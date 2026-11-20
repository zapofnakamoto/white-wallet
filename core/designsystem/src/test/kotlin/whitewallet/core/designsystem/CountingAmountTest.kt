package whitewallet.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
    fun anotherOwnersFigureIsThereAtOnce() {
        var msat by mutableLongStateOf(1_000_000)
        var of by mutableStateOf("a")
        compose.mainClock.autoAdvance = false
        compose.setContent { CountingAmountText(msat, of = of) }
        compose.runOnIdle {
            msat = 3_000_000
            of = "b"
        }
        // A frame or two: a count would be barely on its way.
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertEquals(
            "₿3,000",
            compose.onNode(hasContentDescription("", substring = true)).fetchSemanticsNode().config[SemanticsProperties.Text].single().text,
        )
    }

    @Test
    fun drawnAgainItCountsOnFromTheFigureSeenLast() {
        val last = LastFigure()
        var covered by mutableStateOf(false)
        var msat by mutableLongStateOf(1_000_000)
        compose.mainClock.autoAdvance = false
        compose.setContent { if (!covered) CountingAmountText(msat, last = last) }
        compose.mainClock.advanceTimeByFrame()
        // The new amount comes with the cover: the count never got on its way, so ₿1,000 is what was seen.
        compose.runOnIdle {
            msat = 3_000_000
            covered = true
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { covered = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("₿1,000").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("₿3,000").assertExists()
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
