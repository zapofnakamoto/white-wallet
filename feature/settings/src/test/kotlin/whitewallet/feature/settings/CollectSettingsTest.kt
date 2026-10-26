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
import whitewallet.core.wallet.Collected
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.NotCollected
import whitewallet.core.wallet.PolicyRun

/** The "collect at home mint" part of the notes page. */
@RunWith(RobolectricTestRunner::class)
class CollectSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private val manual = ManualStrategy(title = R.string.settings_strategy_manual, description = R.string.settings_strategy_manual_hint)

    private fun show(state: NotesSettingsState, collect: CollectActions = CollectActions()) = compose.setContent {
        NotesSettingsScreen(state, onBack = {}, onNoteStrategy = {}, onAutoRotate = {}, collect = collect)
    }

    private fun state(collect: Boolean, run: PolicyRun? = null, offered: Boolean = true) = NotesSettingsState(
        noteStrategy = manual, strategies = listOf(manual), collectOffered = offered, collectAtHome = collect, collectFeeCap = 2,
        homeHost = "home.example", lastPolicyRun = run,
    )

    @Test
    fun theSwitchNamesTheHomeMintAndTheCapShowsWhileOn() {
        var cap = 0
        show(state(collect = true), CollectActions(onFeeCap = { cap = it }))
        compose.onNodeWithText("Move everything to home.example").performScrollTo().assertExists()
        compose.onNodeWithText("5%").performScrollTo().performClick()
        assertEquals(5, cap)
    }

    @Test
    fun noCapWhileOffAndNothingIfTheBrandHidesIt() {
        show(state(collect = false, offered = false))
        compose.onNodeWithText("Move everything to home.example").assertDoesNotExist()
        compose.onNodeWithText("2%").assertDoesNotExist()
    }

    @Test
    fun aSkippedMintIsReportedHere() {
        val run = PolicyRun(0, 0, 0, null, 0, notCollected = listOf(NotCollected("other.example", 50_000, NotCollected.Reason.FeeTooHigh(2_000, 2))))
        show(state(collect = true, run))
        compose.onNodeWithText("Not moved: ₿50 on other.example. The fee of ₿2 is more than 2%.").performScrollTo().assertExists()
        compose.onNodeWithText("Move anyway").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanReport() {
        val run = PolicyRun(0, 0, 0, null, 0, collected = listOf(Collected("other.example", 49_000, 1_000)))
        show(state(collect = true, run))
        compose.onNodeWithText("49 ₿ von other.example verschoben (Gebühr 1 ₿)").performScrollTo().assertExists()
        compose.onNodeWithText("2 %").performScrollTo().assertExists()
    }
}
