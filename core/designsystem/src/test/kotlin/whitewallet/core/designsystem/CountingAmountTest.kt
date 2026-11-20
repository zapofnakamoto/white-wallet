package whitewallet.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CountingAmountTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theFirstFigureIsThereAtOnce() {
        compose.mainClock.autoAdvance = false
        compose.setContent { CountingAmountText(21_000) }
        compose.onNodeWithText("₿21").assertExists()
    }

    @Test
    fun aNewFigureIsCountedToAndEndsExact() {
        var msat by mutableLongStateOf(1_000)
        compose.setContent { CountingAmountText(msat) }
        compose.onNodeWithText("₿1").assertExists()
        msat = 3_000
        compose.waitForIdle()
        compose.onNodeWithText("₿3").assertExists()
        compose.onNodeWithText("₿1").assertDoesNotExist()
    }

    @Test
    fun onTheWayAScreenReaderIsToldTheNewAmountOnly() {
        var msat by mutableLongStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { CountingAmountText(msat) }
        compose.runOnIdle { msat = 100_000 }
        // A few frames to start, then part of the way.
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val shown = compose.onNode(hasContentDescription("", substring = true)).fetchSemanticsNode()
        val spoken = shown.config[SemanticsProperties.ContentDescription].single()
        val text = shown.config[SemanticsProperties.Text].single().text
        // Drawn part of the way, and said as the amount it is going to.
        assertNotEquals("₿0", text)
        assertNotEquals("₿100", text)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("₿100").assertExists()
        assertEquals(
            spoken,
            compose
                .onNode(hasContentDescription("", substring = true))
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .single(),
        )
    }
}
