package whitewallet.core.designsystem

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import whitewallet.core.model.Account
import whitewallet.core.model.AccountChoice
import whitewallet.core.model.AccountSummary

@RunWith(RobolectricTestRunner::class)
class AccountSwitcherTest {
    @get:Rule val compose = createComposeRule()

    /** An account of the one wallet "w". */
    private fun summary(id: String, name: String) = AccountSummary(Account(id, name, 0, walletId = "w"), 0)

    private fun show(accounts: List<AccountSummary>, activeId: String, onSelect: (String) -> Unit = {}) = compose.setContent {
        AccountSwitcher(accounts, activeId, onSelect = onSelect, onManage = {}, onMove = {}, onDismiss = {})
    }

    @Test
    fun eachAccountIsMarkedWithTheFirstLetterOfItsName() {
        // A combining accent stays with its letter; leading spaces don't count; a script without case keeps its letter.
        show(listOf(summary("a", "  savings"), summary("b", "été"), summary("c", "日常")), activeId = "a")
        compose.onNodeWithText("S").assertExists()
        compose.onNodeWithText("É").assertExists()
        compose.onNodeWithText("日").assertExists()
    }

    @Test
    fun theActiveAccountIsTheSelectedOne() {
        var selected: String? = null
        show(listOf(summary("a", "Savings"), summary("b", "Daily")), activeId = "a") { selected = it }
        compose.onNode(hasText("Savings"), useUnmergedTree = false).assertIsSelected()
        compose.onNode(hasText("Daily"), useUnmergedTree = false).assertIsNotSelected()
        compose.onNodeWithText("Daily").performClick()
        assertEquals("b", selected)
    }

    @Test
    fun manyAccountsScrollToTheButtonsAndTheHint() {
        show((1..30).map { summary("$it", "Account $it") }, activeId = "1")
        compose.onNodeWithText(text(R.string.accounts_separate_backups)).performScrollTo().assertIsDisplayed()
    }

    private val first = AccountSummary(Account("a", "Main", 0), 0)
    private val sibling = AccountSummary(Account("b", "Savings", 1, walletId = "a", index = 1), 0)
    private val other = AccountSummary(Account("c", "Work", 2), 0)

    @Test
    fun onlyTheWalletInUseShowsItsAccountsUntilTheOthersAreAskedFor() {
        var selected: String? = null
        compose.setContent {
            AccountSwitcher(listOf(first, sibling, other), "b", { selected = it }, {}, {}, {}, walletNames = mapOf("c" to "Family"))
        }
        compose.onNodeWithText(text(R.string.accounts_wallet, 1)).assertExists()
        compose.onNodeWithText("Main").assertExists()
        compose.onNodeWithText("Savings").assertExists()
        compose.onNodeWithText("Family").assertDoesNotExist()
        compose.onNodeWithText("Work").assertDoesNotExist()
        compose.onNodeWithText(plural(R.plurals.accounts_other_wallets, 1, 1)).performClick()
        compose.onNodeWithText("Family").assertExists()
        compose.onNodeWithText(plural(R.plurals.accounts_other_wallets, 1, 1)).assertDoesNotExist()
        compose.onNodeWithText("Work").performClick()
        assertEquals("c", selected)
    }

    @Test
    fun theWalletInUseIsShownWhicheverItIs() {
        compose.setContent { AccountSwitcher(listOf(first, sibling, other), "c", {}, {}, {}, {}, walletNames = mapOf("c" to "Family")) }
        compose.onNodeWithText("Family").assertExists()
        compose.onNodeWithText("Work").assertExists()
        compose.onNodeWithText("Main").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.accounts_wallet, 1)).assertDoesNotExist()
    }

    @Test
    fun oneWalletHasNoOtherWalletsToShow() {
        show(listOf(first, sibling), activeId = "a")
        compose.onNodeWithText(plural(R.plurals.accounts_other_wallets, 1, 1)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.accounts_wallet, 1)).assertDoesNotExist()
    }

    @Test
    fun severalWalletsAreShownApartByNameOrNumberAndManagedInSettings() {
        var managing = false
        compose.setContent {
            AccountSwitcher(listOf(first, sibling, other), "a", {}, { managing = true }, {}, {}, walletNames = mapOf("c" to "Family"))
        }
        compose.onNodeWithText(plural(R.plurals.accounts_other_wallets, 1, 1)).performClick()
        compose.onNodeWithText(text(R.string.accounts_wallet, 1)).assertExists()
        compose.onNodeWithText("Family").assertExists()
        compose.onNodeWithText(text(R.string.accounts_wallet, 2)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.accounts_manage)).performScrollTo().performClick()
        assertEquals(true, managing)
    }

    private fun choice(vararg accounts: AccountSummary, active: String, shown: Boolean = true, names: Map<String, String> = emptyMap()) =
        AccountChoice(accounts.toList(), active, names, shown)

    @Test
    fun theAccountButtonIsReadAsTheAccountInUse() {
        compose.setContent { WalletAccountButton(choice(summary("a", "Savings"), summary("b", "Daily"), active = "a")) {} }
        compose.onNodeWithContentDescription("Savings").assertExists()
        compose.onNode(hasClickLabel(text(R.string.account_switch))).assertExists()
    }

    @Test
    fun withSeveralWalletsTheButtonNamesTheWalletToo() {
        val other = AccountSummary(Account("c", "Kids", 0, walletId = "v"), 0)
        compose.setContent { WalletAccountButton(choice(summary("a", "Savings"), other, active = "c", names = mapOf("v" to "Family"))) {} }
        compose.onNodeWithContentDescription(text(R.string.account_in_wallet, "Family", "Kids")).assertExists()
    }

    @Test
    fun anUnnamedWalletIsCalledByItsNumber() {
        val other = AccountSummary(Account("c", "Kids", 0, walletId = "v"), 0)
        compose.setContent { WalletAccountButton(choice(summary("a", "Savings"), other, active = "c")) {} }
        compose.onNodeWithContentDescription(text(R.string.account_in_wallet, text(R.string.accounts_wallet, 2), "Kids")).assertExists()
    }

    @Test
    fun theButtonIsAbsentWhenNotShown() {
        compose.setContent { WalletAccountButton(choice(summary("a", "Savings"), active = "a", shown = false)) {} }
        compose.onNodeWithContentDescription("Savings").assertDoesNotExist()
    }

    @Test
    fun theButtonIsAbsentWithoutAnAccountInUse() {
        compose.setContent { WalletAccountButton(choice(summary("a", "Savings"), active = "x")) {} }
        compose.onNodeWithContentDescription("Savings").assertDoesNotExist()
    }

    @Test
    fun tappingTheButtonOpensTheSwitcher() {
        var opened = false
        compose.setContent { WalletAccountButton(choice(summary("a", "Savings"), active = "a")) { opened = true } }
        compose.onNodeWithContentDescription("Savings").performClick()
        assertEquals(true, opened)
    }
}

private fun text(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

private fun plural(id: Int, count: Int, vararg args: Any): String =
    RuntimeEnvironment.getApplication().resources.getQuantityString(id, count, *args)

private fun hasClickLabel(label: String) =
    SemanticsMatcher("click label $label") { SemanticsActions.OnClick in it.config && it.config[SemanticsActions.OnClick].label == label }
