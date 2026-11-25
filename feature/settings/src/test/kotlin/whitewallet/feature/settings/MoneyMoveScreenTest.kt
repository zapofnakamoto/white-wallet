package whitewallet.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.wallet.MintMoveResult
import whitewallet.core.wallet.MoneyMoved
import whitewallet.core.wallet.MovePreview
import whitewallet.core.wallet.NoteProblem

@RunWith(RobolectricTestRunner::class)
class MoneyMoveScreenTest {
    @get:Rule val compose = createComposeRule()

    private val preview =
        MovePreview(
            "new.example",
            listOf(
                MovePreview.Line("old.example", 100_000, MovePreview.Quote.Ok(99_000, 1_000)),
                MovePreview.Line("pricey.example", 10_000, MovePreview.Quote.Ok(9_000, 1_000)),
            ),
            feeCapPercent = 2,
        )

    private fun review(collects: Boolean) =
        MoneyMoveState(
            mode = MoneyMoveMode.ChangeHome,
            targets = listOf("new.example"),
            target = "new.example",
            collects = collects,
            preview = preview,
        )

    @Test
    fun withoutCollectingTheUserChoosesWhatMovesOrNothing() {
        val confirmed = mutableListOf<Boolean>()
        compose.setContent {
            var state by remember { mutableStateOf(review(collects = false)) }
            MoneyMoveScreen(
                state,
                {},
                MoneyMoveActions(
                    onToggle = { h -> state = state.copy(excluded = if (h in state.excluded) state.excluded - h else state.excluded + h) },
                    onConfirm = { confirmed += it },
                ),
            )
        }
        compose.onNodeWithText(text(R.string.money_change_and_move, "₿110")).assertExists()
        compose.onNodeWithText(text(R.string.money_review_choose)).performClick()
        compose.onNode(isToggleable() and hasText("₿10 on pricey.example")).performClick()
        compose.onNodeWithText(text(R.string.money_change_and_move, "₿100")).performClick()
        compose.onNode(hasText("Change home mint") and hasClickAction()).performScrollTo().performClick()
        assertEquals(listOf(true, false), confirmed)
    }

    @Test
    fun whileCollectingWhatMovesIsDecidedByTheFeeCap() {
        compose.setContent { MoneyMoveScreen(review(collects = true), {}, MoneyMoveActions()) }
        compose.onNodeWithText(text(R.string.money_review_over_cap, "₿10", "pricey.example", 2)).assertExists()
        compose.onNodeWithText(text(R.string.money_change_and_move, "₿100")).assertExists()
        compose.onNodeWithText(text(R.string.money_review_choose)).assertDoesNotExist()
        compose.onNode(hasText("Change home mint") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun theResultSaysWhatDidNotMoveAndOffersAnotherTry() {
        var retried = false
        val outcome =
            MoneyMoveOutcome(
                homeChanged = true,
                moved =
                    listOf(
                        MoneyMoved("old.example", 100_000, MintMoveResult.Moved(99_000, 1_000)),
                        MoneyMoved("same.example", 3_000_000, MintMoveResult.Failed(NoteProblem.PaymentFailed)),
                    ),
            )
        compose.setContent {
            MoneyMoveScreen(MoneyMoveState(target = "new.example", outcome = outcome), {}, MoneyMoveActions(onRetry = { retried = true }))
        }
        compose.onNodeWithText(text(R.string.money_home_changed, "new.example")).assertExists()
        compose.onNodeWithText(text(R.string.money_result_moved, "₿99", "old.example", "₿1")).assertExists()
        compose.onNodeWithText(text(R.string.money_result_failed, "₿3,000", "same.example", "the payment failed")).assertExists()
        compose.onNodeWithText(text(DesignR.string.try_again)).performClick()
        assertEquals(true, retried)
    }

    /** The anchors the screen asks the brand for, with their status. */
    private val decorated = mutableListOf<Pair<Anchor, WalletStatus>>()

    private fun showDecorated(state: MoneyMoveState, onDone: () -> Unit = {}) = compose.setContent {
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    decorated += anchor to context.status
                }
            }
        CompositionLocalProvider(LocalBrandDesign provides design) { MoneyMoveScreen(state, {}, MoneyMoveActions(onDone = onDone)) }
    }

    @Test
    fun aNewHomeWithAllItsMoneyEndsOnTheSuccessScreen() {
        var done = 0
        val outcome =
            MoneyMoveOutcome(
                homeChanged = true,
                moved =
                    listOf(
                        MoneyMoved("old.example", 100_000, MintMoveResult.Moved(99_000, 1_000)),
                        MoneyMoved("other.example", 50_000, MintMoveResult.Moved(49_000, 1_000)),
                    ),
            )
        showDecorated(MoneyMoveState(target = "new.example", outcome = outcome), onDone = { done++ })
        assertEquals(listOf(Anchor.HomeChanged to WalletStatus.Done(148_000)), decorated.distinct())
        compose.onNodeWithText(text(R.string.money_home_changed_title)).assertExists()
        compose.onNodeWithText(text(R.string.money_home_changed, "new.example")).assertExists()
        compose.onNodeWithText(text(R.string.money_review_fee_total, "₿2")).assertExists()
        compose.onNodeWithText(text(DesignR.string.try_again)).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.done)).performClick()
        assertEquals(1, done)
    }

    @Test
    fun aNewHomeWithoutMoneySaysTheMoneyStayed() {
        showDecorated(MoneyMoveState(target = "new.example", outcome = MoneyMoveOutcome(homeChanged = true, moved = emptyList())))
        assertEquals(listOf(Anchor.HomeChanged to WalletStatus.Done(0)), decorated.distinct())
        compose.onNodeWithText(text(R.string.money_result_money_stays)).assertExists()
    }

    @Test
    fun aMoveThatArrivedEndsOnTheSuccessScreen() {
        val outcome = MoneyMoveOutcome(false, listOf(MoneyMoved("old.example", 100_000, MintMoveResult.Moved(99_000, 1_000))))
        showDecorated(MoneyMoveState(mode = MoneyMoveMode.Move, from = "old.example", target = "new.example", outcome = outcome))
        assertEquals(listOf(Anchor.MoneyMoved to WalletStatus.Done(99_000)), decorated.distinct())
        compose.onNodeWithText(text(R.string.money_moved_title)).assertExists()
        compose.onNodeWithText(text(R.string.money_review_to, "new.example")).assertExists()
    }

    @Test
    fun aMoveStillOnItsWayIsNoSuccess() {
        val outcome = MoneyMoveOutcome(false, listOf(MoneyMoved("old.example", 100_000, MintMoveResult.Pending)))
        showDecorated(MoneyMoveState(mode = MoneyMoveMode.Move, from = "old.example", target = "new.example", outcome = outcome))
        assertEquals(listOf(Anchor.MoveResult to WalletStatus.None), decorated.distinct())
        compose.onNodeWithText(text(DesignR.string.no_answer_title)).assertExists()
        compose.onNodeWithText(text(R.string.money_result_pending, "₿100", "old.example")).assertExists()
    }

    @Test
    fun aMoveThatLeftSomeBehindEndsOnTheResultScreenWithAnotherTry() {
        val outcome =
            MoneyMoveOutcome(
                false,
                listOf(
                    MoneyMoved("old.example", 100_000, MintMoveResult.Moved(99_000, 1_000)),
                    MoneyMoved("same.example", 3_000_000, MintMoveResult.Failed(NoteProblem.Unreachable)),
                ),
            )
        showDecorated(MoneyMoveState(mode = MoneyMoveMode.MoveHome, target = "new.example", outcome = outcome))
        assertEquals(listOf(Anchor.MoveResult to WalletStatus.Done(99_000)), decorated.distinct())
        compose.onNodeWithText(text(R.string.money_moved_title)).assertExists()
        compose.onNodeWithText(text(DesignR.string.try_again)).assertExists()
    }

    @Test
    fun aMoveOfWhichNothingMovedSaysSo() {
        val outcome = MoneyMoveOutcome(false, listOf(MoneyMoved("old.example", 100_000, MintMoveResult.Failed(NoteProblem.PaymentFailed))))
        showDecorated(MoneyMoveState(mode = MoneyMoveMode.Move, from = "old.example", target = "new.example", outcome = outcome))
        assertEquals(listOf(Anchor.MoveResult to WalletStatus.Problem(ProblemKind.Other)), decorated.distinct())
        compose.onNodeWithText(text(R.string.money_nothing_moved_title)).assertExists()
        compose.onNodeWithText(text(R.string.money_result_failed, "₿100", "old.example", "the payment failed")).assertExists()
    }

    @Test
    fun onlyKnownMintsCanBePicked() {
        compose.setContent { MoneyMoveScreen(MoneyMoveState(mode = MoneyMoveMode.Move, from = "old.example"), {}, MoneyMoveActions()) }
        compose.onNodeWithText(text(R.string.money_move_no_mints)).assertExists()
    }

    @Test
    fun withoutCustomMintsMoneyOnlyGoesToSuggestedMints() {
        val known = listOf("home.example", "mint2.example", "learned.example")
        val allowed = { host: String -> host != "learned.example" }
        assertEquals(listOf("mint2.example"), moveTargets(MoneyMoveMode.ChangeHome, known, "home.example", null, false, allowed))
        assertEquals(
            "away from a learned mint, home always counts",
            listOf("home.example", "mint2.example"),
            moveTargets(MoneyMoveMode.Move, known, "home.example", "learned.example", false, allowed),
        )
        assertEquals(
            listOf("mint2.example", "learned.example"),
            moveTargets(MoneyMoveMode.ChangeHome, known, "home.example", null, false) { true },
        )
    }

    @Test
    fun movingAllMoneyHomeLetsTheUserChooseUnlessCollecting() {
        val state =
            MoneyMoveState(mode = MoneyMoveMode.MoveHome, targets = listOf("new.example"), target = "new.example", preview = preview)
        assertEquals(true, state.canChoose)
        assertEquals(110_000L, state.chosenMsat)
        assertEquals(false, state.copy(collects = true).canChoose)
        assertEquals("asked for, so nothing is left out for the cap", 110_000L, state.copy(collects = true).chosenMsat)
        compose.setContent { MoneyMoveScreen(state, {}, MoneyMoveActions()) }
        compose.onNodeWithText(text(R.string.money_move_amount, "₿110")).assertExists()
    }

    @Test
    fun untilTheMintsToPickFromAreKnownOnlyTheTitleShows() {
        compose.setContent { MoneyMoveScreen(MoneyMoveState(mode = MoneyMoveMode.Move, loaded = false), {}, MoneyMoveActions()) }
        compose.onNodeWithText(text(R.string.money_move_title)).assertExists()
        compose.onNodeWithText(text(R.string.money_move_no_mints)).assertDoesNotExist()
    }

    @Test
    fun withNoOtherMintChangingHomeOffersAddingOne() {
        var adding = 0
        compose.setContent {
            MoneyMoveScreen(
                MoneyMoveState(mode = MoneyMoveMode.ChangeHome, canAddMint = true),
                {},
                MoneyMoveActions(onAddMint = { adding++ }),
            )
        }
        compose.onNodeWithText(text(R.string.money_change_no_mints_add)).assertExists()
        compose.onNodeWithText(text(R.string.mints_add)).performClick()
        assertEquals(1, adding)
    }

    @Test
    fun whenNoMintCanBeAddedTheEmptyPageOnlySaysSo() {
        compose.setContent { MoneyMoveScreen(MoneyMoveState(mode = MoneyMoveMode.ChangeHome), {}, MoneyMoveActions()) }
        compose.onNodeWithText(text(R.string.money_change_no_mints)).assertExists()
        compose.onNodeWithText(text(R.string.mints_add)).assertDoesNotExist()
    }

    @Test
    fun theTargetIsPickedWithTheMintPickerSayingWhatEachHolds() {
        val picked = mutableListOf<String>()
        val state =
            MoneyMoveState(
                mode = MoneyMoveMode.ChangeHome,
                targets = listOf("a.example", "b.example"),
                target = "a.example",
                held = mapOf("b.example" to 10_000),
                silent = setOf("a.example"),
            )
        compose.setContent { MoneyMoveScreen(state, {}, MoneyMoveActions(onTarget = { picked += it })) }
        compose.onNodeWithText(text(R.string.money_review_to, "a.example")).assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_choice_change)).performClick()
        compose.onNodeWithText(text(DesignR.string.mint_not_answering_short)).assertExists()
        compose.onNodeWithText(text(R.string.money_move_target_holds, "₿10")).assertExists()
        compose.onNodeWithText("b.example").performClick()
        assertEquals(listOf("b.example"), picked)
    }

    @Test
    fun aTargetWhoseKeyChangedCannotBeContinuedWith() {
        val state = MoneyMoveState(targets = listOf("a.example"), target = "a.example", keyChanged = setOf("a.example"))
        assertEquals(false, state.canContinue)
        assertEquals(true, state.copy(keyChanged = emptySet()).canContinue)
    }
}
