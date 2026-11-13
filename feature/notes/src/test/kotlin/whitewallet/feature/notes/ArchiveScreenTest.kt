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
        compose.onNodeWithText("Its mint couldn't be reached for 7 days, so your wallet stopped trying.").assertExists()
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
}
