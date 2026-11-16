@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.core.model.OutputRef
import whitewallet.core.wallet.LookupMint.Companion.error
import whitewallet.core.wallet.LookupMint.Companion.note
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * A mint whose signing key doesn't match the pinned one never changes what the wallet holds there: an answer that a note is already in use
 * carries no key, nor do most answers about an open change, so the key is compared before any note is looked up. A server with another key
 * in the mint's place could otherwise take notes out of the balance. One that presents the pinned key isn't stopped by this.
 */
class ChangedKeyHeldNotesTest {
    private val clock = 1_800_000_000_000L

    @Test
    fun `a scan leaves the notes it holds on a mint whose signing key changed as they are`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, held) = walletHolding(mint)
                assertEquals(ScanResult(0, listOf(mint.host)), wallet.scan())
                assertEquals(NoteState.Outstanding, wallet.store.note(held)?.state)
                assertEquals(setOf(mint.host), wallet.mintsWithChangedKey.first())
            }
        }

    @Test
    fun `checking a note on a mint whose signing key changed leaves it as it is`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, held) = walletHolding(mint)
                assertEquals(NoteCheck.KeyChanged(mint.host), wallet.recheckNote(held))
                assertEquals(NoteState.Outstanding, wallet.store.note(held)?.state)
            }
        }

    @Test
    fun `checking every note leaves those on a mint whose signing key changed as they are`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, held) = walletHolding(mint)
                val check = wallet.recheckNotes()
                assertTrue(mint.host in check.unreachable, "$check")
                assertEquals(0, check.gone)
                assertEquals(NoteState.Outstanding, wallet.store.note(held)?.state)
            }
        }

    @Test
    fun `a refresh leaves a change on a mint whose signing key changed waiting for an answer, and settles it once the key matches`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, input) = walletHolding(mint, NoteState.Ambiguous)
                val (entry, output) = openChange(wallet, mint, input)
                mint.lookups[output] = note(5_000)

                wallet.refresh()
                assertEquals(JournalStatus.Unknown, wallet.store.journal(JournalStatus.entries.toSet()).single { it.id == entry }.status)
                assertEquals(NoteState.Ambiguous, wallet.store.note(input)?.state)
                assertEquals(null, wallet.store.note(output), "its output wasn't looked up")

                // The key it presents matches again: the same refresh settles the change.
                val walletId = wallet.walletOf(assertNotNull(wallet.store.note(input)).accountId)
                wallet.store.saveMint(assertNotNull(wallet.store.mint(walletId, mint.host)).copy(mintPubkey = LookupMint.PUBKEY))
                wallet.refresh()
                assertEquals(JournalStatus.Confirmed, wallet.store.journal(JournalStatus.entries.toSet()).single { it.id == entry }.status)
                assertEquals(NoteState.Spent, wallet.store.note(input)?.state)
                assertEquals(NoteState.Outstanding, wallet.store.note(output)?.state)
            }
        }

    @Test
    fun `checking a note waiting for an answer on a mint whose signing key changed leaves it waiting`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, input) = walletHolding(mint, NoteState.Ambiguous)
                val (entry, output) = openChange(wallet, mint, input)
                mint.lookups[output] = note(5_000)

                assertEquals(NoteCheck.KeyChanged(mint.host), wallet.recheckNote(input))
                assertEquals(JournalStatus.Unknown, wallet.store.journal(JournalStatus.entries.toSet()).single { it.id == entry }.status)
                assertEquals(NoteState.Ambiguous, wallet.store.note(input)?.state)
            }
        }

    /** A rotation of [input] whose answer never came: its journal entry's id, and its output's key. */
    private suspend fun openChange(wallet: Wallet, mint: LookupMint, input: String): Pair<String, String> {
        val accountId = assertNotNull(wallet.store.note(input)).accountId
        val output = wallet.keys(accountId, mint.host).pubkey(KeySet.Wallet, 1).toHex()
        wallet.store.putJournal(
            JournalEntry(
                "open",
                accountId,
                OperationType.Rotate,
                mint.host,
                mint.withdrawUrl,
                listOf(input),
                listOf("k1"),
                listOf(OutputRef(accountId, 0, 1, output)),
                "${mint.withdrawUrl}/cb",
                JournalStatus.Unknown,
                null,
                clock,
                clock,
            )
        )
        return "open" to output
    }

    /** A wallet holding a note on [mint], which the mint says is already in use, and a pinned key the mint doesn't present; its key. */
    private suspend fun walletHolding(mint: LookupMint, state: NoteState = NoteState.Outstanding): Pair<Wallet, String> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), now = { clock })
        val accountId = assertIs<CreateAccountResult.Created>(wallet.createAccount(mint.host, "Main")).accountId
        val q = wallet.keys(accountId, mint.host).pubkey(KeySet.Wallet, 0).toHex()
        store.upsertNotes(listOf(Note(q, accountId, mint.host, NoteKind.KeyPath, 0, 0, 5_000, null, state, clock)))
        mint.lookups[q] = error("already in use")
        val pinned = assertNotNull(store.mint(mint.host))
        store.saveMint(pinned.copy(mintPubkey = "02" + "11".repeat(32)))
        return wallet to q
    }
}
