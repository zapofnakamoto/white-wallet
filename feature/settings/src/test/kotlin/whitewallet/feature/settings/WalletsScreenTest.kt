package whitewallet.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.brand.ui.AccountMarkParams
import whitewallet.brand.ui.BrandComponents
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.ListIconParams
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.WalletGroup
import whitewallet.core.model.WalletRecord
import whitewallet.core.wallet.AccountRemoval
import whitewallet.core.wallet.AccountUse
import whitewallet.core.wallet.RemoveAccountResult
import whitewallet.core.wallet.UnusedAccount

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
        compose.onNodeWithText(text(DesignR.string.wallet_numbered, 1)).assertExists()
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
    fun onlyTheWalletInUseIsMarkedAsTheAccountInUseIs() {
        // A brand that draws its marks as words: what each row asked for is on the screen.
        val design =
            object : BrandDesign {
                override val components =
                    object : BrandComponents {
                        @Composable
                        override fun ListIcon(params: ListIconParams, modifier: Modifier) {
                            Text(if (params.activeBadge != null) "wallet in use" else "icon")
                        }

                        @Composable
                        override fun AccountMark(params: AccountMarkParams, modifier: Modifier) {
                            Text(if (params.active) "account in use" else "account")
                        }
                    }
            }
        var page by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design) {
                if (page) WalletScreen(first, state, active, {}, {}, {}, {}, {}) else WalletsScreen(state, {}, {}, {})
            }
        }
        // The first wallet holds the account in use; the second doesn't.
        compose.onAllNodesWithText("wallet in use").assertCountEquals(1)
        compose.onAllNodesWithText("icon").assertCountEquals(1)
        compose.onAllNodesWithText(text(R.string.wallet_in_use)).assertCountEquals(1)
        page = true
        compose.onAllNodesWithText("account in use").assertCountEquals(1)
        compose.onAllNodesWithText("account").assertCountEquals(1)
        // No other row of the app is marked.
        compose.onAllNodesWithText("wallet in use").assertCountEquals(0)
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
        compose.setContent { WalletScreen(first, state, active, {}, { opened += it }, { adding = true }, {}, {}) }
        compose.onNodeWithText(text(R.string.settings_recovery_phrase)).performClick()
        // The mints are the account's, on its page: the wallet page has no Mints row.
        compose.onNodeWithText(text(R.string.settings_mints)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_scan_done)).assertExists()
        compose.onNodeWithText(text(R.string.settings_scan)).performClick()
        compose.onNodeWithText("alice@mint.example").assertExists() // the account in use shows its address
        compose.onNodeWithText("Savings").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.wallet_add_account)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_delete)).performScrollTo().performClick()
        assertEquals(
            listOf(
                SettingsDestination.Page(SettingsTarget.Backup),
                SettingsDestination.Page(SettingsTarget.Scan),
                SettingsDestination.Account("b"),
                SettingsDestination.Page(SettingsTarget.Delete),
            ),
            opened,
        )
        assertEquals(true, adding)
    }

    /** The colour [text] is drawn in. */
    private fun colorOf(text: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }

    /** [screen] on the Wallets page, and the theme's error colour. */
    private fun showWallet(screen: WalletsState, group: WalletGroup = first): Color {
        var error = Color.Unspecified
        compose.setContent {
            error = MaterialTheme.colorScheme.error
            WalletScreen(group, screen, active, {}, {}, {}, {}, {})
        }
        compose.waitForIdle()
        return error
    }

    private val savingsUnused = UnusedAccount(savings.account, hidesLater = false)

    @Test
    fun whileTheNewestAccountHasReceivedNothingALineNamesItInsteadOfTheButton() {
        val error = showWallet(state.copy(useFirst = savingsUnused))
        val line = text(R.string.wallet_add_account_use_first, "Savings")
        compose.onNodeWithText(line).assertExists()
        assertNotEquals(error, colorOf(line))
        compose.onNodeWithText(text(R.string.wallet_add_account)).assertDoesNotExist()
    }

    @Test
    fun anAccountThatHidesLaterOnesFromARestoreIsAWarning() {
        val error = showWallet(state.copy(useFirst = savingsUnused.copy(hidesLater = true)))
        val warning = text(R.string.wallet_account_hides_later, "Savings")
        assertEquals(error, colorOf(warning))
        compose.onNodeWithText(text(R.string.wallet_add_account)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.wallet_add_account_use_first, "Savings")).assertDoesNotExist()
    }

    @Test
    fun atTheBrandsAccountLimitOnlyTheWarningIsLeft() {
        showWallet(state.copy(canAddAccount = false, useFirst = savingsUnused))
        compose.onNodeWithText(text(R.string.wallet_add_account)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.wallet_add_account_use_first, "Savings")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.wallet_account_hides_later, "Savings")).assertDoesNotExist()
    }

    @Test
    fun atTheBrandsAccountLimitMoneyARestoreMissesIsStillSaid() {
        showWallet(state.copy(canAddAccount = false, useFirst = savingsUnused.copy(hidesLater = true)))
        compose.onNodeWithText(text(R.string.wallet_account_hides_later, "Savings")).assertExists()
    }

    @Test
    fun aWalletNotInUseSaysNothingAboutAddingAccounts() {
        showWallet(state.copy(useFirst = UnusedAccount(work.account, hidesLater = true)), group = second)
        compose.onNodeWithText(text(R.string.wallet_add_account)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.wallet_account_hides_later, "Work")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.wallet_add_account_use_first, "Work")).assertDoesNotExist()
    }

    @Test
    fun aWalletWhoseScanIsNotFinishedSaysSo() {
        compose.setContent { WalletScreen(first, state, active.copy(unscannedCount = 1), {}, {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.settings_scan_unfinished)).assertExists()
        compose.onNodeWithText(text(R.string.settings_scan_done)).assertDoesNotExist()
    }

    @Test
    fun theOnlyWalletsPageAlsoAddsAWallet() {
        var added = false
        compose.setContent { WalletScreen(first, state.copy(wallets = listOf(first)), active, {}, {}, {}, {}, {}) { added = true } }
        compose.onNodeWithText(text(R.string.wallets_add_hint)).assertExists()
        compose.onNodeWithText(text(R.string.wallets_add)).performScrollTo().performClick()
        assertEquals(true, added)
    }

    @Test
    fun aWalletsPageOpenedFromTheListAddsNoWallet() {
        compose.setContent { WalletScreen(first, state, active, {}, {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.wallets_add)).assertDoesNotExist()
    }

    @Test
    fun aWalletNotInUseOnlyListsItsAccounts() {
        val opened = mutableListOf<SettingsDestination>()
        compose.setContent { WalletScreen(second, state, active, {}, { opened += it }, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.wallet_not_in_use)).assertExists()
        compose.onNodeWithText(text(R.string.settings_recovery_phrase)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_mints)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.wallet_add_account)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_delete)).assertDoesNotExist()
        compose.onNodeWithText("Work").performClick()
        assertEquals(listOf<SettingsDestination>(SettingsDestination.Account("c")), opened)
    }

    @Test
    fun anAccountNotInUseIsUsedFromItsRowWithoutOpeningItsPage() {
        val opened = mutableListOf<SettingsDestination>()
        val used = mutableListOf<String>()
        compose.setContent { WalletScreen(first, state, active, {}, { opened += it }, {}, {}, { used += it }) }
        // Only Savings offers it: Main is in use.
        compose.onAllNodesWithText(text(R.string.wallet_account_use)).assertCountEquals(1)
        compose.onNodeWithContentDescription(text(R.string.wallet_account_use_named, "Savings")).performScrollTo().performClick()
        assertEquals(listOf("b"), used)
        assertEquals(emptyList<SettingsDestination>(), opened)
    }

    @Test
    fun aWalletIsRenamedAndAnEmptyNameGivesItsNumberBack() {
        val names = mutableListOf<String>()
        compose.setContent { WalletScreen(second, state.copy(activeAccountId = "c"), active, {}, {}, {}, { names += it }, {}) }
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
        compose.setContent { AccountRemovalDialog("Savings", removal, removing = false, {}) { removed = true } }
        compose.onNodeWithText(text(R.string.account_remove_title, "Savings")).assertExists()
        compose.onNodeWithText(plural(R.plurals.account_remove_addresses, 2, "alice@mint.example, shop@mint.example")).assertExists()
        compose.onNodeWithText(text(R.string.account_remove_confirm)).performClick()
        assertEquals(true, removed)
    }

    @Test
    fun whileTheAccountIsRemovedTheQuestionStaysAndCantBeLeft() {
        var dismissed = false
        val removal = AccountRemoval(null, 0, emptyList())
        compose.setContent { AccountRemovalDialog("Savings", removal, removing = true, { dismissed = true }, {}) }
        compose.onNodeWithText(text(R.string.account_remove_title, "Savings")).assertExists()
        compose.onNodeWithText(text(DesignR.string.cancel)).assertIsNotEnabled()
        assertEquals(false, dismissed)
    }

    @Test
    @Config(qualifiers = "de")
    fun anAccountThatHoldsMoneyCantBeRemoved() {
        var removed = false
        val removal = AccountRemoval(RemoveAccountResult.InUse(AccountUse.Money), 3_000_000, emptyList())
        compose.setContent { AccountRemovalDialog("Sparen", removal, removing = false, {}) { removed = true } }
        compose.onNodeWithText("Dieses Konto kann noch nicht entfernt werden").assertExists()
        compose.onNodeWithText("Es hält noch", substring = true).assertExists()
        compose.onNodeWithText("Konto entfernen").assertDoesNotExist()
        assertEquals(false, removed)
    }

    @Test
    fun anAccountWithArchivedNotesCantBeRemoved() {
        val removal = AccountRemoval(RemoveAccountResult.InUse(AccountUse.ArchivedNotes), 0, emptyList())
        compose.setContent { AccountRemovalDialog("Savings", removal, removing = false, {}, {}) }
        compose.onNodeWithText(text(R.string.account_remove_blocked_archived)).assertExists()
        compose.onNodeWithText(text(R.string.account_remove_confirm)).assertDoesNotExist()
    }

    @Test
    fun anAccountWithSomethingUnfinishedCantBeRemoved() {
        val removal = AccountRemoval(RemoveAccountResult.InUse(AccountUse.OpenOperation), 0, emptyList())
        compose.setContent { AccountRemovalDialog("Savings", removal, removing = false, {}, {}) }
        compose.onNodeWithText(text(R.string.account_remove_blocked_open)).assertExists()
        compose.onNodeWithText(text(R.string.account_remove_confirm)).assertDoesNotExist()
    }
}
