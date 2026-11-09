package whitewallet.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary

@RunWith(RobolectricTestRunner::class)
class AccountSwitcherTest {
    @get:Rule val compose = createComposeRule()

    private fun summary(id: String, name: String) = AccountSummary(Account(id, name, 0), 0)

    private fun show(accounts: List<AccountSummary>, activeId: String, onSelect: (String) -> Unit = {}) = compose.setContent {
        AccountSwitcher(accounts, activeId, canAdd = true, onSelect = onSelect, onAdd = {}, onMove = {}, onDismiss = {})
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

    @Test
    fun severalWalletsAreShownApartAndAnotherOneCanBeAdded() {
        var added = false
        val first = AccountSummary(Account("a", "Main", 0), 0)
        val sibling = AccountSummary(Account("b", "Savings", 1, walletId = "a", index = 1), 0)
        val other = AccountSummary(Account("c", "Work", 2), 0)
        compose.setContent {
            AccountSwitcher(listOf(first, sibling, other), "a", true, {}, {}, {}, {}, canAddWallet = true, onAddWallet = { added = true })
        }
        compose.onNodeWithText(text(R.string.accounts_wallet, 1)).assertExists()
        compose.onNodeWithText(text(R.string.accounts_wallet, 2)).assertExists()
        compose.onNodeWithText(text(R.string.accounts_wallet, 3)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.accounts_add_wallet)).performScrollTo().performClick()
        assertEquals(true, added)
    }
}
