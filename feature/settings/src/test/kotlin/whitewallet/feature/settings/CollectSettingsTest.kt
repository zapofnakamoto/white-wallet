package whitewallet.feature.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.wallet.CollectDefaults
import whitewallet.core.wallet.Collected
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.MovePreview
import whitewallet.core.wallet.NotCollected
import whitewallet.core.wallet.PolicyRun

/** The "collect at home mint" part of the notes page. */
@RunWith(RobolectricTestRunner::class)
class CollectSettingsTest {
    @get:Rule val compose = createComposeRule()

    private val manual = ManualStrategy(title = R.string.settings_strategy_manual, description = R.string.settings_strategy_manual_hint)

    private fun show(state: NotesSettingsState, collect: CollectActions = CollectActions()) = compose.setContent {
        NotesSettingsScreen(state, onBack = {}, onNoteStrategy = {}, onAutoRotate = {}, collect = collect)
    }

    private fun state(collect: Boolean, run: PolicyRun? = null, offered: Boolean = true, defaults: CollectDefaults = CollectDefaults()) =
        NotesSettingsState(
            noteStrategy = manual,
            strategies = listOf(manual),
            collectOffered = offered,
            collectAtHome = collect,
            collectFeeCap = 2,
            collectFeeCaps = defaults.feeCapChoices(2),
            homeHost = "home.example",
            lastPolicyRun = run,
        )

    @Test
    fun theSwitchNamesTheHomeMintAndTheCapShowsWhileOn() {
        var cap = 0
        show(state(collect = true), CollectActions(onFeeCap = { cap = it }))
        compose.onNodeWithText(text(R.string.notes_collect_title, "home.example")).performScrollTo().assertExists()
        compose.onNodeWithText("5%").performScrollTo().performClick()
        assertEquals(5, cap)
    }

    @Test
    fun theBrandsOwnCapsAndTheAccountsCurrentOne() {
        show(state(collect = true, defaults = CollectDefaults(feeCaps = listOf(3, 20))))
        listOf("2%", "3%", "20%").forEach { compose.onNodeWithText(it).performScrollTo().assertExists() }
        compose.onNodeWithText("5%").assertDoesNotExist()
    }

    @Test
    fun noCapWhileOffAndNothingIfTheBrandHidesIt() {
        show(state(collect = false, offered = false))
        compose.onNodeWithText(text(R.string.notes_collect_title, "home.example")).assertDoesNotExist()
        compose.onNodeWithText("2%").assertDoesNotExist()
    }

    @Test
    fun payingFromOtherMintsFirstIsASwitchNamingTheHomeMint() {
        var first: Boolean? = null
        compose.setContent {
            NotesSettingsScreen(
                state(collect = false),
                onBack = {},
                onNoteStrategy = {},
                onAutoRotate = {},
                onPayFromOtherMintsFirst = { first = it },
            )
        }
        compose.onNodeWithText(text(R.string.notes_pay_from_others_first_hint, "home.example")).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.notes_pay_from_others_first)).performClick()
        assertEquals(true, first)
    }

    @Test
    fun turningCollectingOnShowsWhatMovesNowAndAsks() {
        var confirmed = false
        val preview =
            MovePreview(
                "home.example",
                listOf(
                    MovePreview.Line("other.example", 50_000, MovePreview.Quote.Ok(49_000, 1_000)),
                    MovePreview.Line("dear.example", 10_000, MovePreview.Quote.Ok(5_000, 5_000)),
                ),
                feeCapPercent = 2,
            )
        show(state(collect = false).copy(collectPreview = preview), CollectActions(onConfirm = { confirmed = true }))
        compose.onNodeWithText(text(R.string.money_review_moves, "₿50", "other.example", "₿49", "₿1")).assertExists()
        compose.onNodeWithText(text(R.string.money_review_over_cap, "₿10", "dear.example", 2)).assertExists()
        compose.onNodeWithText(text(R.string.notes_collect_confirm)).performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun aSkippedMintIsReportedHere() {
        val run =
            PolicyRun(
                0,
                0,
                0,
                null,
                0,
                notCollected = listOf(NotCollected("other.example", 50_000, NotCollected.Reason.FeeTooHigh(2_000, 2))),
            )
        show(state(collect = true, run))
        compose.onNodeWithText(text(R.string.notes_collect_fee_too_high, "₿50", "other.example", "₿2", 2)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.money_move_amount, "anyway")).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanReport() {
        val run = PolicyRun(0, 0, 0, null, 0, collected = listOf(Collected("other.example", 49_000, 1_000)))
        show(state(collect = true, run))
        compose.onNodeWithText("49 ₿ von other.example verschoben (Gebühr 1 ₿)").performScrollTo().assertExists()
        compose.onNodeWithText("2 %").performScrollTo().assertExists()
    }

    @Test
    fun untilTheSettingsAreReadThePageDrawsNothing() {
        show(state(collect = true).copy(loaded = false))
        compose.onNodeWithText(text(R.string.notes_pay_from_others_first)).assertDoesNotExist()
    }
}
