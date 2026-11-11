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
        val opened = mutableListOf<SettingsDestination>()
        compose.setContent {
            SettingsScreen(SettingsState(accountId = "a", accountName = "Main", address = address, backupVerified = true), "1.0", {}) {
                opened += it
            }
        }
        compose.onNodeWithText(text(R.string.settings_backup_now)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_delete)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_app_lock_off)).assertExists()
        compose.onNodeWithText("M").assertExists() // the account's mark
        // The account in use comes first and opens its own page; every wallet is one row below, naming the one in use.
        compose.onNodeWithText(address).assertExists()
        compose.onNodeWithText("Main").performClick()
        compose.onNodeWithText(text(R.string.wallet_numbered, 1)).assertExists()
        compose.onNodeWithText(text(R.string.settings_wallets)).performClick()
        assertEquals(listOf(SettingsDestination.Account("a"), SettingsDestination.Page(SettingsTarget.Wallets)), opened)
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
    fun aNamedWalletShowsItsNameInTheHub() {
        compose.setContent { SettingsScreen(SettingsState(accountName = "Main", walletName = "Family", walletNumber = 2), "1.0", {}) {} }
        compose.onNodeWithText("Family").assertExists()
        compose.onNodeWithText(text(R.string.settings_account_no_address)).assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun theAccountPageHoldsTheAccountsSettings() {
        compose.setContent { AccountSettingsScreen(SettingsState(accountName = "Main", address = address), {}, {}) }
        compose.onNodeWithText("Lightning-Adressen").assertExists()
        compose.onNodeWithText("alice@mint.example").assertExists()
        // What belongs to the recovery phrase is on the wallet's page.
        compose.onNodeWithText("Wiederherstellungsphrase").assertDoesNotExist()
    }

    @Test
    fun untilTheWalletIsReadTheHubDrawsNoRows() {
        compose.setContent { SettingsScreen(SettingsState(loaded = false), "1.0", {}) {} }
        compose.onNodeWithText(text(R.string.security_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.about_title)).assertDoesNotExist()
    }
}
