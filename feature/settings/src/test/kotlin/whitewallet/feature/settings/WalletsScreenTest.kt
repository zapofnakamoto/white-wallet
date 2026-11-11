package whitewallet.feature.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.WalletGroup
import whitewallet.core.model.WalletRecord
import whitewallet.core.wallet.AccountRemoval
import whitewallet.core.wallet.AccountUse
import whitewallet.core.wallet.RemoveAccountResult

@RunWith(RobolectricTestRunner::class)
class WalletsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val main = AccountSummary(Account("a", "Main", 1), 5_000_000)
    private val savings = AccountSummary(Account("b", "Savings", 2, walletId = "a", index = 1), 3_000_000)
    private val work = AccountSummary(Account("c", "Work", 3), 0)
    private val first = WalletGroup(WalletRecord("a", null, 1), 1, listOf(main, savings))
    private val second = WalletGroup(WalletRecord("c", "Family", 3), 2, listOf(work))
    private val state =
        WalletsState(
            wallets = listOf(first, second),
            activeAccountId = "a",
            places = mapOf("a" to 0, "b" to 1, "c" to 2),
            canAddWallet = true,
            canAddAccount = true,
            loaded = true,
        )
    private val active = SettingsState(accountName = "Main", address = "alice@mint.example", backupVerified = true, developerMode = false)

    @Test
    fun everyWalletIsListedByNameOrNumberAndOneCanBeAdded() {
        val opened = mutableListOf<String>()
        var added = false
        compose.setContent { WalletsScreen(state, {}, { opened += it }, { added = true }) }
        compose.onNodeWithText(text(R.string.wallet_numbered, 1)).assertExists()
        compose.onNodeWithText("Family").assertExists()
        compose.onNodeWithText(plural(R.plurals.wallets_accounts, 2)).assertExists()
        compose.onNodeWithText(plural(R.plurals.wallets_accounts, 1)).assertExists()
        compose.onNodeWithText(text(R.string.wallet_in_use)).assertExists()
        compose.onNodeWithText("Family").performClick()
        compose.onNodeWithText(text(R.string.wallets_add)).performScrollTo().performClick()
        assertEquals(listOf("c"), opened)
        assertEquals(true, added)
    }

    @Test
    fun atTheBrandsLimitNoWalletCanBeAdded() {
        compose.setContent { WalletsScreen(state.copy(canAddWallet = false), {}, {}, {}) }
        compose.onNodeWithText(text(R.string.wallets_add)).assertDoesNotExist()
    }

    @Test
    fun theWalletInUseShowsWhatBelongsToItsPhraseAndItsAccounts() {
        val opened = mutableListOf<SettingsDestination>()
        var adding = false
        compose.setContent { WalletScreen(first, state, active, {}, { opened += it }, { adding = true }, {}) }
        compose.onNodeWithText(text(R.string.settings_recovery_phrase)).performClick()
        compose.onNodeWithText(text(R.string.settings_mints)).performClick()
        compose.onNodeWithText(text(R.string.settings_scan_done)).assertExists()
        compose.onNodeWithText(text(R.string.settings_scan)).performClick()
        compose.onNodeWithText("alice@mint.example").assertExists() // the account in use shows its address
        compose.onNodeWithText("Savings").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.wallet_add_account)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_delete)).performScrollTo().performClick()
        assertEquals(
            listOf(
                SettingsDestination.Page(SettingsTarget.Backup),
                SettingsDestination.Page(SettingsTarget.Mints),
                SettingsDestination.Page(SettingsTarget.Scan),
                SettingsDestination.Account("b"),
                SettingsDestination.Page(SettingsTarget.Delete),
            ),
            opened,
        )
        assertEquals(true, adding)
    }

    @Test
    fun aWalletWhoseScanIsNotFinishedSaysSo() {
        compose.setContent { WalletScreen(first, state, active.copy(unscannedCount = 1), {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.settings_scan_unfinished)).assertExists()
        compose.onNodeWithText(text(R.string.settings_scan_done)).assertDoesNotExist()
    }

    @Test
    fun theOnlyWalletsPageAlsoAddsAWallet() {
        var added = false
        compose.setContent { WalletScreen(first, state.copy(wallets = listOf(first)), active, {}, {}, {}, {}) { added = true } }
        compose.onNodeWithText(text(R.string.wallets_add_hint)).assertExists()
        compose.onNodeWithText(text(R.string.wallets_add)).performScrollTo().performClick()
        assertEquals(true, added)
    }

    @Test
    fun aWalletsPageOpenedFromTheListAddsNoWallet() {
        compose.setContent { WalletScreen(first, state, active, {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.wallets_add)).assertDoesNotExist()
    }

    @Test
    fun aWalletNotInUseOnlyListsItsAccounts() {
        val opened = mutableListOf<SettingsDestination>()
        compose.setContent { WalletScreen(second, state, active, {}, { opened += it }, {}, {}) }
        compose.onNodeWithText(text(R.string.wallet_not_in_use)).assertExists()
        compose.onNodeWithText(text(R.string.settings_recovery_phrase)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_mints)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.wallet_add_account)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_delete)).assertDoesNotExist()
        compose.onNodeWithText("Work").performClick()
        assertEquals(listOf<SettingsDestination>(SettingsDestination.Account("c")), opened)
    }

    @Test
    fun aWalletIsRenamedAndAnEmptyNameGivesItsNumberBack() {
        val names = mutableListOf<String>()
        compose.setContent { WalletScreen(second, state.copy(activeAccountId = "c"), active, {}, {}, {}, { names += it }) }
        compose.onNodeWithText(text(R.string.wallet_name)).performClick()
        compose.onNodeWithText(text(R.string.wallet_rename_title)).assertExists()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextClearance()
        compose.onNodeWithText(text(R.string.rename_save)).performClick()
        assertEquals(listOf(""), names)
    }

    @Test
    fun anAccountIsRenamedButNotToNothing() {
        val names = mutableListOf<String>()
        compose.setContent { AccountSettingsScreen(active, {}, {}, { names += it }) }
        compose.onNodeWithText(text(R.string.wallet_name)).performClick()
        val field = compose.onNode(androidx.compose.ui.test.hasSetTextAction())
        field.performTextClearance()
        compose.onNodeWithText(text(R.string.rename_save)).assertIsNotEnabled()
        field.performTextInput("Daily")
        compose.onNodeWithText(text(R.string.rename_save)).performClick()
        assertEquals(listOf("Daily"), names)
    }

    @Test
    fun theKeysPageIsOnTheAccountsPageInDeveloperModeOnly() {
        val opened = mutableListOf<SettingsTarget>()
        compose.setContent { AccountSettingsScreen(active.copy(developerMode = true, hidden = emptySet()), {}, { opened += it }) }
        compose.onNodeWithText(text(R.string.keys_title)).performScrollTo().performClick()
        assertEquals(listOf(SettingsTarget.Keys), opened)
    }

    @Test
    fun withoutDeveloperModeTheAccountsPageHasNoKeys() {
        compose.setContent { AccountSettingsScreen(active.copy(hidden = emptySet()), {}, {}) }
        compose.onNodeWithText(text(R.string.keys_title)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun anAccountNotInUseOnlyOffersToUseIt() {
        var used = false
        compose.setContent { AccountNotInUseScreen(work, 2, {}) { used = true } }
        compose.onNodeWithText("Dieses Konto verwenden").performClick()
        assertEquals(true, used)
    }

    @Test
    fun anAccountCanBeRemovedOnlyWhereTheWalletHasAnother() {
        var asked = false
        compose.setContent { AccountSettingsScreen(active, {}, {}, onRemove = { asked = true }) }
        compose.onNodeWithText(text(R.string.account_remove)).performScrollTo().performClick()
        assertEquals(true, asked)
    }

    @Test
    fun aWalletsOnlyAccountHasNoRemoveRow() {
        compose.setContent { AccountSettingsScreen(active, {}, {}) }
        compose.onNodeWithText(text(R.string.account_remove)).assertDoesNotExist()
    }

    @Test
    fun removingAnAccountNamesTheAddressesItLeaves() {
        var removed = false
        val removal = AccountRemoval(null, 0, listOf("alice@mint.example", "shop@mint.example"))
        compose.setContent { AccountRemovalDialog("Savings", removal, {}) { removed = true } }
        compose.onNodeWithText(text(R.string.account_remove_title, "Savings")).assertExists()
        compose.onNodeWithText(plural(R.plurals.account_remove_addresses, 2, "alice@mint.example, shop@mint.example")).assertExists()
        compose.onNodeWithText(text(R.string.account_remove_confirm)).performClick()
        assertEquals(true, removed)
    }

    @Test
    @Config(qualifiers = "de")
    fun anAccountThatHoldsMoneyCantBeRemoved() {
        var removed = false
        val removal = AccountRemoval(RemoveAccountResult.InUse(AccountUse.Money), 3_000_000, emptyList())
        compose.setContent { AccountRemovalDialog("Sparen", removal, {}) { removed = true } }
        compose.onNodeWithText("Dieses Konto kann noch nicht entfernt werden").assertExists()
        compose.onNodeWithText("Es hält noch", substring = true).assertExists()
        compose.onNodeWithText("Konto entfernen").assertDoesNotExist()
        assertEquals(false, removed)
    }

    @Test
    fun anAccountWithSomethingUnfinishedCantBeRemoved() {
        val removal = AccountRemoval(RemoveAccountResult.InUse(AccountUse.OpenOperation), 0, emptyList())
        compose.setContent { AccountRemovalDialog("Savings", removal, {}, {}) }
        compose.onNodeWithText(text(R.string.account_remove_blocked_open)).assertExists()
        compose.onNodeWithText(text(R.string.account_remove_confirm)).assertDoesNotExist()
    }
}
