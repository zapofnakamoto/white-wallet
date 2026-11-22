package whitewallet.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A button at work: locked at once, with its label and its look; the spinner beside the label follows the one rule for loading. */
@RunWith(RobolectricTestRunner::class)
class BusyButtonTest {
    @get:Rule val compose = createComposeRule()

    private var taps = 0
    private val spinner = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    /** A button that is at work once the returned function is called with true. */
    private fun button(): (Boolean) -> Unit {
        var busy by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { PrimaryButton("Check again", { taps++ }, busy = busy) }
        compose.mainClock.advanceTimeByFrame()
        return {
            busy = it
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }
    }

    private fun label() = compose.onNodeWithText("Check again")

    @Test
    fun aButtonThatIsntAtWorkIsTapped() {
        button()
        label().assertIsEnabled().performClick()
        assertEquals(1, taps)
        compose.onNode(spinner).assertDoesNotExist()
    }

    @Test
    fun atWorkItIsLockedAtOnceAndKeepsItsLabel() {
        val work = button()
        work(true)
        label().assertExists().assertIsNotEnabled().performClick()
        assertEquals(0, taps)
        compose.onNode(spinner).assertDoesNotExist()
    }

    @Test
    fun workOverInAMomentNeverShowsTheSpinner() {
        val work = button()
        work(true)
        compose.mainClock.advanceTimeBy(300)
        work(false)
        compose.onNode(spinner).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNode(spinner).assertDoesNotExist()
        label().assertIsEnabled().performClick()
        assertEquals(1, taps)
    }

    @Test
    fun workThatTakesAWhileShowsTheSpinnerBesideTheLabel() {
        val work = button()
        work(true)
        compose.mainClock.advanceTimeBy(450)
        compose.onNode(spinner).assertExists()
        label().assertExists().assertIsNotEnabled()
    }

    @Test
    fun theSpinnerStaysItsShortestTimeAndTheButtonStaysLockedWithIt() {
        val work = button()
        work(true)
        compose.mainClock.advanceTimeBy(450)
        work(false)
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(spinner).assertExists()
        label().performClick()
        assertEquals(0, taps)
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(spinner).assertDoesNotExist()
        label().assertIsEnabled().performClick()
        assertEquals(1, taps)
    }
}
