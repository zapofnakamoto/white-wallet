package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.core.model.MintScan
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.LookupMint.Companion.error
import whitewallet.core.wallet.LookupMint.Companion.note
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * "Scan for notes" ([Wallet.scan]) against a fake mint: the notes it finds, the ones it already holds (another phone with the phrase may
 * have spent them), and what it does where a mint can't be scanned.
 */
class ScanTest {
    private val clock = 1_800_000_000_000L

    @Test
    fun `a scan looks up the notes it holds, and one spent elsewhere leaves the balance`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val spent = held(wallet, KeyId(accountId, mint.host, KeySet.Wallet, 0), 5_000)
                val kept = held(wallet, KeyId(accountId, mint.host, KeySet.Change, 0), 2_000)
                mint.lookups[spent] = error("already in use")
                mint.lookups[kept] = note(2_000)
                mint.lookups[key(wallet, accountId, mint, KeySet.Wallet, 3)] = note(3_000)

                assertEquals(ScanResult(3_000, emptyList()), wallet.scan(), "found: the new note, whatever left the balance")
                assertEquals(NoteState.Spent, wallet.store.note(spent)?.state)
                assertEquals(NoteState.Outstanding, wallet.store.note(kept)?.state)
                assertEquals(5_000, wallet.balanceMsat.first())
            }
        }

    @Test
    fun `a scan says how many of the notes it holds it has looked up`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                for (i in 0L until 3) {
                    mint.lookups[held(wallet, KeyId(accountId, mint.host, KeySet.Wallet, i), 1_000)] = note(1_000)
                }
                val seen = mutableListOf<ScanProgress>()
                wallet.scan { host, progress -> if (host == mint.host) seen += progress }
                val checking = seen.filter { it.notesToCheck > 0 }
                assertEquals(listOf(0, 1, 2, 3), checking.map { it.notesChecked }, "from none to all, after the walk for new notes")
                assertEquals(setOf(3), checking.map { it.notesToCheck }.toSet())
                assertEquals(seen.last { it.notesToCheck == 0 }.accounts, checking.last().accounts, "the walk's accounts stay on screen")
            }
        }

    @Test
    fun `a scan that meets a mint whose signing key changed stops there and says so, and never throws`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val store = InMemoryWalletStore()
                val (wallet, accountId) = wallet(mint, store)
                mint.lookups[key(wallet, accountId, mint, KeySet.Wallet, 0)] = note(5_000)
                val pinned = assertNotNull(store.mint(mint.host))
                store.saveMint(pinned.copy(mintPubkey = "02" + "11".repeat(32)))

                assertEquals(ScanResult(0, listOf(mint.host)), wallet.scan())
                assertEquals(setOf(mint.host), wallet.mintsWithChangedKey.first())
                assertEquals(0, wallet.balanceMsat.first(), "nothing comes in from a mint whose key doesn't match")
                assertEquals(MintScan.Unfinished, store.mint(mint.host)?.scan)
            }
        }

    private suspend fun wallet(mint: LookupMint, store: InMemoryWalletStore = InMemoryWalletStore()): Pair<Wallet, String> {
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), now = { clock })
        val accountId = assertIs<CreateAccountResult.Created>(wallet.createAccount(mint.host, "Main")).accountId
        return wallet to accountId
    }

    private suspend fun key(wallet: Wallet, accountId: String, mint: LookupMint, set: KeySet, index: Long): String =
        wallet.keys(accountId, mint.host).pubkey(set, index).toHex()

    /** A note of the wallet's own at [id] worth [amountMsat]; its key as hex. */
    private suspend fun held(wallet: Wallet, id: KeyId, amountMsat: Long): String {
        val q = wallet.keys(id.accountId, id.host).pubkey(id).toHex()
        wallet.store.upsertNotes(
            listOf(
                Note(q, id.accountId, id.host, NoteKind.KeyPath, id.set.purpose, id.index, amountMsat, null, NoteState.Outstanding, clock)
            )
        )
        return q
    }
}
