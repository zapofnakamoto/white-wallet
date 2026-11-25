package whitewallet.core.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A message at the bottom of the screen goes away with a swipe to either side, as dismissed: its button wasn't tapped. */
@RunWith(RobolectricTestRunner::class)
class SnackbarSwipeTest {
    @get:Rule val compose = createComposeRule()

    private var result: SnackbarResult? = null

    private fun show() {
        val state = SnackbarHostState()
        compose.setContent {
            WalletSnackbarHost(state, Modifier.fillMaxWidth())
            LaunchedEffect(Unit) {
                result = state.showSnackbar("Combined 2 notes", actionLabel = "Details", duration = SnackbarDuration.Indefinite)
            }
        }
        compose.onNodeWithText("Combined 2 notes").assertExists()
    }

    @Test
    fun `a swipe to the right dismisses it`() {
        show()
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Combined 2 notes").assertDoesNotExist()
        assertEquals(SnackbarResult.Dismissed, result)
    }

    @Test
    fun `a swipe to the left dismisses it`() {
        show()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Combined 2 notes").assertDoesNotExist()
        assertEquals(SnackbarResult.Dismissed, result)
    }
}
