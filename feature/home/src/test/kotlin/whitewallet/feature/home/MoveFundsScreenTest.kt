package whitewallet.feature.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary

@RunWith(RobolectricTestRunner::class)
class MoveFundsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun summary(id: String, name: String, balanceMsat: Long = 0, walletId: String = "w1") =
        AccountSummary(Account(id, name, 0, walletId = walletId), balanceMsat)

    @Test
    fun bothAccountsCarryTheirMarksAndTheTargetIsARadioChoice() {
        var target: String? = null
        val state =
            MoveFundsState(
                from = summary("a", "Main", 5_000),
                targets = listOf(summary("b", "Savings"), summary("c", "Daily")),
                places = mapOf("a" to 0, "b" to 1, "c" to 2),
                targetId = "b",
            )
        compose.setContent {
            MoveFundsScreen(state, onBack = {}, onTarget = { target = it }, onEverything = {}, onAmount = {}, onMove = {})
        }
        listOf("M", "S", "D").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNode(hasText("Savings")).assertIsSelected()
        compose.onNodeWithText("Daily").performClick()
        assertEquals("c", target)
    }

    @Test
    fun withSeveralWalletsEveryAccountIsShownUnderItsWallet() {
        val state =
            MoveFundsState(
                from = summary("a", "Main", 5_000, walletId = "w2"),
                targets = listOf(summary("b", "Main"), summary("c", "Savings", walletId = "w2")),
                places = mapOf("b" to 0, "a" to 1, "c" to 2),
                walletNumbers = mapOf("w1" to 1, "w2" to 2),
                walletNames = mapOf("w1" to "Travel"),
            )
        compose.setContent { MoveFundsScreen(state, onBack = {}, onTarget = {}, onEverything = {}, onAmount = {}, onMove = {}) }
        // The same account name in two wallets: the wallet says which is which, by the user's name for it or its number.
        compose.onNodeWithText("Wallet 2 · Main", substring = true).assertExists()
        // As in the account switcher, the other wallets stay folded away until asked for.
        compose.onNodeWithText("Travel").assertDoesNotExist()
        compose.onNodeWithText("1 other wallet").performClick()
        compose.onNodeWithText("1 other wallet").assertDoesNotExist()
        val headers = compose.onAllNodes(hasText("Wallet 2") or hasText("Travel")).fetchSemanticsNodes()
        // The wallet the money leaves comes first.
        assertEquals(listOf("Wallet 2", "Travel"), headers.map { it.config[SemanticsProperties.Text].single().text })
    }

    @Test
    fun withOneWalletNoWalletIsNamed() {
        val state =
            MoveFundsState(
                from = summary("a", "Main", 5_000),
                targets = listOf(summary("b", "Savings")),
                places = mapOf("a" to 0, "b" to 1),
                walletNumbers = mapOf("w1" to 1),
            )
        compose.setContent { MoveFundsScreen(state, onBack = {}, onTarget = {}, onEverything = {}, onAmount = {}, onMove = {}) }
        compose.onNodeWithText("Wallet 1", substring = true).assertDoesNotExist()
    }

    @Test
    fun withNoOtherAccountInTheWalletInUseTheOtherWalletsAreOpen() {
        val state =
            MoveFundsState(
                from = summary("a", "Main", 5_000),
                targets = listOf(summary("b", "Main", walletId = "w2")),
                places = mapOf("a" to 0, "b" to 1),
                walletNumbers = mapOf("w1" to 1, "w2" to 2),
            )
        compose.setContent { MoveFundsScreen(state, onBack = {}, onTarget = {}, onEverything = {}, onAmount = {}, onMove = {}) }
        compose.onNodeWithText("Wallet 2").assertExists()
        compose.onNodeWithText("1 other wallet").assertDoesNotExist()
    }

    @Test
    fun aMintTheMoveAddsToTheOtherWalletIsNamedBeforeMoving() {
        val state =
            MoveFundsState(
                from = summary("a", "Main", 5_000),
                targets = listOf(summary("b", "Main", walletId = "w2")),
                places = mapOf("a" to 0, "b" to 1),
                targetId = "b",
                walletNumbers = mapOf("w1" to 1, "w2" to 2),
                walletNames = mapOf("w2" to "Travel"),
                addsMints = listOf("mint.example"),
            )
        compose.setContent { MoveFundsScreen(state, onBack = {}, onTarget = {}, onEverything = {}, onAmount = {}, onMove = {}) }
        compose.onNodeWithText("mint.example will be added to the mints of Travel.").assertExists()
    }
}
