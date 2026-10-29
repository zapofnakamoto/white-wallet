package whitewallet.feature.notes

import org.junit.Assert.assertEquals
import org.junit.Test
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState

class NoteSortTest {
    private fun note(id: String, sats: Long, at: Long, state: NoteState = NoteState.Shared) =
        Note(id, "a", "home.example", NoteKind.KeyPath, 0, 0, sats * 1000, "cs1", state, at)

    private val notes = listOf(note("small-new", 10, at = 3), note("big-old", 500, at = 1), note("mid", 100, at = 2), note("held", 900, at = 4, NoteState.Outstanding))

    @Test
    fun handedOutNotesFollowTheSortTheUserChose() {
        assertEquals(listOf("big-old", "mid", "small-new"), sharedNotes(notes, NoteSort.Largest).map { it.id })
        assertEquals(listOf("small-new", "mid", "big-old"), sharedNotes(notes, NoteSort.Newest).map { it.id })
    }
}
