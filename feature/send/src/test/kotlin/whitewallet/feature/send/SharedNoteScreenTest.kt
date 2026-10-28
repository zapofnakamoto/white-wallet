package whitewallet.feature.send

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.SharedNote
import whitewallet.core.wallet.SharedNoteState

@RunWith(RobolectricTestRunner::class)
class SharedNoteScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun note(state: SharedNoteState) = SharedNote(
        id = "n1", host = "mint.example", amountMsat = 1_000_000, certified = true, state = state, createdAtMillis = 0,
        link = if (state == SharedNoteState.Waiting) "https://mint.example/note#secret" else null,
    )

    @Test
    fun theBrandSeesTheNoteWaitBeRedeemedOrComeBack() {
        val statuses = mutableListOf<WalletStatus>()
        val design = object : BrandDesign {
            @Composable
            override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                if (anchor == Anchor.NoteShared) {
                    statuses += context.status
                    Text("brand mark")
                }
            }
        }
        var state by mutableStateOf(SharedNoteUiState(note = note(SharedNoteState.Waiting), loading = false))
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design) {
                SharedNoteScreen(state, onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {})
            }
        }
        compose.onNodeWithText("brand mark", useUnmergedTree = true).assertExists()
        assertEquals(WalletStatus.WaitingForRecipient, statuses.last())

        state = state.copy(note = note(SharedNoteState.Redeemed))
        compose.waitForIdle()
        assertEquals(WalletStatus.Sent(1_000_000), statuses.last())

        state = state.copy(note = note(SharedNoteState.Reclaimed))
        compose.waitForIdle()
        assertEquals(WalletStatus.Idle, statuses.last())

        state = state.copy(reclaimPending = true)
        compose.waitForIdle()
        assertEquals(WalletStatus.None, statuses.last())

        state = state.copy(note = note(SharedNoteState.Waiting), reclaimPending = false, problem = PayProblem.Unreachable)
        compose.waitForIdle()
        assertEquals(WalletStatus.Problem(ProblemKind.Offline), statuses.last())
    }

    @Test
    fun withoutABrandDesignNothingIsAdded() {
        compose.setContent {
            SharedNoteScreen(
                SharedNoteUiState(note = note(SharedNoteState.Waiting), loading = false),
                onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {},
            )
        }
        compose.onNodeWithText("₿1,000").assertExists()
        compose.onNodeWithText("brand mark").assertDoesNotExist()
    }
}
