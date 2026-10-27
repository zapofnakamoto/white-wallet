package whitewallet.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.core.wallet.MoneyMoved
import whitewallet.core.wallet.MovePreview
import whitewallet.core.wallet.NoteProblem
import whitewallet.core.wallet.ServiceMoveResult

@RunWith(RobolectricTestRunner::class)
class MoneyMoveScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val preview = MovePreview(
        "new.example",
        listOf(
            MovePreview.Line("old.example", 100_000, MovePreview.Quote.Ok(99_000, 1_000)),
            MovePreview.Line("pricey.example", 10_000, MovePreview.Quote.Ok(9_000, 1_000)),
        ),
        feeCapPercent = 2,
    )

    private fun review(collects: Boolean) = MoneyMoveState(
        mode = MoneyMoveMode.ChangeHome, targets = listOf("new.example"), target = "new.example", collects = collects, preview = preview,
    )

    @Test
    fun withoutCollectingTheUserChoosesWhatMovesOrNothing() {
        val confirmed = mutableListOf<Boolean>()
        compose.setContent {
            var state by remember { mutableStateOf(review(collects = false)) }
            MoneyMoveScreen(state, {}, MoneyMoveActions(
                onToggle = { h -> state = state.copy(excluded = if (h in state.excluded) state.excluded - h else state.excluded + h) },
                onConfirm = { confirmed += it },
            ))
        }
        compose.onNodeWithText("Change and move ₿110").assertExists()
        compose.onNodeWithText("Choose what moves").performClick()
        compose.onNode(isToggleable() and hasText("₿10 on pricey.example")).performClick()
        compose.onNodeWithText("Change and move ₿100").performClick()
        compose.onNode(hasText("Change home mint") and hasClickAction()).performScrollTo().performClick()
        assertEquals(listOf(true, false), confirmed)
    }

    @Test
    fun whileCollectingWhatMovesIsDecidedByTheFeeCap() {
        compose.setContent { MoneyMoveScreen(review(collects = true), {}, MoneyMoveActions()) }
        compose.onNodeWithText("₿10 on pricey.example stays: moving it costs more than 2 %").assertExists()
        compose.onNodeWithText("Change and move ₿100").assertExists()
        compose.onNodeWithText("Choose what moves").assertDoesNotExist()
        compose.onNode(hasText("Change home mint") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun theResultSaysWhatDidNotMoveAndOffersAnotherTry() {
        var retried = false
        val outcome = MoneyMoveOutcome(
            homeChanged = true, address = "alice@new.example",
            moved = listOf(
                MoneyMoved("old.example", 100_000, ServiceMoveResult.Moved(99_000, 1_000)),
                MoneyMoved("same.example", 3_000_000, ServiceMoveResult.Failed(NoteProblem.PaymentFailed)),
            ),
        )
        compose.setContent {
            MoneyMoveScreen(MoneyMoveState(target = "new.example", outcome = outcome), {}, MoneyMoveActions(onRetry = { retried = true }))
        }
        compose.onNodeWithText("New home: new.example").assertExists()
        compose.onNodeWithText("₿99 arrived from old.example (fee ₿1)").assertExists()
        compose.onNodeWithText("₿3,000 on same.example didn't move (the payment failed). It's still there.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(true, retried)
    }

    @Test
    fun onlyKnownMintsCanBePicked() {
        compose.setContent { MoneyMoveScreen(MoneyMoveState(mode = MoneyMoveMode.Move, from = "old.example"), {}, MoneyMoveActions()) }
        compose.onNodeWithText("There is nowhere else to move it. Add one under Settings first.").assertExists()
    }
}
