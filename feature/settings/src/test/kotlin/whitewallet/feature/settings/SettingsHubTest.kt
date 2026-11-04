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
        compose.onNodeWithText(text(R.string.settings_backup_now)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_delete)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_app_lock_off)).assertExists()
        compose.onNodeWithText("M").assertExists() // the account's mark
        compose.onNodeWithText(text(R.string.settings_account_section, "Main")).performClick()
        assertEquals(listOf(SettingsTarget.Account), opened)
    }

    @Test
    fun aMissingBackupIsWarnedAboutOnTop() {
        val opened = mutableListOf<SettingsTarget>()
        compose.setContent {
            SettingsScreen(SettingsState(accountName = "Main", profile = profile), "1.0", emptyList(), {}) { opened += (it as SearchDestination.Page).target }
        }
        compose.onNodeWithText(text(R.string.settings_backup_now)).performClick()
        assertEquals(listOf(SettingsTarget.Backup), opened)
    }

    @Test
    fun hiddenPagesHaveNoRowAndNoSearchResult() {
        val hidden = setOf(SettingsTarget.Mints, SettingsTarget.AccountMints, SettingsTarget.Notes, SettingsTarget.NoteList)
        val state = SettingsState(accountName = "Main", profile = profile, backupVerified = true, homeHost = "mint.example", hidden = hidden)
        compose.setContent { AccountSettingsScreen(state, {}, {}) }
        compose.onNodeWithText("alice@mint.example").assertExists()
        compose.onNodeWithText(text(R.string.settings_account_mints)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_notes)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_note_list)).assertDoesNotExist()
    }

    @Test
    fun hiddenPagesAreLeftOutOfTheHubAndSearch() {
        val state = SettingsState(accountName = "Main", profile = profile, backupVerified = true, hidden = setOf(SettingsTarget.Mints, SettingsTarget.Notes, SettingsTarget.Display, SettingsTarget.Developer))
        compose.setContent { SettingsScreen(state, "1.0", emptyList(), {}) {} }
        compose.onNodeWithText(text(R.string.settings_mints)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_developer)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_amounts)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("trusted")
        compose.onNodeWithText(text(R.string.settings_mints)).assertDoesNotExist()
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
