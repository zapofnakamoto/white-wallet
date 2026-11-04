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
    fun showsTheAddress() {
        compose.setContent { ReceiveScreen(ReceiveState("alice@mint.example"), onBack = {}, onClaim = {}) }
        compose.onNodeWithText("alice@mint.example").assertExists()
    }

    @Test
    fun whatArrivesGetsItsOwnScreen() {
        var more = false
        compose.setContent {
            ReceiveScreen(ReceiveState("alice@mint.example", receivedMsat = 21_000), onBack = {}, onClaim = {}, InvoiceActions(onReceiveMore = { more = true }))
        }
        compose.onNodeWithText(text(R.string.receive_success_title)).assertExists()
        compose.onNodeWithText("₿21").assertExists()
        compose.onNodeWithText("alice@mint.example").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.receive_more)).performScrollTo().performClick()
        assertTrue(more)
    }

    @Test
    @Config(qualifiers = "de")
    fun german() {
        compose.setContent { ReceiveScreen(ReceiveState("alice@mint.example", receivedMsat = 21_000), onBack = {}, onClaim = {}) }
        compose.onNodeWithText("Zahlung erhalten").assertExists()
    }

    @Test
    fun withoutAnAddressItAsksForAnAmount() {
        var created = false
        compose.setContent { ReceiveScreen(ReceiveState(address = null, amountText = "2100"), onBack = {}, onClaim = {}, InvoiceActions(onCreate = { created = true })) }
        compose.onNodeWithText(text(R.string.receive_invoice_create)).performScrollTo().performClick()
        assertTrue(created)
    }

    @Test
    fun anAddressCanStillRequestAnAmount() {
        var asked = false
        compose.setContent { ReceiveScreen(ReceiveState("alice@mint.example"), onBack = {}, onClaim = {}, InvoiceActions(onRequestAmount = { asked = true })) }
        compose.onNodeWithText(text(R.string.receive_request_amount)).performScrollTo().performClick()
        assertTrue(asked)
    }

    @Test
    fun anInvoiceShowsWhatArrivesAndThenThatItWasPaid() {
        val request = InvoiceRequest.Ready("i", "lnbcrt21u1test", 21_000, 20_000, 0)
        var state by mutableStateOf(ReceiveState(invoice = ShownInvoice(request)))
        compose.setContent { ReceiveScreen(state, onBack = {}, onClaim = {}) }
        compose.onNodeWithText(text(R.string.receive_invoice_fee, "₿20", "₿1")).assertExists()
        compose.onNodeWithText(text(R.string.receive_invoice_waiting)).assertExists()
        state = state.copy(invoice = ShownInvoice(request, InvoiceStatus.Paid(20_000)))
        compose.onNodeWithText(text(R.string.receive_success_title)).assertExists()
        compose.onNodeWithText("₿20").assertExists()
        compose.onNodeWithText(text(R.string.receive_invoice_waiting)).assertDoesNotExist()
    }
}
