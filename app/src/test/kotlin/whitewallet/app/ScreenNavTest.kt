package whitewallet.app

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A screen that is left can still be tapped while the next one fades in: its taps must not move the screens any further. */
@RunWith(RobolectricTestRunner::class)
// Not the app's own Application, which opens the wallet.
@Config(application = Application::class)
class ScreenNavTest {
    @get:Rule val compose = createComposeRule()

    private fun show() = compose.setContent {
        val nav = rememberNavController()
        NavHost(nav, startDestination = "home") {
            screen(nav, "home") {
                Column {
                    BasicText("Home")
                    BasicText("Leave home", Modifier.clickable { back() })
                    BasicText("Open settings", Modifier.clickable { open("settings") })
                }
            }
            screen(nav, "settings") {
                Column {
                    BasicText("Settings")
                    BasicText("Leave settings", Modifier.clickable { back() })
                    BasicText("Open about", Modifier.clickable { open("about") })
                }
            }
            screen(nav, "about") {
                Column {
                    BasicText("About")
                    BasicText("Leave about", Modifier.clickable { back() })
                    BasicText("Home again", Modifier.clickable { backTo("home") })
                }
            }
        }
    }

    /** Taps [text] twice before the screen it is on has gone. */
    private fun tapTwice(text: String) {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text).performClick()
        compose.onNodeWithText(text).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    @Test
    fun backTappedTwiceGoesBackOnce() {
        show()
        tap("Open settings")
        tapTwice("Leave settings")
        compose.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun backTappedTwiceLeavesTheScreenUnderIt() {
        show()
        tap("Open settings")
        tap("Open about")
        tapTwice("Leave about")
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun aRowTappedTwiceOpensItsPageOnce() {
        show()
        tapTwice("Open settings")
        tap("Leave settings")
        compose.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun aScreenThatIsLeftOpensNothing() {
        show()
        tap("Open settings")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Leave settings").performClick()
        compose.onNodeWithText("Open about").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun theLastScreenStays() {
        show()
        tap("Leave home")
        compose.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun backToAScreenFurtherDown() {
        show()
        tap("Open settings")
        tap("Open about")
        tapTwice("Home again")
        compose.onNodeWithText("Home").assertIsDisplayed()
    }
}
