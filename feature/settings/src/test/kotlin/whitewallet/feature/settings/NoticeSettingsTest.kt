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
import whitewallet.core.wallet.ManualStrategy

/** The switch for being told what the wallet did to the notes by itself, on the notes page. */
@RunWith(RobolectricTestRunner::class)
class NoticeSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private val manual = ManualStrategy(title = R.string.settings_strategy_manual, description = R.string.settings_strategy_manual_hint)

    private fun show(offered: Boolean, on: Boolean, onNotices: (Boolean) -> Unit = {}) = compose.setContent {
        NotesSettingsScreen(
            NotesSettingsState(noteStrategy = manual, strategies = listOf(manual), noticesOffered = offered, notices = on),
            onBack = {}, onNoteStrategy = {}, onAutoRotate = {}, onNotices = onNotices,
        )
    }

    @Test
    fun theSwitchTurnsTheMessagesOff() {
        var chosen: Boolean? = null
        show(offered = true, on = true) { chosen = it }
        compose.onNodeWithText("Tell me what the wallet did by itself").performScrollTo().performClick()
        assertEquals(false, chosen)
    }

    @Test
    fun noSwitchIfTheBrandDecides() {
        show(offered = false, on = true)
        compose.onNodeWithText("Tell me what the wallet did by itself").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun theSwitchInGerman() {
        show(offered = true, on = false)
        compose.onNodeWithText("Melden, was die Wallet selbst erledigt hat").performScrollTo().assertExists()
    }
}
