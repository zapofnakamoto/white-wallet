@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
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
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain

/**
 * A lookup answering 0 (as the draft after the pinned one answers a burned or pending note): never a note worth 0. Spent, or still being
 * paid when the wallet has a melt of that note open.
 */
class ZeroValueLookupTest {
    private val clock = 1_800_000_000_000L

    @Test
    fun `Claim says a note answered 0 is already spent`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val spend = someoneElses(mint)
                mint.lookups[spend.q.toHex()] = note(0)
                val preview = NoteClaimer(wallet).preview(NoteLink(mint.withdrawUrl, spend, null, null).encode(), accountId)
                assertIs<ClaimPreview.AlreadySpent>(preview)
            }
        }

    @Test
    fun `a kept note answered 0 is gone and nothing is sent`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val received = received(wallet, accountId, mint)
                mint.lookups[received.id] = note(0)
                mint.callback = """{"status":"OK"}"""
                ReceivedNotes(wallet).claim(received)
                assertEquals(0, mint.callbacks, "no claim went out")
                assertEquals(ReceivedState.Gone, wallet.store.receivedNote(received.id)?.state)
            }
        }

    @Test
    fun `a held note answered 0 is spent after a scan`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val q = held(wallet, KeyId(accountId, mint.host, KeySet.Wallet, 900), 5_000)
                mint.lookups[q] = note(0)
                wallet.scan()
                assertEquals(NoteState.Spent, wallet.store.note(q)?.state)
                assertEquals(0, wallet.balanceMsat.first())
            }
        }

    @Test
    fun `a scan over a key answered 0 counts it used and goes on`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                mint.lookups[key(wallet, accountId, mint, 0)] = note(0)
                val found = key(wallet, accountId, mint, 1)
                mint.lookups[found] = note(5_000)
                wallet.scan()
                assertEquals(NoteState.Outstanding, wallet.store.note(found)?.state, "the scan went on past the used key")
                assertEquals(5_000, wallet.balanceMsat.first())
                assertTrue(wallet.store.indexOf(accountId, mint.host, KeySet.Wallet) >= 2, "no used key is handed out again")
            }
        }

    @Test
    fun `a scan over a key whose melt is open leaves the note paying and goes on`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val paying = held(wallet, KeyId(accountId, mint.host, KeySet.Wallet, 0), 5_000, NoteState.Paying)
                wallet.store.putJournal(openMelt(accountId, mint, paying))
                mint.lookups[paying] = note(0)
                val found = key(wallet, accountId, mint, 1)
                mint.lookups[found] = note(2_000)
                wallet.scan()
                assertEquals(NoteState.Paying, wallet.store.note(paying)?.state, "still being paid: neither spent nor back")
                assertEquals(NoteState.Outstanding, wallet.store.note(found)?.state)
                assertTrue(wallet.store.indexOf(accountId, mint.host, KeySet.Wallet) >= 2)
            }
        }

    @Test
    fun `a note we shared that is answered 0 is spent`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val shared = held(wallet, KeyId(accountId, mint.host, KeySet.Wallet, 0), 5_000, NoteState.Shared)
                mint.lookups[shared] = note(0)
                NoteSender(wallet).reconcile(accountId)
                assertEquals(NoteState.Spent, wallet.store.note(shared)?.state, "claimed by whoever had the link")
            }
        }

    @Test
    fun `a melt answered 0 0 stays waiting through the final check`() =
        melt(lookup = note(0), verifyUrl = null) { wallet, mint, entry, q ->
            assertEquals(JournalStatus.Unknown, MeltReconciler(wallet).reconcile(entry, final = true))
            assertEquals(NoteState.Paying, wallet.store.note(q)?.state)
            assertEquals(0, mint.verifyAsked.get())
        }

    @Test
    fun `a melt answered 0 0 is paid once verify says settled`() =
        melt(lookup = note(0), verify = settled(PREIMAGE)) { wallet, _, entry, q ->
            assertEquals(JournalStatus.Confirmed, MeltReconciler(wallet).reconcile(entry))
            assertEquals(NoteState.Spent, wallet.store.note(q)?.state)
        }

    @Test
    fun `a melt whose verify says not settled stays waiting`() =
        melt(lookup = note(0), verify = """{"status":"OK","settled":false,"preimage":null}""") { wallet, mint, entry, q ->
            assertEquals(JournalStatus.Unknown, MeltReconciler(wallet).reconcile(entry, final = true))
            assertEquals(NoteState.Paying, wallet.store.note(q)?.state)
            assertEquals(1, mint.verifyAsked.get())
        }

    @Test
    fun `a melt whose verify says not found stays waiting`() =
        melt(lookup = note(0), verify = error("Not found")) { wallet, _, entry, q ->
            assertEquals(JournalStatus.Unknown, MeltReconciler(wallet).reconcile(entry, final = true))
            assertEquals(NoteState.Paying, wallet.store.note(q)?.state)
        }

    @Test
    fun `a settled verify whose preimage doesn't match the invoice stays waiting`() =
        melt(lookup = note(0), verify = settled("22".repeat(32))) { wallet, _, entry, q ->
            assertEquals(JournalStatus.Unknown, MeltReconciler(wallet).reconcile(entry, final = true))
            assertEquals(NoteState.Paying, wallet.store.note(q)?.state)
        }

    @Test
    fun `a melt whose note comes back is failed and the note restored`() =
        melt(lookup = note(5_000), verify = settled(PREIMAGE)) { wallet, mint, entry, q ->
            assertEquals(JournalStatus.Rejected, MeltReconciler(wallet).reconcile(entry))
            assertEquals(NoteState.Outstanding, wallet.store.note(q)?.state)
            assertEquals(0, mint.verifyAsked.get())
        }

    @Test
    fun `verify is asked only after a 0 0 lookup`() =
        melt(lookup = error("already in use"), verify = settled(PREIMAGE)) { wallet, mint, entry, q ->
            assertEquals(JournalStatus.Confirmed, MeltReconciler(wallet).reconcile(entry))
            assertEquals(NoteState.Spent, wallet.store.note(q)?.state)
            assertEquals(0, mint.verifyAsked.get())
        }

    @Test
    fun `a verify url on another host is not kept`() {
        val withdrawUrl = "https://mint.example/w"
        assertNull(keptVerifyUrl("https://evil.example/verify/x", withdrawUrl))
        assertEquals("https://mint.example/verify/x", keptVerifyUrl("https://mint.example/verify/x", withdrawUrl))
        assertNull(keptVerifyUrl("http://mint.example/verify/x", withdrawUrl), "another scheme is another origin")
        assertNull(keptVerifyUrl(null, withdrawUrl))
    }

    /**
     * A melt of a held note into an invoice whose preimage is [PREIMAGE], the mint's answer accepted (in flight); its note looked up as
     * [lookup], `verify` answering [verify] at [verifyUrl] (the mint's own by default).
     */
    private fun melt(
        lookup: String,
        verify: String? = null,
        verifyUrl: ((LookupMint) -> String?)? = { "http://${it.host}/verify/${PAYMENT_HASH}" },
        check: suspend (Wallet, LookupMint, JournalEntry, String) -> Unit,
    ) =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val q = held(wallet, KeyId(accountId, mint.host, KeySet.Wallet, 0), 5_000, NoteState.Paying)
                mint.lookups[q] = lookup
                mint.verify = verify
                val invoice = TestInvoices.invoice(5_000, paymentHashHex = PAYMENT_HASH)
                val entry =
                    openMelt(accountId, mint, q)
                        .copy(
                            requestUrl = Requests.melt("${mint.withdrawUrl}/cb", "ck1x", invoice),
                            verifyUrl = verifyUrl?.invoke(mint),
                        )
                wallet.store.putJournal(entry)
                check(wallet, mint, entry, q)
            }
        }

    private fun settled(preimage: String) = """{"status":"OK","settled":true,"preimage":"$preimage","pr":"lnbcrt1"}"""

    private suspend fun wallet(mint: LookupMint): Pair<Wallet, String> {
        val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), now = { clock })
        val accountId = assertIs<CreateAccountResult.Created>(wallet.createAccount(mint.host, "Main")).accountId
        return wallet to accountId
    }

    private suspend fun key(wallet: Wallet, accountId: String, mint: LookupMint, index: Long): String =
        wallet.keys(accountId, mint.host).pubkey(KeySet.Wallet, index).toHex()

    /** A note of the wallet's own at [id] worth [amountMsat]; its key as hex. */
    private suspend fun held(wallet: Wallet, id: KeyId, amountMsat: Long, state: NoteState = NoteState.Outstanding): String {
        val q = wallet.keys(id.accountId, id.host).pubkey(id).toHex()
        wallet.store.upsertNotes(
            listOf(Note(q, id.accountId, id.host, NoteKind.KeyPath, id.set.purpose, id.index, amountMsat, null, state, clock))
        )
        return q
    }

    /** A melt of [q] the mint accepted, its payment in flight. */
    private fun openMelt(accountId: String, mint: LookupMint, q: String) =
        JournalEntry(
            id = "melt-$q",
            accountId,
            OperationType.Melt,
            mint.host,
            mint.withdrawUrl,
            inputIds = listOf(q),
            inputK1s = listOf("ck1x"),
            outputs = emptyList(),
            requestUrl = "${mint.withdrawUrl}/cb?k1=ck1x&pr=lnbc1",
            status = JournalStatus.Unknown,
            detail = MeltReconciler.IN_FLIGHT,
            createdAtMillis = clock,
            updatedAtMillis = clock,
        )

    private fun someoneElses(mint: LookupMint): Spend.KeyPath =
        Spend.keyPath(PrivateKey(ByteArray(32).also(SecureRandom()::nextBytes)), spendDomain(mint.host))

    /** Someone else's note on [mint], kept by [accountId] and waiting to be claimed. */
    private suspend fun received(wallet: Wallet, accountId: String, mint: LookupMint): ReceivedNote {
        val spend = someoneElses(mint)
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

    private companion object {
        val PREIMAGE = "11".repeat(32)
        val PAYMENT_HASH: String = Hex.encode(Crypto.sha256(Hex.decode(PREIMAGE)))
    }
}
