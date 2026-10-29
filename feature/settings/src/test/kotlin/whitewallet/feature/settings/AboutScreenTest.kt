package whitewallet.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import whitewallet.brand.ReleaseNote
import whitewallet.brand.ReleaseStage
import whitewallet.core.designsystem.LocalReleaseStage
import whitewallet.core.designsystem.R as DesignR

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

    @Test
    fun aBetaNamesItsStageAndWhatItMeans() {
        compose.setContent {
            CompositionLocalProvider(LocalReleaseStage provides ReleaseStage.Beta) { AboutScreen("Wallet", "0.2.0", "https://example.com", onBack = {}) }
        }
        compose.onNodeWithText("Beta").assertExists()
        compose.onNodeWithText("This app is a beta version: it is still being tested and may have bugs.").assertExists()
    }

    @Test
    fun aStableReleaseShowsNoStage() {
        compose.setContent { AboutScreen("Wallet", "1.0", "https://example.com", onBack = {}) }
        compose.onNodeWithText("Beta").assertDoesNotExist()
        compose.onNodeWithText("This app is a beta version: it is still being tested and may have bugs.").assertDoesNotExist()
    }

    @Test
    fun whatsNewListsEveryReleaseNote() {
        // Any two strings stand in for a note's title and body.
        val note = ReleaseNote("sample", DesignR.string.release_stage_alpha, DesignR.string.release_notice_alpha)
        compose.setContent { AboutScreen("Wallet", "1.0", "https://example.com", onBack = {}, releaseNotes = listOf(note)) }
        compose.onNodeWithText("This app is an alpha version: it is still being built and may have bugs.").assertDoesNotExist()
        compose.onNodeWithText("What's new").performClick()
        compose.onNodeWithText("This app is an alpha version: it is still being built and may have bugs.").assertExists()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithText("This app is an alpha version: it is still being built and may have bugs.").assertDoesNotExist()
    }

    @Test
    fun withoutReleaseNotesThereIsNoWhatsNew() {
        compose.setContent { AboutScreen("Wallet", "1.0", "https://example.com", onBack = {}) }
        compose.onNodeWithText("What's new").assertDoesNotExist()
    }
}
