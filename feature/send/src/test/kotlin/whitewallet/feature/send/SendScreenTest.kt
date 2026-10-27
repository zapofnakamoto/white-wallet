package whitewallet.feature.send

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.PayResult

@RunWith(RobolectricTestRunner::class)
class SendScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun actions(onCreateNote: () -> Unit = {}) = SendActions({}, {}, {}, {}, {}, {}, {}, {}, {}, onCreateNote)

    @Test
    fun anAmountlessInvoiceIsRefusedWithAWayOut() {
        compose.setContent { SendScreen(SendState(input = "lnbc1…", problem = PayProblem.NoAmount), onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText("This invoice has no amount.", substring = true).assertExists()
    }

    @Test
    fun aNoteCanBeCreatedInstead() {
        var opened = false
        compose.setContent { SendScreen(SendState(), onBack = {}, onDone = {}, actions = actions { opened = true }) }
        compose.onNodeWithText("Create a note").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun aPaymentThatWentOutGetsTheSuccessScreen() {
        var more = false
        val paid = SendState(result = PayResult.Paid(amountMsat = 1_000_000, feeMsat = 1_000))
        compose.setContent { SendScreen(paid, onBack = {}, onDone = {}, actions = SendActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onSendMore = { more = true })) }
        compose.onNodeWithText("Paid").assertExists()
        compose.onNodeWithText("₿1,000").assertExists()
        compose.onNodeWithText("Fee: ₿1").assertExists()
        compose.onNodeWithText("Send more").performScrollTo().performClick()
        assertTrue(more)
    }
}
