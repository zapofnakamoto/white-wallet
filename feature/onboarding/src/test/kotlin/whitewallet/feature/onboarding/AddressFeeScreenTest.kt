package whitewallet.feature.onboarding

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.model.RegisteredAddress

/** An address on a mint that charges for one: the fee on the form, the question, the reserved name and paying for it. */
@RunWith(RobolectricTestRunner::class)
class AddressFeeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val fee = 21_000L
    private var accepted = 0
    private var declined = 0
    private var paid = 0
    private var continued = 0

    /** [fee] and a larger amount as the screen writes them. */
    private lateinit var amount: String
    private lateinit var larger: String

    private fun show(state: OnboardingState) = compose.setContent {
        amount = formatAmount(fee)
        larger = formatAmount(50_000)
        OnboardingScreen(
            state,
            onBack = null,
            onUsernameChange = {},
            mint = MintActions({}, {}),
            onSuggestion = {},
            onStart = {},
            onContinue = { continued++ },
            fee = FeeActions({ accepted++ }, { declined++ }, { paid++ }),
        )
    }

    private val setup =
        OnboardingState(
            username = "alice",
            host = "mint.example",
            canChangeHost = false,
            mode = OnboardingMode.Setup,
            canSkipAddress = true,
        )
    private val fromSettings = setup.copy(mode = OnboardingMode.AddAddress, canSkipAddress = false)
    private val held = RegisteredAddress("alice", "mint.example", addedAtMillis = 0, share = 0, feeInvoice = "lnbcrt210n1fee")

    private fun theFormSaysTheFeeBeforeTheNameIsAskedFor() {
        show(setup.copy(feeMsat = fee, availability = Availability.Available))
        compose.onNodeWithText(text(R.string.address_fee_notice, amount)).assertExists()
        compose.onNodeWithText(text(R.string.setup_address_submit)).assertExists()
    }

    @Test fun theFormSaysTheFeeBeforeTheNameIsAskedForInEnglish() = theFormSaysTheFeeBeforeTheNameIsAskedFor()

    @Test @Config(qualifiers = "de") fun theFormSaysTheFeeBeforeTheNameIsAskedForInGerman() = theFormSaysTheFeeBeforeTheNameIsAskedFor()

    @Test
    fun aMintWithoutAFeeSaysNothingAboutOne() {
        show(setup.copy(availability = Availability.Available))
        compose.onNodeWithText(text(R.string.address_fee_notice, amount)).assertDoesNotExist()
    }

    @Test
    fun aNameTheWalletOwnsAlreadyCostsNothingAgain() {
        show(setup.copy(feeMsat = fee, availability = Availability.Yours))
        compose.onNodeWithText(text(R.string.address_fee_notice, amount)).assertDoesNotExist()
    }

    @Test
    fun inTheSetupTheFeeIsAcceptedOrTheAddressSkipped() {
        show(setup.copy(feeMsat = fee, feePrompt = fee))
        compose.onNodeWithText(text(R.string.address_fee_title)).assertExists()
        compose.onNodeWithText(text(R.string.address_fee_body, amount)).assertExists()
        compose.onNodeWithText(text(R.string.address_fee_back)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.address_fee_reserve)).performClick()
        compose.onNodeWithText(text(R.string.address_fee_skip)).performClick()
        assertEquals(1 to 1, accepted to declined)
    }

    @Test
    fun fromSettingsDecliningTheFeeGoesBackToTheForm() {
        show(fromSettings.copy(feeMsat = fee, feePrompt = fee))
        compose.onNodeWithText(text(R.string.address_fee_skip)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.address_fee_back)).performClick()
        assertEquals(1, declined)
    }

    private fun aReservedNameShowsItsInvoiceAndCanWait() {
        show(setup.copy(held = HeldAddress(held, fee, canPayFromBalance = false)))
        compose.onNodeWithText(text(R.string.address_held_title)).assertExists()
        compose.onNodeWithText("alice@mint.example").assertExists()
        compose.onNodeWithText(text(R.string.address_held_body, amount)).assertExists()
        compose.onNodeWithText(text(R.string.address_held_copy)).assertExists()
        // A new wallet holds nothing to pay with.
        compose.onNodeWithText(text(R.string.address_held_pay_balance)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.address_held_later)).performScrollTo().performClick()
        assertEquals(1, continued)
    }

    @Test fun aReservedNameShowsItsInvoiceAndCanWaitInEnglish() = aReservedNameShowsItsInvoiceAndCanWait()

    @Test @Config(qualifiers = "de") fun aReservedNameShowsItsInvoiceAndCanWaitInGerman() = aReservedNameShowsItsInvoiceAndCanWait()

    @Test
    fun anAccountThatHoldsEnoughPaysTheFeeFromItsBalance() {
        show(fromSettings.copy(held = HeldAddress(held, fee, canPayFromBalance = true)))
        compose.onNodeWithText(text(R.string.address_held_pay_balance)).performScrollTo().performClick()
        assertEquals(1, paid)
    }

    @Test
    fun aFeeThatCouldNotBePaidSaysTheNameStaysReserved() {
        show(fromSettings.copy(held = HeldAddress(held, fee, canPayFromBalance = true), error = OnboardingError.FeeNotPaid()))
        compose.onNodeWithText(text(R.string.onboarding_error_fee_not_paid)).assertExists()
        compose.onNodeWithText(text(R.string.address_held_title)).assertExists()
    }

    @Test
    fun aReservationThatRanOutLeadsBackToTheForm() {
        show(setup.copy(feeMsat = fee, error = OnboardingError.HoldExpired()))
        compose.onNodeWithText(text(R.string.onboarding_error_hold_expired)).assertExists()
        compose.onNodeWithText(text(R.string.setup_address_submit)).assertExists()
    }

    @Test
    fun aFeeAboveWhatTheAppAcceptsIsRefusedWithBothAmounts() {
        show(setup.copy(feeMsat = 50_000, error = OnboardingError.FeeTooHigh(50_000, fee)))
        compose.onNodeWithText(text(R.string.onboarding_error_fee, larger, amount)).assertExists()
    }
}
