package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.Mint
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.core.model.OutputRef
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.UnclaimedReason
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * BIP-44's account rule: a restore stops after `scan.stop.after.unused.accounts` unused accounts in a row, so the wallet adds no account a
 * restore wouldn't reach, and says so where one made before the rule already hides money.
 */
class AccountRuleTest {
    private val store = InMemoryWalletStore()

    private fun wallet(gap: Int = 1, maxAccounts: Int = AppDefaults.UNLIMITED) =
        Wallet(
            store,
            InMemorySeedStore(),
            LnurlcashClient(),
            maxAccounts = maxAccounts,
            settings = WalletSettings(scanStopAfterUnusedAccounts = gap),
        )

    /** A wallet of accounts `0 until count`, each [used] or not, and its first account active: the ids by index. */
    private suspend fun Wallet.withAccounts(count: Int, used: Set<Int> = emptySet()): List<String> {
        val first = assertIs<CreateAccountResult.Created>(createWallet("Account 1")).accountId
        setSetupStep(null)
        val ids =
            listOf(first) +
                (1 until count).map { i -> "acc$i".also { store.saveAccount(Account(it, "Account ${i + 1}", 0, first, i.toLong())) } }
        used.forEach { store.useAccount(ids[it]) }
        store.setActiveAccount(first)
        return ids
    }

    private fun note(accountId: String, state: NoteState, q: String = "c".repeat(64)) =
        Note(q, accountId, "mint.example", NoteKind.KeyPath, 0, 0, 1_000, null, state, 0)

    private fun summaries(vararg used: Boolean?) = used.mapIndexedNotNull { i, u ->
        u?.let { AccountSummary(Account("a$i", "A$i", 0, "w", i.toLong()), 0, used = it) }
    }

    @Test
    fun `a wallet's first account is always allowed, and so is any while fewer accounts than the gap come before it`() {
        assertNull(accountToUseFirst(emptyList(), 1))
        assertNull(accountToUseFirst(summaries(false, false), 3))
        assertEquals(UnusedAccount(summaries(false)[0].account, hidesLater = false), accountToUseFirst(summaries(false), 1))
    }

    @Test
    fun `while the last account holds nothing, no account is added, nothing changes and no mint is asked`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) = MockResponse(code = 404)
                    }
                server.start()
                val host = "localhost:${server.port}"
                val wallet = wallet()
                val (first, second) = wallet.withAccounts(2, used = setOf(0))
                store.saveMint(Mint(first, host, "http://$host/w", null, 1_000, 100_000_000, null, null))
                store.setActiveAccount(second)
                val requests = server.requestCount

                val refused = assertIs<CreateAccountResult.UseFirst>(wallet.createAccount("Third"))
                assertEquals(UnusedAccount(checkNotNull(store.account(second)), hidesLater = false), refused.first)
                assertEquals(listOf(first, second), store.accounts.first().sortedBy { it.account.index }.map { it.account.id })
                assertEquals(second, store.currentAccountId())
                assertEquals(requests, server.requestCount, "no mint is asked")
            }
        }

    @Test
    fun `a note in any state makes an account used, so the next one is added`() =
        runBlocking<Unit> {
            for (state in listOf(NoteState.Outstanding, NoteState.Spent, NoteState.Shared, NoteState.Unknown, NoteState.Ambiguous)) {
                val store = InMemoryWalletStore()
                val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient())
                val first = assertIs<CreateAccountResult.Created>(wallet.createWallet("Main")).accountId
                wallet.setSetupStep(null)
                assertIs<CreateAccountResult.UseFirst>(wallet.createAccount("Savings"), "$state")
                store.upsertNotes(listOf(note(first, state)))
                assertTrue(store.accounts.first().single().used, "$state")
                assertIs<CreateAccountResult.Created>(wallet.createAccount("Savings"), "$state")
            }
        }

    @Test
    fun `what a restore can't see doesn't make an account used - an address, an unpaid invoice, a link not claimed yet`() =
        runBlocking<Unit> {
            val wallet = wallet()
            val (first) = wallet.withAccounts(1)
            store.saveAddress(first, RegisteredAddress("alice", "mint.example", 0, share = 0))
            val index = store.reserveKeyIndex(first, "mint.example", KeyCounter.Wallet)
            store.putJournal(
                JournalEntry(
                    "invoice",
                    first,
                    OperationType.Mint,
                    "mint.example",
                    "https://mint.example/w",
                    emptyList(),
                    emptyList(),
                    listOf(OutputRef(first, 0, index, "d".repeat(64))),
                    "lnbcrt1…",
                    JournalStatus.Prepared,
                    null,
                    0,
                    0,
                )
            )
            store.putReceivedNote(
                ReceivedNote(
                    id = "e".repeat(64),
                    accountId = first,
                    host = "mint.example",
                    withdrawUrl = "https://mint.example/withdraw",
                    k1 = "f".repeat(64),
                    certificate = null,
                    declaredMsat = 1_000,
                    amountMsat = null,
                    lockedUntilMillis = null,
                    lockedForSeconds = null,
                    state = ReceivedState.Archived,
                    journalId = null,
                    receivedAtMillis = 0,
                    updatedAtMillis = 0,
                    unclaimedReason = UnclaimedReason.ByUser,
                )
            )
            assertFalse(store.accounts.first().single().used)
            assertIs<CreateAccountResult.UseFirst>(wallet.createAccount("Savings"))
        }

    @Test
    fun `only the account right before the new one counts, not an older one nor another wallet's`() =
        runBlocking<Unit> {
            val wallet = wallet()
            val (_, second) = wallet.withAccounts(2, used = setOf(0))
            store.addAccount("elsewhere", "Elsewhere", null)
            store.useAccount("elsewhere")
            store.setActiveAccount(second)
            assertEquals(second, assertIs<CreateAccountResult.UseFirst>(wallet.createAccount("Third")).first.account.id)
        }

    @Test
    fun `a removed account's index is offered again, judged by the account right before it, not by those after`() =
        runBlocking<Unit> {
            val wallet = wallet()
            val (first, second, third) = wallet.withAccounts(3, used = setOf(0, 1))
            store.setActiveAccount(second)
            assertIs<RemoveAccountResult.Removed>(wallet.removeAccount())
            assertEquals(listOf(0L, 2L), store.accounts.first().map { it.account.index }.sorted())
            assertNull(wallet.accountToUseFirst.first(), "account 3 holds nothing, but the new one takes index 1, after a used account")
            val again = assertIs<CreateAccountResult.Created>(wallet.createAccount("Again")).accountId
            assertEquals(1L, store.account(again)?.index)
            assertEquals(setOf(first, again, third), store.accounts.first().map { it.account.id }.toSet())
        }

    @Test
    fun `with a gap of 3, two unused accounts in a row still allow another, three don't, and the newest is named`() =
        runBlocking<Unit> {
            val wallet = wallet(gap = 3)
            val ids = wallet.withAccounts(3, used = setOf(0))
            assertNull(wallet.accountToUseFirst.first())
            val fourth = assertIs<CreateAccountResult.Created>(wallet.createAccount("Account 4")).accountId
            assertEquals(3L, store.account(fourth)?.index)
            val refused = assertIs<CreateAccountResult.UseFirst>(wallet.createAccount("Account 5"))
            assertEquals(fourth, refused.first.account.id)
            assertEquals(refused.first, wallet.accountToUseFirst.first())
            store.useAccount(ids[1])
            assertIs<CreateAccountResult.Created>(wallet.createAccount("Account 5"), "any of the three, used, ends the run")
        }

    @Test
    fun `the brand's account limit comes first`() =
        runBlocking<Unit> {
            val wallet = wallet(maxAccounts = 2)
            wallet.withAccounts(2)
            assertEquals(CreateAccountResult.AccountLimitReached(2), wallet.createAccount("Third"))
        }

    @Test
    fun `the account to use first follows the wallet's notes as they come`() =
        runBlocking<Unit> {
            val wallet = wallet()
            val (first) = wallet.withAccounts(1)
            assertEquals(first, wallet.accountToUseFirst.first()?.account?.id)
            val next = async { withTimeout(5_000) { wallet.accountToUseFirst.first { it == null } } }
            yield()
            store.upsertNotes(listOf(note(first, NoteState.Outstanding)))
            assertNull(next.await())
        }

    @Test
    fun `an unused account with a used one after it hides that one from a restore, and is named until it is used`() =
        runBlocking<Unit> {
            val wallet = wallet()
            val ids = wallet.withAccounts(5, used = setOf(0, 2, 4))
            val hole = UnusedAccount(checkNotNull(store.account(ids[1])), hidesLater = true)
            assertEquals(hole, wallet.accountToUseFirst.first(), "the first of two holes")
            assertEquals(CreateAccountResult.UseFirst(hole), wallet.createAccount("Sixth"), "no account is added behind it either")
            store.useAccount(ids[1])
            assertEquals(UnusedAccount(checkNotNull(store.account(ids[3])), hidesLater = true), wallet.accountToUseFirst.first())
            store.useAccount(ids[3])
            assertNull(wallet.accountToUseFirst.first())
        }

    @Test
    fun `with a gap of 3, a hole takes three unused accounts in a row, and names the last of them`() {
        assertNull(accountToUseFirst(summaries(true, false, false, true), 3))
        val hole = summaries(true, false, false, false, true)
        assertEquals(UnusedAccount(hole[3].account, hidesLater = true), accountToUseFirst(hole, 3))
    }

    @Test
    fun `an index no account has here neither makes nor ends a hole`() {
        assertNull(accountToUseFirst(summaries(true, null, true), 1))
        assertNull(accountToUseFirst(summaries(true, false, null, false, true), 2), "the missing index breaks the run")
    }
}
