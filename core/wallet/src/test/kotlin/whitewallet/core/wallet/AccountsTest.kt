package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.Account
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.KeyScheme
import whitewallet.core.model.MintScan
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.spendDomain
import whitewallet.testkit.MockMint

class AccountsTest {
    private val wordsA = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val wordsB = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    /** A signing key no mint here has. */
    private val otherKey = "02" + "11".repeat(32)

    @Test
    fun `switching accounts changes profile, notes and backup state`() =
        runBlocking<Unit> {
            val store = InMemoryWalletStore()
            val seeds = InMemorySeedStore(mapOf("a" to wordsA, "b" to wordsB))
            val wallet = Wallet(store, seeds, LnurlcashClient())
            store.addAccount("a", "Main", PrimaryAddress("alice", "mint.example"))
            store.addAccount("b", "Savings", PrimaryAddress("bob", "other.example"))
            store.upsertNotes(listOf(note("n1", "a", 3_000), note("n2", "b", 5_000)))
            seeds.setBackupVerified("a")

            assertEquals("bob", wallet.primaryAddress.first()?.username)
            assertEquals(5_000, wallet.balanceMsat.first())
            assertEquals(false, wallet.backupVerified.first())
            assertEquals(wordsB, wallet.recoveryPhrase())

            wallet.switchAccount("a")
            assertEquals("alice", wallet.primaryAddress.first()?.username)
            assertEquals(3_000, wallet.balanceMsat.first())
            assertEquals(true, wallet.backupVerified.first())
            assertEquals(wordsA, wallet.recoveryPhrase())
            assertEquals(listOf(3_000L, 5_000L), wallet.accounts.first().map { it.balanceMsat })
        }

    private fun note(id: String, account: String, msat: Long, host: String = "mint.example", purpose: Int = 0, index: Long = 0) =
        Note(id, account, host, NoteKind.KeyPath, purpose, index, msat, null, NoteState.Outstanding, 0, scheme = KeyScheme.Bip32)

    // ---- Against the conformance mock mint

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, val keysA: AccountKeys)

    private fun withMint(test: suspend Fixture.() -> Unit) =
        runBlocking<Unit> {
            assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
            MockMint.start().use { mint ->
                val store = InMemoryWalletStore()
                val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to wordsA, "b" to wordsB)), LnurlcashClient())
                store.addAccount("b", "Savings", PrimaryAddress("bob", mint.host))
                store.addAccount("a", "Main", PrimaryAddress("alice", mint.host))
                assertIs<ConnectResult.Connected>(wallet.connectMint(mint.host))
                Fixture(mint, store, wallet, testKeys(wordsA, mint.host)).test()
            }
        }

    /** As [withMint], with a second mint the wallet of account "a" is connected to as well. */
    private fun withTwoMints(test: suspend Fixture.(other: MockMint) -> Unit) = withMint {
        MockMint.start().use { other ->
            assertIs<ConnectResult.Connected>(wallet.connectMint(other.host))
            test(other)
        }
    }

    /** Credits a purpose-0 note of account "a" at the mock mint [at] and records it, as a claim would have. */
    private suspend fun Fixture.give(msat: Long, at: MockMint = mint) {
        val index = store.reserveKeyIndex("a", spendDomain(at.host), KeyCounter.Wallet)
        val q = (if (at === mint) keysA else testKeys(wordsA, at.host)).pubkey(KeySet.Wallet, index)
        at.credit(Cp1.encode(q), msat)
        store.upsertNotes(listOf(note(q.toHex(), "a", msat, at.host, 0, index)))
    }

    /** The wallet of account "b" has [host] in its list already, pinned to [key]. */
    private suspend fun Fixture.pinForB(host: String, key: String?) =
        store.saveMint(assertNotNull(store.mint("a", host)).copy(walletId = "b", mintPubkey = key))

    private suspend fun Fixture.balance(account: String) = store.accounts.first().first { it.account.id == account }.balanceMsat

    @Test
    fun `a refresh is the active wallet's, another wallet's payments and operations wait until it is active`() = withMint {
        wallet.switchAccount("b")
        assertIs<ConnectResult.Connected>(wallet.connectMint(mint.host))
        wallet.switchAccount("a")
        val keysB = testKeys(wordsB, mint.host)
        mint.credit(Cp1.encode(keysB.pubkey(KeySet.share(0), 0)), 4_000)
        val open =
            JournalEntry(
                "j1",
                "b",
                OperationType.Rotate,
                mint.host,
                "https://${mint.host}/w",
                emptyList(),
                emptyList(),
                emptyList(),
                "",
                JournalStatus.Unknown,
                null,
                0,
                0,
            )
        store.putJournal(open)

        wallet.refresh()
        assertEquals(0, balance("b"))
        assertEquals(listOf(JournalStatus.Unknown), store.journal(JournalStatus.entries.toSet()).map { it.status })
        assertEquals(0, wallet.unresolvedOperations())

        store.putJournal(open.copy(status = JournalStatus.Rejected))
        wallet.switchAccount("b")
        wallet.refresh()
        assertEquals(4_000, balance("b"))
    }

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
    fun `a move to another wallet adds the mint to that wallet, with the key the sending wallet pinned`() = withMint {
        give(21_000)
        assertEquals(null, store.mint("b", mint.host))
        assertEquals(listOf(mint.host), wallet.moveAddsMints("b", null))
        assertEquals(MoveResult.Moved(21_000), wallet.moveFunds("b", null))
        val added = assertNotNull(store.mint("b", mint.host), "the receiving wallet knows the mint its note is at")
        assertEquals(assertNotNull(store.mint("a", mint.host)?.mintPubkey), added.mintPubkey)
        assertEquals(MintScan.Never, added.scan)
        assertEquals(emptyList<String>(), wallet.moveAddsMints("b", null))
    }

    @Test
    fun `a move to a wallet that pinned another key for the mint is refused without touching anything`() = withMint {
        give(21_000)
        pinForB(mint.host, otherKey)
        val result = assertIs<MoveResult.Failed>(wallet.moveFunds("b", null))
        assertEquals(MoveFailure.Refused, result.reason)
        assertEquals(21_000, balance("a"))
        assertEquals(0, balance("b"))
        assertTrue(store.journal.isEmpty())
    }

    @Test
    fun `a partial move to a wallet that pinned another key for the mint is refused without touching anything`() = withMint {
        give(21_000)
        pinForB(mint.host, otherKey)
        val result = assertIs<MoveResult.Failed>(wallet.moveFunds("b", 5_000))
        assertEquals(MoveFailure.Refused, result.reason)
        assertEquals(MINT_KEY_CHANGED, result.detail)
        assertEquals(listOf(MoveStayed(mint.host, 5_000, StayReason.OtherWalletKey)), result.stayed)
        assertEquals(21_000, balance("a"))
        assertTrue(store.journal.isEmpty())
    }

    @Test
    fun `a move to a wallet that has the mint without a key goes on`() = withMint {
        give(21_000)
        pinForB(mint.host, null)
        assertEquals(emptyList<String>(), wallet.moveAddsMints("b", null))
        assertEquals(MoveResult.Moved(21_000), wallet.moveFunds("b", null))
        assertEquals(21_000, balance("b"))
    }

    @Test
    fun `a mint that doesn't answer isn't added, and nothing moves`() = withMint {
        give(21_000)
        mint.close()
        val result = assertIs<MoveResult.Failed>(wallet.moveFunds("b", null))
        assertEquals(MoveFailure.Refused, result.reason)
        assertEquals(listOf(MoveStayed(mint.host, 21_000, StayReason.Unreachable)), result.stayed)
        assertEquals(null, store.mint("b", mint.host))
        assertEquals(21_000, balance("a"))
        assertTrue(store.journal.isEmpty())
    }

    @Test
    fun `a mint that now shows another key than the sending wallet pinned isn't added, and nothing moves`() = withMint {
        give(21_000)
        store.saveMint(assertNotNull(store.mint("a", mint.host)).copy(mintPubkey = otherKey))
        val result = assertIs<MoveResult.Failed>(wallet.moveFunds("b", 5_000))
        assertEquals(MoveFailure.Refused, result.reason)
        assertEquals(MINT_KEY_CHANGED, result.detail)
        assertEquals(listOf(MoveStayed(mint.host, 5_000, StayReason.KeyChanged)), result.stayed)
        assertEquals(null, store.mint("b", mint.host))
        assertEquals(21_000, balance("a"))
        assertTrue(store.journal.isEmpty())
    }

    @Test
    fun `moving everything from several mints moves what can move, and says which mint's notes stayed and why`() = withTwoMints { other ->
        give(3_000)
        give(9_000, other)
        other.close()
        assertEquals(
            MoveResult.Moved(3_000, listOf(MoveStayed(other.host, 9_000, StayReason.Unreachable))),
            wallet.moveFunds("b", null),
        )
        assertNotNull(store.mint("b", mint.host))
        assertEquals(null, store.mint("b", other.host))
        assertEquals(9_000, balance("a"))
        assertEquals(3_000, balance("b"))
        assertEquals(
            listOf(other.host),
            store.allNotes().filter { it.state == NoteState.Outstanding && it.accountId == "a" }.map { it.host },
        )
    }

    @Test
    fun `a mint that refuses the move itself keeps its notes, and the other mints' notes still move`() = withTwoMints { other ->
        // A note the wallet has at the other mint, which that mint doesn't know: the move of it is refused there.
        val index = store.reserveKeyIndex("a", spendDomain(other.host), KeyCounter.Wallet)
        val q = testKeys(wordsA, other.host).pubkey(KeySet.Wallet, index)
        store.upsertNotes(listOf(note(q.toHex(), "a", 9_000, other.host, 0, index)))
        give(3_000)
        val moved = assertIs<MoveResult.Moved>(wallet.moveFunds("b", null))
        assertEquals(3_000, moved.amountMsat)
        assertEquals(listOf(MoveStayed(other.host, 9_000, StayReason.Refused)), moved.stayed)
        assertEquals(3_000, balance("b"))
    }

    @Test
    fun `what a move would do is asked of the mints first, and changes nothing`() = withTwoMints { other ->
        give(3_000)
        give(9_000, other)
        assertEquals(MoveCheck(12_000, emptyList()), wallet.checkMoveFunds("b", null))
        assertEquals(MoveCheck(5_000, emptyList()), wallet.checkMoveFunds("b", 5_000))
        assertEquals(MoveCheck(0, emptyList()), wallet.checkMoveFunds("b", 10_000), "no single mint holds that much")

        // The other wallet knows the first mint by another key; the second one stops answering.
        pinForB(mint.host, otherKey)
        assertEquals(MoveCheck(9_000, listOf(MoveStayed(mint.host, 3_000, StayReason.OtherWalletKey))), wallet.checkMoveFunds("b", null))
        other.close()
        assertEquals(
            setOf(MoveStayed(mint.host, 3_000, StayReason.OtherWalletKey), MoveStayed(other.host, 9_000, StayReason.Unreachable)),
            wallet.checkMoveFunds("b", null).also { assertEquals(0, it.movableMsat) }.stayed.toSet(),
        )
        assertEquals(MoveCheck(0, listOf(MoveStayed(other.host, 5_000, StayReason.Unreachable))), wallet.checkMoveFunds("b", 5_000))

        assertEquals(null, store.mint("b", other.host), "asking adds no mint")
        assertEquals(12_000, balance("a"))
        assertTrue(store.journal.isEmpty())
    }

    @Test
    fun `a mint that shows another key than the one pinned can't take part, as the check says before the move`() = withMint {
        give(21_000)
        store.saveMint(assertNotNull(store.mint("a", mint.host)).copy(mintPubkey = otherKey))
        assertEquals(MoveCheck(0, listOf(MoveStayed(mint.host, 21_000, StayReason.KeyChanged))), wallet.checkMoveFunds("b", null))
        // To an account of the same wallet too: the wallet stopped working with that mint.
        store.saveAccount(Account("a2", "Spending", 2, walletId = "a", index = 1, homeHost = mint.host))
        assertEquals(MoveCheck(0, listOf(MoveStayed(mint.host, 21_000, StayReason.KeyChanged))), wallet.checkMoveFunds("a2", null))
        val result = assertIs<MoveResult.Failed>(wallet.moveFunds("a2", null))
        assertEquals(listOf(MoveStayed(mint.host, 21_000, StayReason.KeyChanged)), result.stayed)
        assertEquals(21_000, balance("a"))
    }

    @Test
    fun `a move of an amount adds only the mint the amount is taken from`() = withTwoMints { other ->
        give(3_000)
        give(9_000, other)
        assertEquals(setOf(mint.host, other.host), wallet.moveAddsMints("b", null).toSet())
        assertEquals(listOf(other.host), wallet.moveAddsMints("b", 5_000))
        assertEquals(emptyList<String>(), wallet.moveAddsMints("b", 10_000), "no single mint holds that much: nothing would move")

        assertEquals(MoveResult.Moved(5_000), wallet.moveFunds("b", 5_000))
        assertNotNull(store.mint("b", other.host))
        assertEquals(null, store.mint("b", mint.host))
        assertEquals(listOf(mint.host), wallet.moveAddsMints("b", null))
    }

    @Test
    fun `a move to another account of the same wallet adds no mint`() = withMint {
        store.saveAccount(Account("a2", "Spending", 2, walletId = "a", index = 1, homeHost = mint.host))
        give(21_000)
        assertEquals(emptyList<String>(), wallet.moveAddsMints("a2", null))
        assertEquals(MoveResult.Moved(21_000), wallet.moveFunds("a2", null))
        assertEquals(21_000, balance("a2"))
        assertEquals(listOf("a"), store.wallets.first().map { it.id }.filter { store.mint(it, mint.host) != null })
    }

    @Test
    fun `a mint added by a move doesn't become the home mint of a wallet that has none`() = withMint {
        assertIs<CreateAccountResult.Created>(wallet.createWallet("Other"))
        wallet.setSetupStep(null)
        val other = assertNotNull(store.currentAccountId())
        wallet.switchAccount("a")
        give(21_000)
        assertEquals(null, store.account(other)?.homeHost)
        assertEquals(MoveResult.Moved(21_000), wallet.moveFunds(other, null))
        assertNotNull(store.mint(wallet.walletOf(other), mint.host))
        // A home mint is trusted by default: one the wallet only met because a note was moved to it isn't.
        assertEquals(null, store.account(other)?.homeHost)
    }

    @Test
    fun `moving everything merges several notes`() = withMint {
        give(1_000)
        give(2_000)
        give(4_000)
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
        assertTrue(store.allNotes().any { it.accountId == "a" && it.purpose == KeySet.Change.purpose && it.state == NoteState.Outstanding })
    }

    @Test
    fun `moving more than any single note merges first, then splits`() = withMint {
        give(3_000)
        give(4_000)
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
    fun `a new account gets its own recovery phrase and becomes active`() =
        runBlocking<Unit> {
            // Onboarding a second account is covered against a fake mint in OnboardingTest; here only the seed separation.
            val store = InMemoryWalletStore()
            val seeds = InMemorySeedStore(mapOf("a" to wordsA))
            store.addAccount("a", "Main", PrimaryAddress("alice", "mint.example"))
            val wallet = Wallet(store, seeds, LnurlcashClient())
            wallet.createSeed("b")
            assertNotEquals(seeds.load("a"), seeds.load("b"))
            assertEquals(12, seeds.load("b")?.size)
        }
}
