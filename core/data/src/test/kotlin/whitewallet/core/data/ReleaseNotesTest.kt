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
    fun `a fresh install sees only the important notes`() = runBlocking {
        assertNull(preferences.seenReleaseNotes.first())
        assertEquals(listOf(important), ReleaseNoteTracker(notes, preferences, freshInstall = true).unseen())
        assertEquals(setOf("faster"), preferences.seenReleaseNotes.first())
    }

    @Test
    fun `an update from a version without release notes sees every note`() = runBlocking {
        assertEquals(notes, ReleaseNoteTracker(notes, preferences, freshInstall = false).unseen())
        assertEquals(emptySet(), preferences.seenReleaseNotes.first())
    }

    @Test
    fun `seen notes stay hidden and newer ones show`() = runBlocking {
        val tracker = ReleaseNoteTracker(notes, preferences, freshInstall = false)
        tracker.markSeen(tracker.unseen())
        assertEquals(emptyList(), tracker.unseen())

        val newer = ReleaseNote("newer", 5, 6)
        // The next update: a fresh install no longer, whatever the package says, since ids are stored already.
        assertEquals(listOf(newer), ReleaseNoteTracker(listOf(newer) + notes, preferences, freshInstall = true).unseen())
    }

    @Test
    fun `a fresh install that saw nothing still sees the next update's notes`() = runBlocking {
        ReleaseNoteTracker(emptyList(), preferences, freshInstall = true).unseen()
        assertEquals(listOf(ordinary), ReleaseNoteTracker(listOf(ordinary), preferences, freshInstall = false).unseen())
    }

    @Test
    fun `important notes come back until got it`() = runBlocking {
        val tracker = ReleaseNoteTracker(notes, preferences, freshInstall = false)
        tracker.markDismissed(tracker.unseen())
        assertEquals(listOf(important), tracker.unseen())
        tracker.markDismissed(tracker.unseen())
        assertEquals(listOf(important), tracker.unseen())
        tracker.markSeen(tracker.unseen())
        assertEquals(emptyList(), tracker.unseen())
    }

    @Test
    fun `marking seen keeps what was seen before`() = runBlocking {
        val tracker = ReleaseNoteTracker(notes, preferences, freshInstall = false)
        tracker.markSeen(listOf(ordinary))
        tracker.markSeen(listOf(important))
        assertEquals(setOf("faster", "act-now"), preferences.seenReleaseNotes.first())
    }
}
