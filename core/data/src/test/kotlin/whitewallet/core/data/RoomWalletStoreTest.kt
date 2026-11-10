package whitewallet.core.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import whitewallet.core.model.Account
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.KeyScheme
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.core.model.OutputRef
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.WalletRecord
import whitewallet.core.wallet.CreateAccountResult
import whitewallet.core.wallet.KeyId
import whitewallet.core.wallet.KeyProbe
import whitewallet.core.wallet.KeySet
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.NotePolicies
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * What the wallet stores comes back as it was written: the journal above all, which settles every operation whose outcome is unknown, after
 * a restart too. The wallet's own tests use an in-memory store; this is the one the app uses.
 */
@RunWith(RobolectricTestRunner::class)
class RoomWalletStoreTest {
    private val db =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WalletDatabase::class.java).allowMainThreadQueries().build()
    private val store = RoomWalletStore(db)

    @AfterTest fun close() = db.close()

    private val q1 = "a".repeat(64)
    private val q2 = "b".repeat(64)

    /** Saves account [id] (index [index] of wallet [walletId], saved first if new): its rows need it, as the foreign keys say. */
    private suspend fun account(id: String, walletId: String = id, index: Long = 0, home: String? = "mint.example") {
        if (store.wallet(walletId) == null) store.saveWallet(WalletRecord(walletId, null, 1))
        store.saveAccount(Account(id, id, 1, walletId, index, home))
    }

    @Test
    fun `a journal entry comes back as written, with its inputs, spends and outputs, ours and someone else's`() =
        runBlocking<Unit> {
            account("acc")
            val entry =
                JournalEntry(
                    "j1",
                    "acc",
                    OperationType.Split,
                    "mint.example",
                    "https://mint.example/w",
                    listOf(q1, q2),
                    listOf("k1a", "k1b"),
                    listOf(OutputRef("acc", 0, 7, q1), OutputRef.external(q2)),
                    "https://mint.example/w/cb?k1=x&p1=y",
                    JournalStatus.Unknown,
                    "connection dropped",
                    1,
                    2,
                )
            store.putJournal(entry)
            assertEquals(listOf(entry), store.journal(setOf(JournalStatus.Unknown)))
            assertEquals(true, store.journal(setOf(JournalStatus.Unknown)).single().outputs[1].isExternal)

            val mint =
                JournalEntry(
                    "j2",
                    "acc",
                    OperationType.Mint,
                    "mint.example",
                    "https://mint.example/w",
                    emptyList(),
                    emptyList(),
                    listOf(OutputRef("acc", 0, 8, q2)),
                    "lnbcrt1…",
                    JournalStatus.Prepared,
                    null,
                    3,
                    3,
                )
            store.putJournal(mint)
            assertEquals(listOf(mint), store.journal(setOf(JournalStatus.Prepared)))
            store.putJournal(entry.copy(status = JournalStatus.Confirmed))
            assertEquals(listOf("j2"), store.journal(setOf(JournalStatus.Prepared, JournalStatus.Unknown)).map { it.id })
        }

    @Test
    fun `notes keep their state, and only outstanding ones count in the balance`() =
        runBlocking<Unit> {
            account("acc")
            val notes =
                listOf(
                    Note(q1, "acc", "mint.example", NoteKind.KeyPath, 2, 3, 1_000, "cs1…", NoteState.Outstanding, 1),
                    Note(q2, "acc", "mint.example", NoteKind.KeyPath, 0, 4, 5_000, null, NoteState.Ambiguous, 1),
                )
            store.upsertNotes(notes)
            assertEquals(notes.toSet(), store.notes("acc").first().toSet())
            assertEquals(1_000, store.accounts.first().single().balanceMsat)
            store.upsertNotes(listOf(notes[1].copy(state = NoteState.Outstanding)))
            assertEquals(6_000, store.accounts.first().single().balanceMsat)
        }

    @Test
    fun `key indexes are handed out once, and key checks of every kind come back`() =
        runBlocking<Unit> {
            account("acc")
            assertEquals(listOf(0L, 1L, 2L), List(3) { store.reserveIndex("acc", "mint.example", 0) })
            assertEquals(3, store.currentIndexOrNull("acc", "mint.example", 0))
            assertNull(store.currentIndexOrNull("acc", "mint.example", 1))
            val probes =
                listOf(KeyProbe.Unused(1), KeyProbe.Outstanding(1_000, true, false, 2), KeyProbe.Spent(3), KeyProbe.Refused("pending", 4))
            probes.forEachIndexed { i, p -> store.putKeyCheck(KeyId("acc", "mint.example", 2, i.toLong(), KeyScheme.Bip32, 0), p) }
            assertEquals(probes, store.keyChecks("acc").first().toSortedMap(compareBy { it.index }).values.toList())
        }

    @Test
    fun `the indexes of a mint show each counter under its own keys, and leave out how many shares went out`() =
        runBlocking<Unit> {
            account("acc")
            store.setKeyIndex("acc", "mint.example", KeyCounter.Wallet, 4)
            store.setKeyIndex("acc", "mint.example", KeyCounter.Change, 7)
            store.setKeyIndex("acc", "mint.example", KeyCounter.Shares, 2)
            assertEquals(mapOf(KeySet.Wallet to 4L, KeySet.Change to 7L), store.indexesFlow("acc", "mint.example").first())
        }

    @Test
    fun `history rows linked to an operation are settled with it`() =
        runBlocking<Unit> {
            account("acc")
            val row =
                Activity(
                    "pay:j1",
                    "acc",
                    ActivityKind.Sent,
                    1_000,
                    10,
                    "mint.example",
                    "bob@mint.example",
                    null,
                    null,
                    false,
                    ActivityStatus.Pending,
                    "j1",
                    1,
                )
            store.putActivity(row)
            store.settleActivities("j1", ActivityStatus.Done)
            assertEquals(listOf(row.copy(status = ActivityStatus.Done)), store.activities("acc").first())
        }

    @Test
    fun `the account list says which wallet each account is in, and at which index`() =
        runBlocking<Unit> {
            account("w")
            account("s", walletId = "w", index = 1)
            account("o", home = null)
            assertEquals(
                listOf(Triple("w", "w", 0L), Triple("s", "w", 1L), Triple("o", "o", 0L)),
                store.accounts.first().map { Triple(it.account.id, it.account.walletId, it.account.index) },
            )
        }

    @Test
    fun `a wallet of two accounts counts once, and deleting it takes both accounts`() =
        runBlocking<Unit> {
            val wallet =
                Wallet(
                    store,
                    TestSeeds(),
                    LnurlcashClient(),
                    policies = NotePolicies.InMemory(ManualStrategy.ID),
                    maxWallets = 2,
                )
            assertIs<CreateAccountResult.Created>(wallet.createWallet("Main"))
            assertIs<CreateAccountResult.Created>(wallet.createAccount("Savings"))
            assertEquals(listOf(0L, 1L), wallet.activeWalletAccounts.first().map { it.account.index })
            assertTrue(wallet.canAddWallet.first(), "one wallet of two, of two allowed")

            assertFalse(wallet.deleteActiveAccount(), "nothing left")
            assertEquals(emptyList(), store.accounts.first())
        }

    @Test
    fun `deleting an account leaves nothing of it, and other accounts untouched`() =
        runBlocking<Unit> {
            for (id in listOf("a", "b")) {
                account(id)
                store.saveAddress(id, RegisteredAddress("name-$id", "mint.example", 1))
                store.setPrimaryAddress(id, "name-$id", "mint.example")
                store.upsertNotes(
                    listOf(Note(id.repeat(64), id, "mint.example", NoteKind.KeyPath, 0, 0, 1_000, null, NoteState.Outstanding, 1))
                )
                store.reserveIndex(id, "mint.example", 0)
                store.putJournal(
                    JournalEntry(
                        "j$id",
                        id,
                        OperationType.Rotate,
                        "mint.example",
                        "w",
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        "u",
                        JournalStatus.Unknown,
                        null,
                        1,
                        1,
                    )
                )
                store.putReceivedNote(received("r$id".padEnd(64, '0'), id, ReceivedState.Waiting))
            }
            store.setActiveAccount("a")
            store.deleteAccount("a")
            assertNull(store.currentAccountId())
            assertEquals(listOf("b"), store.accounts.first().map { it.account.id })
            assertEquals(emptyList(), store.notes("a").first())
            assertNull(store.currentPrimaryAddress("a"))
            assertEquals(emptyList(), store.addresses("a").first())
            assertEquals("name-b@mint.example", store.currentPrimaryAddress("b")?.address)
            assertNull(store.wallet("a"), "the wallet goes with its last account")
            assertNull(store.currentIndexOrNull("a", "mint.example", 0))
            assertEquals(listOf("jb"), store.journal(setOf(JournalStatus.Unknown)).map { it.id })
            assertEquals(1, store.notes("b").first().size)
            assertEquals(emptyList(), store.receivedNotes("a").first())
            assertEquals(1, store.receivedNotes("b").first().size)
        }

    @Test
    fun `a received note comes back as written, with its spend, lock and refusals, and is found by its state`() =
        runBlocking<Unit> {
            account("acc")
            val locked =
                received(q1, "acc", ReceivedState.Locked)
                    .copy(lockedUntilMillis = 1_800_000_000_000, lockedForSeconds = 86_528, receivedAtMillis = 2)
            val waiting = received(q2, "acc", ReceivedState.Waiting).copy(refusedSinceMillis = 1_700_000_000_000)
            store.putReceivedNote(locked)
            store.putReceivedNote(waiting)
            assertEquals(locked, store.receivedNote(q1))
            assertEquals(listOf(locked, waiting), store.receivedNotes("acc").first(), "newest first")
            assertEquals(listOf(waiting), store.receivedNotes(setOf(ReceivedState.Waiting)))

            val claimed = waiting.copy(state = ReceivedState.Claimed, k1 = null, amountMsat = 5_000, journalId = "j1")
            store.putReceivedNote(claimed)
            assertEquals(claimed, store.receivedNote(q2))
            assertEquals(listOf(locked), store.receivedNotes(setOf(ReceivedState.Waiting, ReceivedState.Locked)))
            store.deleteReceivedNote(q1)
            assertNull(store.receivedNote(q1))
        }

    @Test
    fun `an account's money and operations keep it, its own rows go with it, and an update keeps everything`() =
        runBlocking<Unit> {
            account("acc")
            store.upsertNotes(listOf(Note(q1, "acc", "mint.example", NoteKind.KeyPath, 0, 0, 1_000, null, NoteState.Outstanding, 1)))
            store.setKeyIndex("acc", "mint.example", KeyCounter.Wallet, 3)
            // Saving the account or its wallet again (a rename, a new home) changes them in place.
            store.saveAccount(Account("acc", "Renamed", 1, homeHost = "other.example"))
            store.saveWallet(WalletRecord("acc", "Renamed too", 1))
            assertEquals(1, store.notes("acc").first().size)
            assertEquals(3, store.keyIndexOrNull("acc", "mint.example", KeyCounter.Wallet))
            assertEquals("Renamed", store.account("acc")?.name)
            assertEquals("other.example", store.account("acc")?.homeHost)
            assertEquals("Renamed too", store.wallet("acc")?.name)
            assertEquals("other.example", store.accounts.first().single().account.homeHost)

            // The database refuses to drop an account that still has notes; deleteAccount deletes them by name first.
            val sql = db.openHelper.writableDatabase
            assertFailsWith<SQLiteConstraintException> { sql.execSQL("DELETE FROM accounts WHERE id = 'acc'") }
            store.deleteAccount("acc")
            assertNull(store.keyIndexOrNull("acc", "mint.example", KeyCounter.Wallet), "its counters went with it")
        }

    @Test
    fun `an account is one index of one wallet, and has at most one primary address`() =
        runBlocking<Unit> {
            account("a")
            assertFailsWith<SQLiteConstraintException> { store.saveAccount(Account("b", "B", 2, "a", 0)) }
            assertFailsWith<SQLiteConstraintException>("no wallet") { store.saveAccount(Account("c", "C", 2, "nowhere", 0)) }

            store.saveAddress("a", RegisteredAddress("alice", "mint.example", 1))
            store.saveAddress("a", RegisteredAddress("bob", "other.example", 2))
            store.setPrimaryAddress("a", "alice", "mint.example")
            store.setPrimaryAddress("a", "bob", "other.example")
            assertEquals("bob@other.example", store.currentPrimaryAddress("a")?.address)
            // Saving it again (its fee paid, its share learned) keeps it primary.
            store.saveAddress("a", RegisteredAddress("bob", "other.example", 2, share = 4))
            assertEquals(RegisteredAddress("bob", "other.example", 2, share = 4), store.currentPrimaryAddress("a"))
            assertFailsWith<IllegalStateException> { store.setPrimaryAddress("a", "carol", "mint.example") }
            store.deleteAddress("a", "bob", "other.example")
            assertNull(store.currentPrimaryAddress("a"), "none is primary once it is gone")
        }

    private fun received(q: String, accountId: String, state: ReceivedState) =
        ReceivedNote(
            q,
            accountId,
            "mint.example",
            "https://mint.example/w",
            "cw1example",
            "cs50n1example",
            5_000,
            null,
            null,
            null,
            state,
            null,
            1,
            1,
        )
}
