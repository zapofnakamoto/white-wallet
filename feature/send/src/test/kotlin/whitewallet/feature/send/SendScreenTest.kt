package whitewallet.feature.send

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
import whitewallet.core.wallet.PayDestination
import whitewallet.core.wallet.PayPreview
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.PayResult
import whitewallet.lnurl.Bolt11Invoice

@RunWith(RobolectricTestRunner::class)
class SendScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun actions(onCreateNote: () -> Unit = {}) = SendActions({}, {}, {}, {}, {}, {}, {}, {}, {}, onCreateNote)

    @Test
    fun aBalanceThatCannotBePaidAtOnceIsSaidBeforeAnythingIsEntered() {
        compose.setContent {
            SendScreen(SendState(balanceMsat = 8_000_000, limitMsat = 6_000_000), onBack = {}, onDone = {}, actions = actions())
        }
        compose.onNodeWithText(text(R.string.send_limit_one_payment, "₿6,000", "₿8,000")).assertExists()
    }

    @Test
    fun aBalanceThatCanBePaidAtOnceSaysNothingMore() {
        compose.setContent { SendScreen(SendState(balanceMsat = 8_000_000), onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText(text(R.string.send_limit_one_payment, "₿8,000", "₿8,000")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.send_continue)).assertExists()
    }

    @Test
    fun anAmountlessInvoiceIsRefusedWithAWayOut() {
        compose.setContent {
            SendScreen(SendState(input = "lnbc1…", problem = PayProblem.NoAmount), onBack = {}, onDone = {}, actions = actions())
        }
        compose.onNodeWithText("This invoice has no amount.", substring = true).assertExists()
    }

    @Test
    fun aNoteCanBeCreatedInstead() {
        var opened = false
        compose.setContent { SendScreen(SendState(), onBack = {}, onDone = {}, actions = actions { opened = true }) }
        compose.onNodeWithText(text(R.string.send_create_note)).performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun aPaymentThatWentOutGetsTheSuccessScreen() {
        var more = false
        val paid = SendState(result = PayResult.Paid(amountMsat = 1_000_000, feeMsat = 1_000))
        compose.setContent {
            SendScreen(
                paid,
                onBack = {},
                onDone = {},
                actions = SendActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onSendMore = { more = true }),
            )
        }
        compose.onNodeWithText(text(R.string.send_paid_title)).assertExists()
        compose.onNodeWithText("₿1,000").assertExists()
        compose.onNodeWithText(text(R.string.send_fee, "₿1")).assertExists()
        compose.onNodeWithText(text(R.string.send_more)).performScrollTo().performClick()
        assertTrue(more)
    }

    private val payLink =
        PayPreview.Ready(
            PayDestination.PayLink("https://mint.example/p", "https://mint.example/cb", "[]", 1_000, 1_000_000, 0, null),
            fixedAmountMsat = null,
            minMsat = 1_000,
            maxMsat = 1_000_000,
            commentMaxLength = 0,
            payee = "bob@mint.example",
            description = null,
            availableMsat = 1_000_000,
        )

    @Test
    fun aRefusalTheWalletDoesNotKnowShowsTheMintsWordsAsTheMints() {
        val failed = PayResult.Failed(PayProblem.Refused, "Closed for maintenance.")
        compose.setContent { SendScreen(SendState(preview = payLink, result = failed), onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText(text(R.string.send_problem_refused)).assertExists()
        compose.onNodeWithText(text(R.string.send_problem_mint_message, "Closed for maintenance.")).assertExists()
    }

    @Test
    fun aRefusalTheWalletKnowsIsToldInItsOwnWordsOnly() {
        val failed = PayResult.Failed(PayProblem.OwnMintInvoice)
        compose.setContent { SendScreen(SendState(preview = payLink, result = failed), onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText(text(R.string.send_problem_own_mint_invoice)).assertExists()
        compose.onNodeWithText(text(R.string.send_problem_mint_message, ""), substring = true).assertDoesNotExist()
    }

    @Test
    fun onlyAPayLinkOffersToTryAgainAfterAFailedPayment() {
        val invoice = Bolt11Invoice("lnbc1…", "bc", 1_000_000, 0, "00", null, null, 3600, null)
        var preview by mutableStateOf(payLink)
        val failed = PayResult.Failed(PayProblem.PaymentFailed)
        compose.setContent { SendScreen(SendState(preview = preview, result = failed), onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText(text(R.string.send_try_again)).assertExists()
        preview = payLink.copy(destination = PayDestination.Invoice(invoice))
        compose.onNodeWithText(text(R.string.send_try_again)).assertDoesNotExist()
    }

    @Test
    fun untilTheFaucetIsKnownNothingIsDrawn() {
        compose.setContent { SendScreen(SendState(loaded = false), onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText(text(R.string.send_continue)).assertDoesNotExist()
    }

    @Test
    fun aNotesFormIsNotDrawnBeforeWhatCanBeSentIsRead() {
        compose.setContent { CreateNoteScreen(CreateNoteState(), onBack = {}, onAmountChange = {}, onCreate = {}) }
        compose.onNodeWithText(text(R.string.note_bearer_warning)).assertDoesNotExist()
    }
}
