package whitewallet.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sevenTapsOnTheVersionUnlockDeveloperOptions() {
        var unlocked = 0
        compose.setContent { AboutScreen("Wallet", "1.0", "https://example.com", onBack = {}, onUnlock = { unlocked++ }) }
        val version = compose.onNodeWithText("Version 1.0")
        repeat(3) { version.performClick() }
        compose.onNodeWithText("4 more taps to show developer options").assertExists()
        repeat(3) { version.performClick() }
        compose.onNodeWithText("1 more tap to show developer options").assertExists()
        version.performClick()
        compose.onNodeWithText("Developer options are now in Settings.").assertExists()
        version.performClick()
        assertEquals(1, unlocked)
    }

    @Test
    fun withNothingToUnlockTheVersionIsJustText() {
        compose.setContent { AboutScreen("Wallet", "1.0", "https://example.com", onBack = {}) }
        repeat(7) { compose.onNodeWithText("Version 1.0").performClick() }
        compose.onNodeWithText("Developer options are now in Settings.").assertDoesNotExist()
    }
}
