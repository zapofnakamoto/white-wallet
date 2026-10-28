package whitewallet.feature.home

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
    @get:Rule
    val compose = createComposeRule()

    private fun summary(id: String, name: String, balanceMsat: Long = 0) = AccountSummary(Account(id, name, 0, "mint.example"), null, balanceMsat)

    @Test
    fun bothAccountsCarryTheirMarksAndTheTargetIsARadioChoice() {
        var target: String? = null
        val state = MoveFundsState(
            from = summary("a", "Main", 5_000),
            targets = listOf(summary("b", "Savings"), summary("c", "Daily")),
            places = mapOf("a" to 0, "b" to 1, "c" to 2),
            targetId = "b",
        )
        compose.setContent { MoveFundsScreen(state, onBack = {}, onTarget = { target = it }, onEverything = {}, onAmount = {}, onMove = {}) }
        listOf("M", "S", "D").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNode(hasText("Savings")).assertIsSelected()
        compose.onNodeWithText("Daily").performClick()
        assertEquals("c", target)
    }
}
