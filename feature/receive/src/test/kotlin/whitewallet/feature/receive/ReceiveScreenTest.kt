package whitewallet.feature.receive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.wallet.InvoiceRequest
import whitewallet.core.wallet.InvoiceStatus

@RunWith(RobolectricTestRunner::class)
class ReceiveScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsTheAddressAndWhatArrived() {
        compose.setContent { ReceiveScreen(ReceiveState("alice@mint.example", receivedMsat = 21_000), onBack = {}, onClaim = {}) }
        compose.onNodeWithText("alice@mint.example").assertExists()
        compose.onNodeWithText("Received ₿21").assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun german() {
        compose.setContent { ReceiveScreen(ReceiveState("alice@mint.example", receivedMsat = 21_000), onBack = {}, onClaim = {}) }
        compose.onNodeWithText("21 ₿ empfangen").assertExists()
    }

    @Test
    fun withoutAnAddressItAsksForAnAmount() {
        var created = false
        compose.setContent { ReceiveScreen(ReceiveState(address = null, amountText = "2100"), onBack = {}, onClaim = {}, InvoiceActions(onCreate = { created = true })) }
        compose.onNodeWithText("Create invoice").performScrollTo().performClick()
        assertTrue(created)
    }

    @Test
    fun anAddressCanStillRequestAnAmount() {
        var asked = false
        compose.setContent { ReceiveScreen(ReceiveState("alice@mint.example"), onBack = {}, onClaim = {}, InvoiceActions(onRequestAmount = { asked = true })) }
        compose.onNodeWithText("Request a specific amount").performScrollTo().performClick()
        assertTrue(asked)
    }

    @Test
    fun anInvoiceShowsWhatArrivesAndThenThatItWasPaid() {
        val request = InvoiceRequest.Ready("i", "lnbcrt21u1test", 21_000, 20_000, 0)
        var state by mutableStateOf(ReceiveState(invoice = ShownInvoice(request)))
        compose.setContent { ReceiveScreen(state, onBack = {}, onClaim = {}) }
        compose.onNodeWithText("You receive ₿20 after a fee of ₿1.").assertExists()
        compose.onNodeWithText("Waiting for payment…").assertExists()
        state = state.copy(invoice = ShownInvoice(request, InvoiceStatus.Paid(20_000)))
        compose.onNodeWithText("Received ₿20").assertExists()
        compose.onNodeWithText("Waiting for payment…").assertDoesNotExist()
    }
}
