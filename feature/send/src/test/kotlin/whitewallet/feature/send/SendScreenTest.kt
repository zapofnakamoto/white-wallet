package whitewallet.feature.send

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.wallet.PayDestination
import whitewallet.core.wallet.PayPreview
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.PayQuote
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
        compose.onNodeWithText(text(R.string.send_more)).performClick()
        assertTrue(more)
    }

    private val payLink =
        PayPreview.Ready(
            PayDestination.PayLink("https://mint.example/p", "https://mint.example/cb", "[]", 1_000, 1_000_000, 0, false),
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
    fun offlineSendSaysSoBeforeTheUserTriesAndKeepsContinueForNoteLinks() {
        var state by mutableStateOf(SendState(input = "lnbc1…", offline = true, problem = PayProblem.Unreachable))
        compose.setContent { SendScreen(state, onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText(text(R.string.send_offline)).assertExists()
        compose.onNodeWithText(text(R.string.send_problem_unreachable)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.send_continue)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.send_create_note)).assertIsNotEnabled()
        state = state.copy(offline = false)
        compose.onNodeWithText(text(R.string.send_offline)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.send_create_note)).assertIsEnabled()
    }

    @Test
    fun offlineNothingCanBePaid() {
        val state = SendState(preview = payLink, amountText = "1", quote = PayQuote.Ok(1_000, 0, "mint.example"), offline = true)
        compose.setContent { SendScreen(state, onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText(text(R.string.send_offline)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.send_pay_amount, "₿1")).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aMintWhoseSigningKeyDoesNotMatchIsSaidSoNotThatItCouldNotBeReached() {
        val failed = PayResult.Failed(PayProblem.KeyChanged)
        compose.setContent { SendScreen(SendState(preview = payLink, result = failed), onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText(text(R.string.send_problem_key_changed)).assertExists()
        compose.onNodeWithText(text(R.string.send_problem_unreachable)).assertDoesNotExist()
    }

    @Test
    fun aMintThatDidNotAnswerIsNamedAndAnInvoiceNothingWasAskedToPayCanBeTriedAgain() {
        val invoice = Bolt11Invoice("lnbc1…", "bc", 1_000_000, 0, "00", null, null, 3600, null)
        val failed = PayResult.Failed(PayProblem.MintNotAnswering, host = "home.example")
        var state by mutableStateOf(SendState(preview = payLink.copy(destination = PayDestination.Invoice(invoice)), result = failed))
        compose.setContent { SendScreen(state, onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText(text(R.string.send_problem_mint_silent, "home.example")).assertExists()
        compose.onNodeWithText(text(R.string.send_try_again)).assertExists()
        state = state.copy(mintsVisible = false)
        compose.onNodeWithText(text(R.string.send_problem_mint_silent_unnamed)).assertExists()
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
    fun aPaymentStillOnItsWayEndsOnTheResultScreen() {
        var done = 0
        val pending = PayResult.Pending(1_000_000)
        compose.setContent {
            SendScreen(SendState(preview = payLink, result = pending), onBack = {}, onDone = { done++ }, actions = actions())
        }
        compose.onNodeWithText(text(R.string.send_pending_title)).assertExists()
        compose.onNodeWithText(text(R.string.send_pending_body, "₿1,000", "bob@mint.example")).assertExists()
        compose.onNodeWithText(text(R.string.send_done)).performClick()
        assertEquals(1, done)
    }

    @Test
    fun theBrandSeesAPaymentThatIsNotPaidOrStillOnItsWayAtItsResult() {
        val seen = mutableListOf<Pair<Anchor, WalletStatus>>()
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    seen += anchor to context.status
                }
            }
        var result by mutableStateOf<PayResult>(PayResult.Failed(PayProblem.PaymentFailed))
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design) {
                SendScreen(SendState(preview = payLink, result = result), onBack = {}, onDone = {}, actions = actions())
            }
        }
        compose.onNodeWithText(text(R.string.send_failed_title)).assertExists()
        assertEquals(listOf(Anchor.SendResult to WalletStatus.Problem(ProblemKind.Other)), seen.distinct())
        seen.clear()
        result = PayResult.Pending(1_000_000)
        compose.waitForIdle()
        assertEquals(listOf(Anchor.SendResult to WalletStatus.Sending), seen.distinct())
    }

    @Test
    fun untilTheFaucetIsKnownNothingIsDrawn() {
        compose.setContent { SendScreen(SendState(loaded = false), onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText(text(R.string.send_continue)).assertDoesNotExist()
    }

    @Test
    fun aNoteWithoutAnAnswerWaitsOnTheResultScreenAndCanBeTriedAgain() {
        var again = 0
        compose.setContent {
            CreateNoteScreen(
                CreateNoteState(availableMsat = 5_000_000, amountText = "1000", pending = true),
                onBack = {},
                onAmountChange = {},
                onCreate = {},
                onTryAgain = { again++ },
            )
        }
        compose.onNodeWithText(text(DesignR.string.no_answer_title)).assertExists()
        compose.onNodeWithText(text(R.string.note_create_pending)).assertExists()
        compose.onNodeWithText(text(R.string.note_create)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.send_try_again)).performClick()
        assertEquals(1, again)
    }

    @Test
    fun aNoteTheMintRefusedSaysNothingWasHandedOut() {
        var back = 0
        compose.setContent {
            CreateNoteScreen(
                CreateNoteState(availableMsat = 5_000_000, amountText = "1000", problem = PayProblem.Refused),
                onBack = { back++ },
                onAmountChange = {},
                onCreate = {},
            )
        }
        compose.onNodeWithText(text(R.string.note_create_failed_title)).assertExists()
        compose.onNodeWithText(text(R.string.send_problem_refused)).assertExists()
        compose.onNodeWithText(text(R.string.send_done)).performClick()
        assertEquals(1, back)
    }

    @Test
    fun offlineANoteKeepsItsFormAndSaysSo() {
        compose.setContent {
            CreateNoteScreen(
                CreateNoteState(availableMsat = 5_000_000, offline = true, problem = PayProblem.Unreachable),
                onBack = {},
                onAmountChange = {},
                onCreate = {},
            )
        }
        compose.onNodeWithText(text(R.string.send_offline)).assertExists()
        compose.onNodeWithText(text(R.string.note_create_failed_title)).assertDoesNotExist()
    }

    @Test
    fun aNotesFormIsNotDrawnBeforeWhatCanBeSentIsRead() {
        compose.setContent { CreateNoteScreen(CreateNoteState(), onBack = {}, onAmountChange = {}, onCreate = {}) }
        compose.onNodeWithText(text(R.string.note_bearer_warning)).assertDoesNotExist()
    }

    private val mints = listOf(SendMint("home.example", isHome = true, maxMsat = 3_000_000), SendMint("other.example", false, 9_000_000))

    @Test
    fun openedForAMintItIsPaidFromThereAndAnotherCanBePicked() {
        val picked = mutableListOf<String?>()
        compose.setContent {
            SendScreen(
                SendState(from = "other.example", mints = mints),
                onBack = {},
                onDone = {},
                actions = SendActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onPayFrom = { picked += it }),
            )
        }
        compose.onNodeWithText(text(R.string.send_from_up_to, "other.example", "₿9,000")).assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_choice_change)).performClick()
        compose.onNodeWithText(text(R.string.send_from_detail_home, "₿3,000")).assertExists()
        // Back to the wallet's choice.
        compose.onNodeWithText(text(R.string.send_from_automatic)).performClick()
        assertEquals(listOf(null), picked)
    }

    @Test
    fun theReviewSaysWhichMintPaysAndOneWithTooLittleCannotBePicked() {
        val picked = mutableListOf<String?>()
        val state = SendState(preview = payLink, amountText = "5000", quote = PayQuote.Ok(5_000_000, 0, "other.example"), mints = mints)
        compose.setContent {
            SendScreen(
                state,
                onBack = {},
                onDone = {},
                actions = SendActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onPayFrom = { picked += it }),
            )
        }
        compose.onNodeWithText(text(R.string.send_from, "other.example")).performScrollTo().assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_choice_change)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.send_from_not_enough, "₿3,000")).assertIsNotEnabled()
        compose.onNodeWithText("home.example").performClick()
        assertEquals(emptyList<String?>(), picked)
    }

    @Test
    fun aMintWhoseKeyChangedIsListedButCannotBePicked() {
        val picked = mutableListOf<String?>()
        compose.setContent {
            SendScreen(
                SendState(from = "other.example", mints = mints + SendMint("third.example", false, 1_000_000, keyChanged = true)),
                onBack = {},
                onDone = {},
                actions = SendActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onPayFrom = { picked += it }),
            )
        }
        compose.onNodeWithText(text(DesignR.string.mint_choice_change)).performClick()
        compose.onNodeWithText(text(R.string.send_from_key_changed)).assertIsNotEnabled()
        compose.onNodeWithText("third.example").performClick()
        assertEquals(emptyList<String?>(), picked)
    }

    private val stuck = SendMint("stuck.example", false, 298_000, keyChanged = true)

    @Test
    fun moneyOnAMintWhoseKeyChangedIsSaidApartFromTheMostOneMintCanSendWithAWayOut() {
        val opened = mutableListOf<String>()
        val state = SendState(mints = mints + stuck, balanceMsat = 12_298_000, limitMsat = 9_000_000)
        compose.setContent {
            SendScreen(
                state,
                onBack = {},
                onDone = {},
                actions = SendActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onOpenMint = { opened += it }),
            )
        }
        compose.onNodeWithText(text(R.string.send_available, "₿9,000")).assertExists()
        compose.onNodeWithText(text(R.string.send_limit_one_payment, "₿9,000", "₿12,298")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.send_key_changed_held, "₿298", "stuck.example")).assertExists()
        compose.onNodeWithText(text(R.string.send_input)).assertExists()
        compose.onNodeWithText(text(R.string.send_key_changed_action)).performClick()
        assertEquals(listOf("stuck.example"), opened)
    }

    @Test
    fun withMoneyOnlyOnAMintWhoseKeyChangedThatIsAllTheFormSays() {
        compose.setContent {
            SendScreen(SendState(mints = listOf(stuck), balanceMsat = 298_000), onBack = {}, onDone = {}, actions = actions())
        }
        compose.onNodeWithText(text(R.string.send_key_changed_held, "₿298", "stuck.example")).assertExists()
        compose.onNodeWithText(text(R.string.send_key_changed_action)).assertExists()
        compose.onNodeWithText(text(R.string.send_input)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.send_create_note)).assertDoesNotExist()
    }

    @Test
    fun whereTheBrandDoesNotNameMintsOneSentenceSaysWhatCannotBeSent() {
        val state = SendState(mints = mints + stuck + stuck.copy(host = "also.example"), mintsVisible = false)
        compose.setContent { SendScreen(state, onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText(text(R.string.send_key_changed_held_unnamed, "₿596")).assertExists()
        compose.onNodeWithText(text(R.string.send_key_changed_action)).assertDoesNotExist()
    }

    @Test
    fun aNoteCannotBeMadeFromAMintWhoseKeyChangedAndTheFormSaysWhy() {
        val opened = mutableListOf<String>()
        compose.setContent {
            CreateNoteScreen(
                CreateNoteState(availableMsat = 0, mints = listOf(stuck)),
                onBack = {},
                onAmountChange = {},
                onCreate = {},
                onOpenMint = { opened += it },
            )
        }
        compose.onNodeWithText(text(R.string.send_key_changed_held, "₿298", "stuck.example")).assertExists()
        compose.onNodeWithText(text(R.string.note_create)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.send_key_changed_action)).performClick()
        assertEquals(listOf("stuck.example"), opened)
    }

    @Test
    fun aNoteFromAnotherMintCanStillBeMadeBesideWhatIsStuck() {
        compose.setContent {
            CreateNoteScreen(
                CreateNoteState(availableMsat = 9_000_000, mints = mints + stuck),
                onBack = {},
                onAmountChange = {},
                onCreate = {},
            )
        }
        compose.onNodeWithText(text(R.string.send_key_changed_held, "₿298", "stuck.example")).assertExists()
        compose.onNodeWithText(text(R.string.note_available, "₿9,000")).assertExists()
    }

    @Test
    fun withMoneyOnOneMintNothingIsSaidAboutMints() {
        val state = SendState(preview = payLink, amountText = "1", quote = PayQuote.Ok(1_000, 0, "home.example"), mints = mints.take(1))
        compose.setContent { SendScreen(state, onBack = {}, onDone = {}, actions = actions()) }
        compose.onNodeWithText(text(R.string.send_from, "home.example")).assertDoesNotExist()
    }
}
