package whitewallet.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.brand.ReleaseNote
import whitewallet.core.wallet.ReleaseNoteTracker

/** Which release notes a user sees, with the seen ids kept in the real `settings` DataStore. */
class ReleaseNotesTest {
    private val file = File.createTempFile("release-notes", ".preferences_pb").apply { delete() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val preferences = DataStorePreferences(PreferenceDataStoreFactory.create(scope = scope) { file })

    private val important = ReleaseNote("act-now", 1, 2, important = true)
    private val ordinary = ReleaseNote("faster", 3, 4)
    private val notes = listOf(important, ordinary)

    @AfterTest
    fun close() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `without a wallet yet only the important notes are seen`() = runBlocking {
        assertNull(preferences.seenReleaseNotes.first())
        assertEquals(listOf(important), ReleaseNoteTracker(notes, preferences, hasWallet = { false }).unseen())
        assertEquals(setOf("faster"), preferences.seenReleaseNotes.first())
    }

    @Test
    fun `a wallet from a version without release notes sees every note`() = runBlocking {
        assertEquals(notes, ReleaseNoteTracker(notes, preferences, hasWallet = { true }).unseen())
        assertEquals(emptySet(), preferences.seenReleaseNotes.first())
    }

    @Test
    fun `what counts as seen is settled at the first start, before onboarding makes a wallet`() = runBlocking {
        var wallet = false
        val tracker = ReleaseNoteTracker(notes, preferences, hasWallet = { wallet })
        tracker.start()
        wallet = true
        assertEquals(listOf(important), tracker.unseen())
    }

    @Test
    fun `seen notes stay hidden and newer ones show`() = runBlocking {
        val tracker = ReleaseNoteTracker(notes, preferences, hasWallet = { true })
        tracker.markSeen(tracker.unseen())
        assertEquals(emptyList(), tracker.unseen())

        val newer = ReleaseNote("newer", 5, 6)
        // The next update: without a wallet or not, the ids are stored already.
        assertEquals(listOf(newer), ReleaseNoteTracker(listOf(newer) + notes, preferences, hasWallet = { false }).unseen())
    }

    @Test
    fun `a new user who saw nothing still sees the next update's notes`() = runBlocking {
        ReleaseNoteTracker(emptyList(), preferences, hasWallet = { false }).unseen()
        assertEquals(listOf(ordinary), ReleaseNoteTracker(listOf(ordinary), preferences, hasWallet = { true }).unseen())
    }

    @Test
    fun `important notes come back until got it`() = runBlocking {
        val tracker = ReleaseNoteTracker(notes, preferences, hasWallet = { true })
        tracker.markDismissed(tracker.unseen())
        assertEquals(listOf(important), tracker.unseen())
        tracker.markDismissed(tracker.unseen())
        assertEquals(listOf(important), tracker.unseen())
        tracker.markSeen(tracker.unseen())
        assertEquals(emptyList(), tracker.unseen())
    }

    @Test
    fun `marking seen keeps what was seen before`() = runBlocking {
        val tracker = ReleaseNoteTracker(notes, preferences, hasWallet = { true })
        tracker.markSeen(listOf(ordinary))
        tracker.markSeen(listOf(important))
        assertEquals(setOf("faster", "act-now"), preferences.seenReleaseNotes.first())
    }
}
