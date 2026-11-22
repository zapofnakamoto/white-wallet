package whitewallet.feature.notes

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.model.HandedOut
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.UnclaimedReason

@RunWith(RobolectricTestRunner::class)
class ArchiveScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun archived(id: Char, reason: UnclaimedReason, failingFor: Long? = 7 * DAY + 15 * 60_000L) =
        ReceivedNote(
            id.toString().repeat(64),
            "a",
            "mint.example",
            "w",
            "k1",
            null,
            null,
            2_000_000,
            null,
            null,
            ReceivedState.Archived,
            null,
            receivedAtMillis = 1,
            updatedAtMillis = 10 * DAY,
            failingSinceMillis = failingFor?.let { 10 * DAY - it },
            unclaimedReason = reason,
        )

    @Test
    fun aTakeBackTheMintRefusedSaysSoInItsOwnWords() {
        compose.setContent {
            ArchiveScreen(ArchiveState(outcome = RetryOutcome.NoteTakeBackRefused("Rate limited")), ArchiveActions(), onBack = {})
        }
        compose.onNodeWithText(plural(R.plurals.notes_checked_take_back_refused, 1, 1), substring = true).assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_message, "Rate limited"), substring = true).assertExists()
    }

    @Test
    fun eachArchivedLinkSaysWhyTheWalletStoppedTrying() {
        val notes =
            listOf(
                archived('a', UnclaimedReason.Refused),
                archived('b', UnclaimedReason.Unreachable),
                archived('c', UnclaimedReason.Refused, failingFor = 2 * 60 * 60_000L),
                archived('d', UnclaimedReason.ByUser, failingFor = null),
            )
        compose.setContent { ArchiveScreen(ArchiveState(notes = notes), ArchiveActions(), onBack = {}) }
        compose.onNodeWithText(text(R.string.archive_hint)).assertExists()
        compose.onNodeWithText("Its mint kept refusing it for 7 days, so your wallet stopped trying.").assertExists()
        compose.onNodeWithText(plural(DesignR.plurals.received_unreachable_days, 7, 7)).assertExists()
        compose.onNodeWithText("Its mint kept refusing it for 2 hours, so your wallet stopped trying.").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("You archived it."))
        compose.onNodeWithText("You archived it.").assertExists()
    }

    @Test
    fun anArchivedLinkCanBeTriedAgain() {
        var retried: String? = null
        val note = archived('a', UnclaimedReason.Unreachable)
        compose.setContent { ArchiveScreen(ArchiveState(notes = listOf(note)), ArchiveActions(retry = { retried = it }), onBack = {}) }
        compose.onNodeWithText(text(R.string.archive_retry)).performClick()
        assertEquals(note.id, retried)
    }

    @Test
    fun removingForGoodIsAskedFirstAndSaysTheRecoveryPhraseCantBringItBack() {
        var asked: String? = null
        var removed = false
        val note = archived('a', UnclaimedReason.Refused)
        compose.setContent {
            ArchiveScreen(
                ArchiveState(notes = listOf(note), removing = note.id),
                ArchiveActions(askRemove = { asked = it }, remove = { removed = true }),
                onBack = {},
            )
        }
        compose.onNodeWithText(text(R.string.archive_remove_title)).assertExists()
        compose.onNodeWithText(text(R.string.archive_remove_body)).assertExists()
        compose.onNodeWithText(text(R.string.archive_remove_confirm)).performClick()
        assertTrue(removed)
        assertEquals(null, asked)
    }

    @Test
    fun anEmptyArchiveSaysSo() {
        compose.setContent { ArchiveScreen(ArchiveState(notes = emptyList()), ArchiveActions(), onBack = {}) }
        compose.onNodeWithText(text(R.string.archive_empty)).assertExists()
    }

    @Test
    fun untilTheNotesAreReadNothingIsDrawn() {
        compose.setContent { ArchiveScreen(ArchiveState(loaded = false), ArchiveActions(), onBack = {}) }
        compose.onNodeWithText(text(R.string.archive_empty)).assertDoesNotExist()
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }

    private fun unknown(id: Char) =
        Note(
            id.toString().repeat(64),
            "a",
            "mint.example",
            NoteKind.KeyPath,
            0,
            7,
            5_000_000,
            null,
            NoteState.Unknown,
            1,
            archivedAtMillis = 2,
        )

    @Test
    fun archivedNotesSayTheirMintDoesNotKnowThemAndCanBeRechecked() {
        var rechecked: String? = null
        val note = unknown('u')
        compose.setContent {
            ArchiveScreen(ArchiveState(unknownNotes = listOf(note)), ArchiveActions(recheck = { rechecked = it }), onBack = {})
        }
        compose.onNodeWithText(text(R.string.archive_notes_hint)).assertExists()
        compose.onNodeWithText(text(R.string.archive_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.archive_notes_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.notes_state_unknown)).assertExists()
        compose.onNodeWithText(text(R.string.notes_recheck)).performClick()
        assertEquals(note.id, rechecked)
    }

    @Test
    fun withLinksAndNotesEachPartHasItsTitle() {
        compose.setContent {
            ArchiveScreen(
                ArchiveState(notes = listOf(archived('a', UnclaimedReason.ByUser, null)), unknownNotes = listOf(unknown('u'))),
                ArchiveActions(),
                onBack = {},
            )
        }
        compose.onNodeWithText(text(R.string.archive_links_title)).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.archive_notes_title)))
        compose.onNodeWithText(text(R.string.archive_notes_title)).assertExists()
    }

    @Test
    fun aHandedOutNoteIsInAPartOfItsOwnThatSaysItWaitsToBeClaimed() {
        compose.setContent {
            ArchiveScreen(
                ArchiveState(unknownNotes = listOf(unknown('h').copy(handedOut = HandedOut.Waiting))),
                ArchiveActions(),
                onBack = {},
            )
        }
        compose.onNodeWithText(text(R.string.archive_handed_out_hint)).assertExists()
        compose.onNodeWithText(text(R.string.archive_notes_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.archive_handed_out_title)).assertDoesNotExist()
    }

    @Test
    fun heldAndHandedOutNotesEachHaveTheirTitledPart() {
        compose.setContent {
            ArchiveScreen(
                ArchiveState(unknownNotes = listOf(unknown('u'), unknown('h').copy(handedOut = HandedOut.TakeBack))),
                ArchiveActions(),
                onBack = {},
            )
        }
        compose.onNodeWithText(text(R.string.archive_notes_title)).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.archive_handed_out_title)))
        compose.onNodeWithText(text(R.string.archive_handed_out_title)).assertExists()
        compose.onNodeWithText(text(R.string.archive_handed_out_hint)).assertExists()
    }

    @Test
    fun removingAnArchivedNoteIsAskedFirstAndSaysAScanFindsIt() {
        var removed = false
        val note = unknown('u')
        compose.setContent {
            ArchiveScreen(
                ArchiveState(unknownNotes = listOf(note), removingNote = note.id),
                ArchiveActions(removeNote = { removed = true }),
                onBack = {},
            )
        }
        compose.onNodeWithText(text(R.string.archive_note_remove_body)).assertExists()
        compose.onNodeWithText(text(R.string.archive_remove_confirm)).performClick()
        assertTrue(removed)
    }

    @Test
    fun anArchivedNoteBackInTheBalanceIsTold() {
        compose.setContent { ArchiveScreen(ArchiveState(outcome = RetryOutcome.NoteBack(5_000_000)), ArchiveActions(), onBack = {}) }
        compose.onNodeWithText(text(R.string.archive_note_back, "₿5,000")).assertExists()
    }
}
