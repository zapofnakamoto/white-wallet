package whitewallet.feature.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SettingsHubTest {
    @get:Rule val compose = createComposeRule()

    private val address = "alice@mint.example"

    @Test
    fun theHubOnlyLinksToPages() {
        val opened = mutableListOf<SettingsTarget>()
        compose.setContent {
            SettingsScreen(SettingsState(accountName = "Main", address = address, backupVerified = true), "1.0", {}) {
                opened += (it as SettingsDestination.Page).target
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
            SettingsScreen(SettingsState(accountName = "Main", address = address), "1.0", {}) {
                opened += (it as SettingsDestination.Page).target
            }
        }
        compose.onNodeWithText(text(R.string.settings_backup_now)).performClick()
        assertEquals(listOf(SettingsTarget.Backup), opened)
    }

    @Test
    fun hiddenPagesHaveNoRowOnTheAccountPage() {
        val hidden = setOf(SettingsTarget.Mints, SettingsTarget.AccountMints, SettingsTarget.Notes, SettingsTarget.NoteList)
        val state =
            SettingsState(accountName = "Main", address = address, backupVerified = true, homeHost = "mint.example", hidden = hidden)
        compose.setContent { AccountSettingsScreen(state, {}, {}) }
        compose.onNodeWithText("alice@mint.example").assertExists()
        compose.onNodeWithText(text(R.string.settings_account_mints)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_notes)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_note_list)).assertDoesNotExist()
    }

    @Test
    fun hiddenPagesAreLeftOutOfTheHub() {
        val state =
            SettingsState(
                accountName = "Main",
                address = address,
                backupVerified = true,
                hidden = setOf(SettingsTarget.Mints, SettingsTarget.Notes, SettingsTarget.Display, SettingsTarget.Developer),
            )
        compose.setContent { SettingsScreen(state, "1.0", {}) {} }
        compose.onNodeWithText(text(R.string.settings_mints)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_developer)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_amounts)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun theAccountPageHoldsTheAccountsSettings() {
        compose.setContent { AccountSettingsScreen(SettingsState(accountName = "Main", address = address), {}, {}) }
        compose.onNodeWithText("Konto: Main").assertExists()
        compose.onNodeWithText("Wiederherstellungsphrase").assertExists()
        compose.onNodeWithText("alice@mint.example").assertExists()
    }
}
