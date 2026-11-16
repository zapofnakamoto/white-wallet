package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.LookupMint.Companion.error
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * A mint whose signing key doesn't match the pinned one never changes what the wallet holds there: an answer that a note is already in use
 * carries no key, so the key is compared before any note is looked up. Whoever answers in its place could otherwise take notes out of the
 * balance.
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

    /** A wallet holding a note on [mint], which the mint says is already in use, and a pinned key the mint doesn't present; its key. */
    private suspend fun walletHolding(mint: LookupMint): Pair<Wallet, String> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), now = { clock })
        val accountId = assertIs<CreateAccountResult.Created>(wallet.createAccount(mint.host, "Main")).accountId
        val q = wallet.keys(accountId, mint.host).pubkey(KeySet.Wallet, 0).toHex()
        store.upsertNotes(listOf(Note(q, accountId, mint.host, NoteKind.KeyPath, 0, 0, 5_000, null, NoteState.Outstanding, clock)))
        mint.lookups[q] = error("already in use")
        val pinned = assertNotNull(store.mint(mint.host))
        store.saveMint(pinned.copy(mintPubkey = "02" + "11".repeat(32)))
        return wallet to q
    }
}
