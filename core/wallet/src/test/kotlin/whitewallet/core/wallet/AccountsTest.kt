package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

class AccountsTest {
    private val wordsA = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val wordsB = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    @Test
    fun `switching accounts changes profile, notes and backup state`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val seeds = InMemorySeedStore(mapOf("a" to wordsA, "b" to wordsB))
        val wallet = Wallet(store, seeds, LnurlcashClient())
        store.addAccount("a", "Main", Profile("alice", "mint.example"))
        store.addAccount("b", "Savings", Profile("bob", "other.example"))
        store.upsertNotes(listOf(note("n1", "a", 3_000), note("n2", "b", 5_000)))
        seeds.setBackupVerified("a")

        assertEquals("bob", wallet.profile.first()?.username)
        assertEquals(5_000, wallet.balanceMsat.first())
        assertEquals(false, wallet.backupVerified.first())
        assertEquals(wordsB, wallet.recoveryPhrase())

        wallet.switchAccount("a")
        assertEquals("alice", wallet.profile.first()?.username)
        assertEquals(3_000, wallet.balanceMsat.first())
        assertEquals(true, wallet.backupVerified.first())
        assertEquals(wordsA, wallet.recoveryPhrase())
        assertEquals(listOf(3_000L, 5_000L), wallet.accounts.first().map { it.balanceMsat })
    }

    private fun note(id: String, account: String, msat: Long, host: String = "mint.example", purpose: Int = 0, index: Long = 0) =
        Note(id, account, host, NoteKind.KeyPath, purpose, index, msat, null, NoteState.Outstanding, 0)

    // ---- Against the conformance mock mint

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, val branchA: CashBranch)

    private fun withMint(test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start().use { mint ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to wordsA, "b" to wordsB)), LnurlcashClient())
            store.addAccount("b", "Savings", Profile("bob", mint.host))
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectMint(mint.host))
            Fixture(mint, store, wallet, CashBranch.fromSeed(MnemonicCode.toSeed(wordsA, ""), mint.host)).test()
        }
    }

    /** Credits a purpose-0 note of account "a" at the mock mint and records it, as a claim would have. */
    private suspend fun Fixture.give(msat: Long) {
        val index = store.reserveIndex("a", mint.host, 0)
        val q = branchA.notePubkey(NotePurpose.Wallet, index)
        mint.credit(Cp1.encode(q), msat)
        store.upsertNotes(listOf(note(q.toHex(), "a", msat, mint.host, 0, index)))
    }

    private suspend fun Fixture.balance(account: String) = store.accounts.first().first { it.account.id == account }.balanceMsat

    @Test
    fun `moving everything rotates a single note into the other account`() = withMint {
        give(21_000)
        assertEquals(MoveResult.Moved(21_000), wallet.moveFunds("b", null))
        assertEquals(0, balance("a"))
        assertEquals(21_000, balance("b"))
        val moved = store.allNotes().single { it.accountId == "b" }
        assertNotNull(moved.certificate, "the mint certified the moved note")
        assertEquals(NoteState.Spent, store.allNotes().single { it.accountId == "a" }.state)
    }

    @Test
    fun `moving everything merges several notes`() = withMint {
        give(1_000); give(2_000); give(4_000)
        assertEquals(MoveResult.Moved(7_000), wallet.moveFunds("b", null))
        assertEquals(0, balance("a"))
        assertEquals(7_000, balance("b"))
    }

    @Test
    fun `moving part of a note splits it and keeps the change`() = withMint {
        give(21_000)
        assertEquals(MoveResult.Moved(5_000), wallet.moveFunds("b", 5_000))
        assertEquals(16_000, balance("a"))
        assertEquals(5_000, balance("b"))
        assertTrue(store.allNotes().any { it.accountId == "a" && it.purpose == NotePurpose.Change.value && it.state == NoteState.Outstanding })
    }

    @Test
    fun `moving more than any single note merges first, then splits`() = withMint {
        give(3_000); give(4_000)
        assertEquals(MoveResult.Moved(6_000), wallet.moveFunds("b", 6_000))
        assertEquals(1_000, balance("a"))
        assertEquals(6_000, balance("b"))
    }

    @Test
    fun `moving more than the balance is refused without touching anything`() = withMint {
        give(1_000)
        val result = assertIs<MoveResult.Failed>(wallet.moveFunds("b", 5_000))
        assertEquals(MoveFailure.InsufficientFunds, result.reason)
        assertEquals(1_000, balance("a"))
        assertTrue(store.journal.isEmpty())
    }

    @Test
    fun `a new account gets its own recovery phrase and becomes active`() = runBlocking<Unit> {
        // Onboarding a second account is covered against a fake mint in OnboardingTest; here only the seed separation.
        val store = InMemoryWalletStore()
        val seeds = InMemorySeedStore(mapOf("a" to wordsA))
        store.addAccount("a", "Main", Profile("alice", "mint.example"))
        val wallet = Wallet(store, seeds, LnurlcashClient())
        wallet.createSeed("b")
        assertNotEquals(seeds.load("a"), seeds.load("b"))
        assertEquals(12, seeds.load("b")?.size)
    }
}
