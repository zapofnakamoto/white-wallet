@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import fr.acinq.bitcoin.PrivateKey
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.LookupMint.Companion.error
import whitewallet.core.wallet.LookupMint.Companion.note
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.spendDomain

/**
 * A mint's wording never spends a note nor drops one: "spent" or "unknown" in a refusal is how one mint words it (lnurl-mint 0.13.1 says
 * "Invalid or already spent k1." for a burned note, an unknown one and a bad signature alike), a hint. Only a lookup by `p` answered
 * `already in use` shows a note burned (LUD-25). Against a fake mint whose answers each test sets.
 */
class MintWordingTest {
    private val clock = 1_800_000_000_000L

    @Test
    fun `a lookup's wording never spends a note the wallet holds nor sets it aside`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val id = KeySet.Wallet.key(accountId, mint.host, 900)
                val q = held(wallet, id, NoteState.Outstanding)
                val prober = KeyProber(wallet)
                for (reason in WORDING + OTHERS) {
                    mint.lookups[q] = error(reason)
                    assertIs<KeyProbe.Refused>(prober.probe(id, wallet.mintOf(accountId, mint.host)!!), reason)
                    assertEquals(NoteState.Outstanding, wallet.store.note(q)?.state, reason)
                }
                mint.lookups[q] = error("Unknown note.")
                assertIs<KeyProbe.Unused>(prober.probe(id, wallet.mintOf(accountId, mint.host)!!))
                assertEquals(NoteState.Unknown, wallet.store.note(q)?.state, "set aside, never dropped")
                mint.lookups[q] = error("already in use")
                assertIs<KeyProbe.Spent>(prober.probe(id, wallet.mintOf(accountId, mint.host)!!))
                assertEquals(NoteState.Spent, wallet.store.note(q)?.state)
            }
        }

    @Test
    fun `a scan counts a key neither used nor unused on a mint's wording`() =
        runBlocking<Unit> {
            for (reason in WORDING + OTHERS) {
                LookupMint().use { mint ->
                    val (wallet, accountId) = wallet(mint)
                    val q = wallet.keys(accountId, mint.host).pubkey(KeySet.Wallet, 0).toHex()
                    mint.lookups[q] = error(reason)
                    val result = IndexDiscovery(wallet).discover(accountId, mint.host, KeySet.Wallet)
                    assertEquals(reason, result.stoppedEarly, "stopped at the key, to ask again later")
                    assertEquals(0, wallet.store.indexOf(accountId, mint.host, KeySet.Wallet), reason)
                }
            }
        }

    @Test
    fun `a melt is paid only once its note's lookup says already in use`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val q = held(wallet, KeySet.Wallet.key(accountId, mint.host, 900), NoteState.Paying)
                val entry = entry(mint, accountId, OperationType.Melt, q, detail = MeltReconciler.IN_FLIGHT)
                wallet.store.putJournal(entry)
                for (reason in WORDING + OTHERS + "Unknown note.") {
                    mint.lookups[q] = error(reason)
                    assertEquals(JournalStatus.Unknown, MeltReconciler(wallet).reconcile(entry, final = true), reason)
                    assertEquals(NoteState.Paying, wallet.store.note(q)?.state, reason)
                }
                mint.lookups[q] = error("already in use")
                assertEquals(JournalStatus.Confirmed, MeltReconciler(wallet).reconcile(entry))
                assertEquals(NoteState.Spent, wallet.store.note(q)?.state)
            }
        }

    @Test
    fun `a shared note is spent only once its lookup says already in use`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val q = held(wallet, KeySet.Wallet.key(accountId, mint.host, 900), NoteState.Shared)
                for (reason in WORDING + OTHERS) {
                    mint.lookups[q] = error(reason)
                    NoteSender(wallet).reconcile(accountId)
                    assertEquals(NoteState.Shared, wallet.store.note(q)?.state, reason)
                }
                mint.lookups[q] = error("already in use")
                NoteSender(wallet).reconcile(accountId)
                assertEquals(NoteState.Spent, wallet.store.note(q)?.state)
            }
        }

    @Test
    fun `a mutation with no output to look up landed only if its inputs are burned`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val q = held(wallet, KeySet.Wallet.key(accountId, mint.host, 900), NoteState.Ambiguous)
                val entry = entry(mint, accountId, OperationType.Rotate, q)
                wallet.store.putJournal(entry)
                for (reason in WORDING + "Unknown note.") {
                    mint.lookups[q] = error(reason)
                    assertEquals(JournalStatus.Unknown, Reconciler(wallet).reconcile(entry), reason)
                    assertEquals(NoteState.Ambiguous, wallet.store.note(q)?.state, reason)
                }
                mint.lookups[q] = error("already in use")
                assertEquals(JournalStatus.Confirmed, Reconciler(wallet).reconcile(entry))
                assertEquals(NoteState.Spent, wallet.store.note(q)?.state)
            }
        }

    @Test
    fun `a renew the mint refuses in its words sets aside only the notes a lookup finds gone`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val q = held(wallet, KeySet.Wallet.key(accountId, mint.host, 900), NoteState.Outstanding)
                mint.lookups[q] = note(5_000)
                mint.onCallback = { mint.lookups[q] = error("Note already spent.") }
                mint.callback = error("Invalid or already spent k1.")
                val note = wallet.store.note(q)!!
                val refused = assertIs<MutationResult.Refused>(rotate(wallet, accountId, mint, note))
                assertEquals(emptyList(), refused.setAside)
                assertEquals(NoteState.Outstanding, wallet.store.note(q)?.state, "the lookup's wording proves nothing either")

                mint.lookups[q] = error("already in use")
                val gone = assertIs<MutationResult.Refused>(rotate(wallet, accountId, mint, note))
                assertEquals(listOf(q), gone.setAside, "the lookup before the renew finds it burned")
                assertEquals(NoteState.Spent, wallet.store.note(q)?.state)
            }
        }

    @Test
    fun `a received note refused in the mint's words keeps its spend unless a lookup finds it burned`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                for (after in WORDING + "Unknown note.") {
                    val received = received(wallet, accountId, mint)
                    mint.lookups[received.id] = note(5_000)
                    mint.callback = error("Invalid or already spent k1.")
                    mint.onCallback = { mint.lookups[received.id] = error(after) }
                    ReceivedNotes(wallet).claim(received)
                    val kept = wallet.store.receivedNote(received.id)
                    assertEquals(ReceivedState.Waiting, kept?.state, after)
                    assertEquals(received.k1, kept?.k1, "its spend is the only copy: $after")
                }
                val received = received(wallet, accountId, mint)
                mint.lookups[received.id] = note(5_000)
                mint.onCallback = { mint.lookups[received.id] = error("already in use") }
                ReceivedNotes(wallet).claim(received)
                assertEquals(ReceivedState.Gone, wallet.store.receivedNote(received.id)?.state)
            }
        }

    @Test
    fun `a received note its mint words as gone before the claim keeps its spend`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                for (answer in WORDING.map(::error) + error("Unknown note.")) {
                    val received = received(wallet, accountId, mint)
                    mint.lookups[received.id] = answer
                    ReceivedNotes(wallet).claim(received)
                    val kept = wallet.store.receivedNote(received.id)
                    assertEquals(ReceivedState.Waiting, kept?.state, answer)
                    assertEquals(received.k1, kept?.k1, answer)
                }
                assertEquals(0, mint.callbacks, "nothing was sent")
                val received = received(wallet, accountId, mint)
                mint.lookups[received.id] = error("already in use")
                ReceivedNotes(wallet).claim(received)
                assertEquals(ReceivedState.Gone, wallet.store.receivedNote(received.id)?.state)
            }
        }

    @Test
    fun `a claim that didn't land keeps the note's spend unless a lookup finds it burned`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                for (answer in WORDING.map(::error) + error("Unknown note.")) {
                    val received = claiming(wallet, accountId, mint)
                    mint.lookups[received.id] = answer
                    mint.callback = error("pending")
                    ReceivedNotes(wallet).retryAll(setOf(accountId))
                    val kept = wallet.store.receivedNote(received.id)
                    assertEquals(ReceivedState.Waiting, kept?.state, answer)
                    assertEquals(received.k1, kept?.k1, answer)
                }
                val received = claiming(wallet, accountId, mint)
                mint.lookups[received.id] = error("already in use")
                ReceivedNotes(wallet).retryAll(setOf(accountId))
                assertEquals(ReceivedState.Gone, wallet.store.receivedNote(received.id)?.state)
                assertNull(wallet.store.receivedNote(received.id)?.k1)
            }
        }

    @Test
    fun `Claim says a note is spent only when its lookup says already in use, and shows any other answer`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val claimer = NoteClaimer(wallet)
                for (reason in WORDING + "Unknown note.") {
                    val (q, link) = link(mint)
                    mint.lookups[q] = error(reason)
                    assertEquals(ClaimPreview.MintRefused(reason), claimer.preview(link, accountId), reason)
                }
                val (q, link) = link(mint)
                mint.lookups[q] = error("already in use")
                assertEquals(ClaimPreview.AlreadySpent, claimer.preview(link, accountId))

                val locked = LockedNote.until(clock / 1000 + 3600)
                val lockedLink = locked.link(mint.host, mint.withdrawUrl)
                for (reason in WORDING + "Unknown note.") {
                    mint.lookups[locked.q.toHex()] = error(reason)
                    val preview = assertIs<ClaimPreview.Locked>(claimer.preview(lockedLink, accountId), reason)
                    assertNull(preview.amountMsat, reason)
                }
                mint.lookups[locked.q.toHex()] = error("already in use")
                assertEquals(ClaimPreview.AlreadySpent, claimer.preview(lockedLink, accountId))
            }
        }

    private suspend fun wallet(mint: LookupMint): Pair<Wallet, String> {
        val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), now = { clock })
        val accountId = (wallet.createAccount(mint.host, "Main") as CreateAccountResult.Created).accountId
        assertNotNull(wallet.mintOf(accountId, mint.host)?.mintPubkey, "connected, its key pinned")
        return wallet to accountId
    }

    /** A note of the wallet's own at [id], in [state]; its key as hex. */
    private suspend fun held(wallet: Wallet, id: KeyId, state: NoteState): String {
        val q = wallet.keys(id.accountId, id.host).pubkey(id).toHex()
        wallet.store.upsertNotes(
            listOf(Note(q, id.accountId, id.host, NoteKind.KeyPath, id.set.purpose, id.index, 5_000, null, state, clock))
        )
        return q
    }

    private fun entry(mint: LookupMint, accountId: String, type: OperationType, q: String, detail: String? = null) =
        JournalEntry(
            id = "${type.name.lowercase()}-$q",
            accountId,
            type,
            mint.host,
            mint.withdrawUrl,
            inputIds = listOf(q),
            inputK1s = emptyList(),
            outputs = emptyList(),
            requestUrl = "",
            status = JournalStatus.Unknown,
            detail = detail,
            createdAtMillis = clock,
            updatedAtMillis = clock,
        )

    private suspend fun rotate(wallet: Wallet, accountId: String, mint: LookupMint, note: Note): MutationResult =
        NoteMutator(wallet).rotate(accountId, mint.host, listOf(note), listOf(OutputSpec(accountId, KeySet.Wallet)))

    /** Someone else's key-path note on [mint], as a link would hand it over: its key as hex, and the link. */
    private fun link(mint: LookupMint): Pair<String, String> {
        val spend = Spend.keyPath(PrivateKey(ByteArray(32).also(SecureRandom()::nextBytes)), spendDomain(mint.host))
        return spend.q.toHex() to NoteLink(mint.withdrawUrl, spend, null, null).encode()
    }

    /** Someone else's note on [mint], kept by [accountId] and waiting to be claimed. */
    private suspend fun received(wallet: Wallet, accountId: String, mint: LookupMint): ReceivedNote {
        val spend = Spend.keyPath(PrivateKey(ByteArray(32).also(SecureRandom()::nextBytes)), spendDomain(mint.host))
        val note =
            ReceivedNote(
                spend.q.toHex(),
                accountId,
                mint.host,
                mint.withdrawUrl,
                spend.k1,
                certificate = null,
                declaredMsat = null,
                amountMsat = null,
                lockedUntilMillis = null,
                lockedForSeconds = null,
                state = ReceivedState.Waiting,
                journalId = null,
                receivedAtMillis = clock,
                updatedAtMillis = clock,
            )
        wallet.store.putReceivedNote(note)
        return note
    }

    /** Someone else's note on [mint], whose claim was sent and refused. */
    private suspend fun claiming(wallet: Wallet, accountId: String, mint: LookupMint): ReceivedNote {
        val note = received(wallet, accountId, mint)
        val claim = entry(mint, accountId, OperationType.Rotate, note.id).copy(status = JournalStatus.Rejected)
        wallet.store.putJournal(claim)
        return note.copy(state = ReceivedState.Claiming, journalId = claim.id).also { wallet.store.putReceivedNote(it) }
    }

    private companion object {
        /** lnurl-mint's words for a note it won't spend: never proof, whatever they say. */
        val WORDING = listOf("Note already spent.", "Invalid or already spent k1.")

        /** Refusals that name no spent or unknown note at all, though they hold such words. */
        val OTHERS = listOf("unknown error, try again later", "locktime not reached: the note cannot be spent before 2027-01-01")
    }
}
