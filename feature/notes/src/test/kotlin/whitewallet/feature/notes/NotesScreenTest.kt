package whitewallet.feature.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.NotCollected

@RunWith(RobolectricTestRunner::class)
class NotesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun note(id: String, host: String, sats: Long, state: NoteState = NoteState.Outstanding, certified: Boolean = true) =
        Note(id, "a", host, NoteKind.KeyPath, 0, id.hashCode().toLong() and 0xff, sats * 1000, if (certified) "cs1" else null, state, 0)

    private val home = listOf(note("h1", "home.example", 30_000), note("h2", "home.example", 15_000))
    private val other = listOf(note("o1", "other.example", 2_000), note("o2", "other.example", 430, certified = false))

    private fun state(vararg changes: (NotesState) -> NotesState): NotesState {
        val base = NotesState(
            loaded = true,
            mints = listOf(MintNotes("home.example", true, home), MintNotes("other.example", false, other)),
            knownHosts = listOf("home.example", "other.example"),
        )
        return changes.fold(base) { s, change -> change(s) }
    }

    @Test
    fun theOverviewShowsTheTotalPerMintAndOnlyUnusualStatus() {
        compose.setContent { NotesContent(state(), NotesActions()) }
        compose.onNodeWithText("₿47,430").assertExists()
        compose.onNodeWithText("4 notes · 2 services").assertExists()
        compose.onNodeWithText("home.example").assertExists()
        compose.onNodeWithText("Home mint").assertExists()
        compose.onNodeWithText("₿45,000").assertExists()
        compose.onNodeWithText("Amount not certified").assertExists()
    }

    @Test
    fun aTrustedMintIsMarkedOnItsGroupAndInTheNote() {
        compose.setContent {
            NotesContent(state({ it.copy(mints = listOf(MintNotes("home.example", true, home, trusted = true), MintNotes("other.example", false, other)), open = "h1") }), NotesActions())
        }
        compose.onNodeWithText("Home mint · Trusted").assertExists()
        compose.onNodeWithText("From a service you trust").assertExists()
    }

    @Test
    fun emptyWalletSaysSo() {
        compose.setContent { NotesContent(NotesState(loaded = true), NotesActions()) }
        compose.onNodeWithText("No notes yet. Notes appear here once you receive money.").assertExists()
    }

    @Test
    fun tappingANoteOpensItsActions() {
        var shared: String? = null
        var renewed: String? = null
        compose.setContent { NotesContent(state({ it.copy(open = "h2") }), NotesActions(share = { shared = it }, renew = { renewed = it })) }
        compose.onNodeWithText("On home.example · ", substring = true).assertExists()
        compose.onNodeWithText("Share as QR").performClick()
        compose.onNodeWithText("Renew").performClick()
        assertEquals("h2", shared)
        assertEquals("h2", renewed)
        compose.onNodeWithText("Key 0/", substring = true).assertDoesNotExist()
    }

    @Test
    fun developerModeShowsTheKey() {
        compose.setContent { NotesContent(state({ it.copy(open = "h2", developerMode = true) }), NotesActions()) }
        compose.onNodeWithText("Key 0/", substring = true).assertExists()
    }

    @Test
    fun aNoteBeingPaidCanBeOpenedButNotActedOn() {
        val paying = note("p", "home.example", 1_000, NoteState.Paying)
        compose.setContent {
            NotesContent(state({ it.copy(mints = listOf(MintNotes("home.example", true, listOf(paying))), open = "p") }), NotesActions())
        }
        compose.onNodeWithText("Share as QR").assertIsNotEnabled()
    }

    @Test
    fun longPressStartsSelectingAndTheBarActsOnTheSelection() {
        var pressed: Note? = null
        var combined = false
        var s by mutableStateOf(state())
        compose.setContent { NotesContent(s, NotesActions(longPress = { pressed = it }, combine = { combined = true })) }
        compose.onNodeWithText("₿30,000").performTouchInput { longClick() }
        assertEquals("h1", pressed?.id)

        s = state({ it.copy(selecting = true, selected = setOf("h1")) })
        compose.onNodeWithText("1 note selected · ₿30,000").assertExists()
        compose.onNodeWithText("Combine").assertIsNotEnabled()
        s = state({ it.copy(selecting = true, selected = setOf("h1", "h2")) })
        compose.onNodeWithText("Combine").assertIsEnabled().performClick()
        assertEquals(true, combined)
    }

    @Test
    fun aMintCollectingSkippedOffersMoveAnyway() {
        var moved: String? = null
        val skipped = NotCollected.Reason.FeeTooHigh(feeMsat = 2_000, capPercent = 2)
        compose.setContent {
            NotesContent(
                state({ it.copy(mints = listOf(MintNotes("home.example", true, home), MintNotes("other.example", false, other, skipped))) }),
                NotesActions(moveAnyway = { moved = it }),
            )
        }
        compose.onNodeWithText("Not moved home: the fee of ₿2 is more than 2%.").assertExists()
        compose.onNodeWithText("Move anyway").performClick()
        assertEquals("other.example", moved)
    }

    @Test
    fun sharedNotesWaitInTheirOwnSection() {
        var opened: String? = null
        val shared = note("s", "home.example", 3_333, NoteState.Shared)
        compose.setContent { NotesContent(state({ it.copy(shared = listOf(shared)) }), NotesActions(openShared = { opened = it })) }
        compose.onNodeWithText("₿3,333 shared, not redeemed yet").assertExists()
        // The list is lazy: rows below the screen exist only once scrolled to.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Tap one to show its QR code again or take it back."))
        compose.onNodeWithText("Shared, not redeemed yet").assertExists()
        compose.onNodeWithText("₿3,333").performClick()
        assertEquals("s", opened)
    }

    @Test
    fun checkingReportsNotesThatAreGone() {
        compose.setContent { NotesContent(state({ it.copy(outcome = NotesOutcome.Checked(ok = 3, gone = 1, unreachable = listOf("other.example"))) }), NotesActions()) }
        compose.onNodeWithText(
            "1 note is no longer there: it was spent elsewhere, for example with this recovery phrase on another phone. No answer from other.example. Try again later.",
        ).assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun german() {
        compose.setContent { NotesContent(state({ it.copy(selecting = true, selected = setOf("h1", "h2")) }), NotesActions()) }
        compose.onNodeWithText("4 Scheine · 2 Dienste").assertExists()
        compose.onNodeWithText("Haupt-Mint").assertExists()
        compose.onNodeWithText("2 Scheine ausgewählt · 45.000 ₿").assertExists()
        compose.onNodeWithText("Zusammenlegen").assertExists()
    }
}
