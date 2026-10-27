package whitewallet.core.data

import androidx.room.Room
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.core.model.OutputRef
import whitewallet.core.model.Profile
import whitewallet.core.wallet.KeyId
import whitewallet.core.wallet.KeyProbe

/**
 * What the wallet stores comes back as it was written: the journal above all, which settles every operation whose
 * outcome is unknown, after a restart too. The wallet's own tests use an in-memory store; this is the one the app uses.
 */
@RunWith(RobolectricTestRunner::class)
class RoomWalletStoreTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WalletDatabase::class.java)
        .allowMainThreadQueries().build()
    private val store = RoomWalletStore(db)

    @AfterTest
    fun close() = db.close()

    private val q1 = "a".repeat(64)
    private val q2 = "b".repeat(64)

    @Test
    fun `a journal entry comes back as written, with its inputs, spends and outputs, ours and someone else's`() = runBlocking<Unit> {
        val entry = JournalEntry("j1", "acc", OperationType.Split, "mint.example", "https://mint.example/w", listOf(q1, q2), listOf("k1a", "k1b"),
            listOf(OutputRef("acc", 0, 7, q1), OutputRef.external(q2)), "https://mint.example/w/cb?k1=x&p1=y", JournalStatus.Unknown,
            "connection dropped", 1, 2)
        store.putJournal(entry)
        assertEquals(listOf(entry), store.journal(setOf(JournalStatus.Unknown)))
        assertEquals(true, store.journal(setOf(JournalStatus.Unknown)).single().outputs[1].isExternal)

        val mint = JournalEntry("j2", "acc", OperationType.Mint, "mint.example", "https://mint.example/w", emptyList(), emptyList(),
            listOf(OutputRef("acc", 0, 8, q2)), "lnbcrt1…", JournalStatus.Prepared, null, 3, 3)
        store.putJournal(mint)
        assertEquals(listOf(mint), store.journal(setOf(JournalStatus.Prepared)))
        store.putJournal(entry.copy(status = JournalStatus.Confirmed))
        assertEquals(listOf("j2"), store.journal(setOf(JournalStatus.Prepared, JournalStatus.Unknown)).map { it.id })
    }

    @Test
    fun `notes keep their state, and only outstanding ones count in the balance`() = runBlocking<Unit> {
        store.saveAccount(Account("acc", "Main", 1, "mint.example"))
        val notes = listOf(
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
    fun `key indexes are handed out once, and key checks of every kind come back`() = runBlocking<Unit> {
        assertEquals(listOf(0L, 1L, 2L), List(3) { store.reserveIndex("acc", "mint.example", 0) })
        assertEquals(3, store.currentIndexOrNull("acc", "mint.example", 0))
        assertNull(store.currentIndexOrNull("acc", "mint.example", 1))
        val probes = listOf(KeyProbe.Unused(1), KeyProbe.Outstanding(1_000, true, false, 2), KeyProbe.Spent(3), KeyProbe.Refused("pending", 4))
        probes.forEachIndexed { i, p -> store.putKeyCheck(KeyId("acc", "mint.example", 2, i.toLong()), p) }
        assertEquals(probes, store.keyChecks("acc").first().toSortedMap(compareBy { it.index }).values.toList())
    }

    @Test
    fun `history rows linked to an operation are settled with it`() = runBlocking<Unit> {
        val row = Activity("pay:j1", "acc", ActivityKind.Sent, 1_000, 10, "mint.example", "bob@mint.example", null, null, false, ActivityStatus.Pending, "j1", 1)
        store.putActivity(row)
        store.settleActivities("j1", ActivityStatus.Done)
        assertEquals(listOf(row.copy(status = ActivityStatus.Done)), store.activities("acc").first())
    }

    @Test
    fun `deleting an account leaves nothing of it, and other accounts untouched`() = runBlocking<Unit> {
        for (id in listOf("a", "b")) {
            store.saveAccount(Account(id, id, 1, "mint.example"))
            store.saveProfile(id, Profile("name-$id", "mint.example"))
            store.upsertNotes(listOf(Note(id.repeat(64), id, "mint.example", NoteKind.KeyPath, 0, 0, 1_000, null, NoteState.Outstanding, 1)))
            store.reserveIndex(id, "mint.example", 0)
            store.putJournal(JournalEntry("j$id", id, OperationType.Rotate, "mint.example", "w", emptyList(), emptyList(), emptyList(), "u", JournalStatus.Unknown, null, 1, 1))
        }
        store.setActiveAccount("a")
        store.deleteAccount("a")
        assertNull(store.currentAccountId())
        assertEquals(listOf("b"), store.accounts.first().map { it.account.id })
        assertEquals(emptyList(), store.notes("a").first())
        assertNull(store.currentProfile("a"))
        assertNull(store.currentIndexOrNull("a", "mint.example", 0))
        assertEquals(listOf("jb"), store.journal(setOf(JournalStatus.Unknown)).map { it.id })
        assertEquals(1, store.notes("b").first().size)
    }
}
