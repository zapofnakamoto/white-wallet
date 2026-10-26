package whitewallet.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HomeState, onOpenService: (String) -> Unit = {}, onToggle: () -> Unit = {}) = compose.setContent {
        HomeScreen(
            "Wallet", state, onSend = {}, onReceive = {}, onRefresh = {}, onSettings = {}, onBackup = {}, onDismissBackup = {},
            onChangeAddress = {}, accountActions = AccountActions({}, {}, {}), onToggleBalance = onToggle, onOpenService = onOpenService,
        )
    }

    @Test
    fun tappingTheBalanceHidesIt() {
        compose.setContent {
            var hidden by remember { mutableStateOf(false) }
            HomeScreen(
                "Wallet", HomeState(balanceMsat = 845_000, balanceHidden = hidden), onSend = {}, onReceive = {}, onRefresh = {},
                onSettings = {}, onBackup = {}, onDismissBackup = {}, onChangeAddress = {}, accountActions = AccountActions({}, {}, {}),
                onToggleBalance = { hidden = !hidden },
            )
        }
        compose.onNodeWithText("₿845").performClick()
        compose.onNodeWithText("₿845").assertDoesNotExist()
        compose.onNodeWithText("••••••").assertExists()
    }

    @Test
    fun aSingleAccountBrandShowsNoAccounts() {
        val main = AccountSummary(Account("a", "Main", 0, "mint.example"), null, 0)
        show(HomeState(accounts = listOf(main), activeAccountId = "a", showAccounts = false))
        compose.onNodeWithText("Main").assertDoesNotExist()
    }

    @Test
    fun theSwitcherOffersNoNewAccountAtTheLimit() {
        val main = AccountSummary(Account("a", "Main", 0, "mint.example"), null, 0)
        val savings = AccountSummary(Account("b", "Savings", 1, "mint.example"), null, 0)
        show(HomeState(accounts = listOf(main, savings), activeAccountId = "a", canAddAccount = false))
        compose.onNodeWithText("Main").performClick()
        compose.onNodeWithText("Savings").assertExists()
        compose.onNodeWithText("Add account").assertDoesNotExist()
    }

    @Test
    fun aChangedSigningKeyLeadsToTheDetails() {
        var opened: String? = null
        show(HomeState(keyChangedHost = "mint.example"), onOpenService = { opened = it })
        compose.onNodeWithText("mint.example changed its signing key").assertExists()
        compose.onNodeWithText("Details").performClick()
        assertEquals("mint.example", opened)
    }

    private fun combined(feeMsat: Long) = Activity("op:1", "a", ActivityKind.Combined, 1_701_000, feeMsat, "mint.example", null, true, 2,
        automatic = true, status = ActivityStatus.Done, journalId = "1", createdAtMillis = 0)

    @Test
    fun aFeeTheMintPaidBackShowsAsARefund() {
        show(HomeState(activity = listOf(combined(-1_000))))
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Show note maintenance").performClick()
        compose.onNodeWithText("Fee refunded ₿1", substring = true).assertExists()
        compose.onNodeWithText("Fee ₿", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun aRefundInGerman() {
        show(HomeState(activity = listOf(combined(-1_000))))
        compose.onNodeWithText("Verlauf").performClick()
        compose.onNodeWithText("Pflege der Scheine anzeigen").performClick()
        compose.onNodeWithText("Gebühr erstattet 1 ₿", substring = true).assertExists()
    }
}
