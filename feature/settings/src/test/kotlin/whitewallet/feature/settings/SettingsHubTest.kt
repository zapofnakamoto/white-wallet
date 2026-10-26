package whitewallet.feature.settings

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.model.Profile
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SettingsHubTest {
    @get:Rule
    val compose = createComposeRule()

    private val profile = Profile(username = "alice", host = "mint.example")

    @Test
    fun theHubOnlyLinksToPages() {
        val opened = mutableListOf<SettingsTarget>()
        compose.setContent {
            SettingsScreen(SettingsState(accountName = "Main", profile = profile, backupVerified = true), "1.0", emptyList(), {}) {
                opened += (it as SearchDestination.Page).target
            }
        }
        compose.onNodeWithText("Back up your recovery phrase").assertDoesNotExist()
        compose.onNodeWithText("Delete this account from this device").assertDoesNotExist()
        compose.onNodeWithText("App lock off").assertExists()
        compose.onNodeWithText("Account: Main").performClick()
        assertEquals(listOf(SettingsTarget.Account), opened)
    }

    @Test
    fun aMissingBackupIsWarnedAboutOnTop() {
        val opened = mutableListOf<SettingsTarget>()
        compose.setContent {
            SettingsScreen(SettingsState(accountName = "Main", profile = profile), "1.0", emptyList(), {}) { opened += (it as SearchDestination.Page).target }
        }
        compose.onNodeWithText("Back up your recovery phrase").performClick()
        assertEquals(listOf(SettingsTarget.Backup), opened)
    }

    @Test
    fun hiddenPagesHaveNoRowAndNoSearchResult() {
        val hidden = setOf(SettingsTarget.Mints, SettingsTarget.Service, SettingsTarget.Notes, SettingsTarget.NoteList)
        val state = SettingsState(accountName = "Main", profile = profile, backupVerified = true, homeHost = "mint.example", hidden = hidden)
        compose.setContent { AccountSettingsScreen(state, {}, {}) }
        compose.onNodeWithText("alice@mint.example").assertExists()
        compose.onNodeWithText("Home mint").assertDoesNotExist()
        compose.onNodeWithText("Note settings").assertDoesNotExist()
        compose.onNodeWithText("Notes").assertDoesNotExist()
    }

    @Test
    fun hiddenPagesAreLeftOutOfTheHubAndSearch() {
        val state = SettingsState(accountName = "Main", profile = profile, backupVerified = true, hidden = setOf(SettingsTarget.Mints, SettingsTarget.Notes, SettingsTarget.Display, SettingsTarget.Developer))
        compose.setContent { SettingsScreen(state, "1.0", emptyList(), {}) {} }
        compose.onNodeWithText("Mints").assertDoesNotExist()
        compose.onNodeWithText("Developer options").assertDoesNotExist()
        compose.onNodeWithText("Amounts").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("trusted")
        compose.onNodeWithText("Mints").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun theAccountPageHoldsTheAccountsSettings() {
        compose.setContent { AccountSettingsScreen(SettingsState(accountName = "Main", profile = profile), {}, {}) }
        compose.onNodeWithText("Konto: Main").assertExists()
        compose.onNodeWithText("Wiederherstellungsphrase").assertExists()
        compose.onNodeWithText("alice@mint.example").assertExists()
    }
}
