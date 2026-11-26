package whitewallet.feature.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState

// Native graphics: lines break and are cut as on a phone.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PileLabelsTest {
    @get:Rule val compose = createComposeRule()

    private fun note(id: String, host: String, sats: Long, state: NoteState = NoteState.Outstanding) =
        Note(id, "a", host, NoteKind.KeyPath, 0, id.hashCode().toLong() and 0xff, sats * 1000, "cs1", state, 0)

    private fun labels(text: String): TextLayoutResult {
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(
            layout
        )
        return layout.single()
    }

    @Test
    fun whyNoneCountsIsNotCutOnAPhoneWideCard() {
        val mints =
            listOf(
                MintNotes(
                    "home.example",
                    true,
                    listOf(
                        note("u1", "home.example", 5_000, NoteState.Unknown),
                        note("u2", "home.example", 1_000, NoteState.Unknown),
                        note("a1", "home.example", 2_000, NoteState.Ambiguous),
                    ),
                    trusted = true,
                ),
                MintNotes("other.example", false, listOf(note("o1", "other.example", 2_000)), trusted = true),
            )
        compose.setContent { Box(Modifier.width(360.dp)) { NotesContent(NotesState(loaded = true, mints = mints), NotesActions()) } }
        val why = labels("2 notes unknown to the mint · 1 more · Home mint · Trusted")
        assertFalse(why.hasVisualOverflow)
        // The other mint's card gives its labels as many lines, so the cards stay as high.
        assertEquals(2, labels("Trusted · 1 note").layoutInput.maxLines)
    }

    @Test
    fun withEveryMintInTheBalanceTheLabelsTakeOneLine() {
        val mints = listOf(MintNotes("home.example", true, listOf(note("h1", "home.example", 5_000)), trusted = true))
        compose.setContent { Box(Modifier.width(360.dp)) { NotesContent(NotesState(loaded = true, mints = mints), NotesActions()) } }
        assertEquals(1, labels("Home mint · Trusted · 1 note").layoutInput.maxLines)
    }
}
