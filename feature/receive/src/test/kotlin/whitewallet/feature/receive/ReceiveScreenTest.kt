package whitewallet.feature.receive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.designsystem.MINT_KEY_WARNING_TAG
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.wallet.InvoiceRequest
import whitewallet.core.wallet.InvoiceStatus
import whitewallet.core.wallet.NoHome

@RunWith(RobolectricTestRunner::class)
class ReceiveScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun withoutAHomeMintItSaysWhatIsMissing() {
        val asked = mutableListOf<NoHome>()
        compose.setContent { ReceiveScreen(ReceiveState(noHome = NoHome.NoMint), onBack = {}, onClaim = {}, onNoHome = { asked += it }) }
        compose.onNodeWithText(text(R.string.receive_no_mint)).assertExists()
        compose.onNodeWithText(text(R.string.receive_no_mint_action)).performClick()
        assertEquals(listOf(NoHome.NoMint), asked)
    }

    @Test
    fun anAccountWhoseHomeWasRemovedPicksOne() {
        compose.setContent { ReceiveScreen(ReceiveState(noHome = NoHome.PickHome), onBack = {}, onClaim = {}) }
        compose.onNodeWithText(text(R.string.receive_no_home_action)).assertExists()
        compose.onNodeWithText(text(R.string.receive_no_mint_action)).assertDoesNotExist()
    }

    @Test
    fun showsTheAddress() {
        compose.setContent { ReceiveScreen(ReceiveState("alice@mint.example"), onBack = {}, onClaim = {}) }
        compose.onNodeWithText("alice@mint.example").assertExists()
    }

    @Test
    fun whatArrivesGetsItsOwnScreen() {
        var more = false
        compose.setContent {
            ReceiveScreen(
                ReceiveState("alice@mint.example", receivedMsat = 21_000),
                onBack = {},
                onClaim = {},
                InvoiceActions(onReceiveMore = { more = true }),
            )
        }
        compose.onNodeWithText(text(R.string.receive_success_title)).assertExists()
        compose.onNodeWithText("₿21").assertExists()
        compose.onNodeWithText("alice@mint.example").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.receive_more)).performClick()
        assertTrue(more)
    }

    @Test
    fun severalPaymentsAreCountedInTheTitleAndTheAmountIsTheirTotal() {
        var state by mutableStateOf(ReceiveState("alice@mint.example", receivedMsat = 1_000, receivedPayments = 1))
        compose.setContent { ReceiveScreen(state, onBack = {}, onClaim = {}) }
        compose.onNodeWithText(text(R.string.receive_success_title)).assertExists()
        compose.onNodeWithText("₿1").assertExists()
        state = state.copy(receivedMsat = 3_000, receivedPayments = 2)
        compose.waitForIdle()
        compose.onNodeWithText(plural(R.plurals.receive_payments_received, 2)).assertExists()
        compose.onNodeWithText(text(R.string.receive_success_title)).assertDoesNotExist()
        compose.onNodeWithText("₿3").assertExists()
        state = state.copy(receivedMsat = 6_000, receivedPayments = 3)
        compose.waitForIdle()
        compose.onNodeWithText(plural(R.plurals.receive_payments_received, 3)).assertExists()
        compose.onNodeWithText("₿6").assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun severalPaymentsInGerman() {
        compose.setContent {
            ReceiveScreen(ReceiveState("alice@mint.example", receivedMsat = 3_000, receivedPayments = 2), onBack = {}, onClaim = {})
        }
        compose.onNodeWithText("2 Zahlungen erhalten").assertExists()
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
        compose.setContent {
            ReceiveScreen(
                ReceiveState(address = null, amountText = "2100"),
                onBack = {},
                onClaim = {},
                InvoiceActions(onCreate = { created = true }),
            )
        }
        compose.onNodeWithText(text(R.string.receive_invoice_create)).performScrollTo().performClick()
        assertTrue(created)
    }

    @Test
    fun anAddressCanStillRequestAnAmount() {
        var asked = false
        compose.setContent {
            ReceiveScreen(ReceiveState("alice@mint.example"), onBack = {}, onClaim = {}, InvoiceActions(onRequestAmount = { asked = true }))
        }
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

    @Test
    fun anAddressWhoseMintDoesNotAnswerStillShowsAndSaysPaymentsCannotArrive() {
        var state by mutableStateOf(ReceiveState("alice@mint.example", addressMintNotAnswering = true))
        compose.setContent { ReceiveScreen(state, onBack = {}, onClaim = {}) }
        compose.onNodeWithText("alice@mint.example").assertExists()
        compose.onNodeWithText(text(R.string.receive_address_mint_silent, "mint.example")).assertExists()
        compose.onNodeWithText(text(R.string.receive_hint)).assertDoesNotExist()
        state = state.copy(mintsVisible = false)
        compose.onNodeWithText(text(R.string.receive_address_mint_silent_unnamed)).assertExists()
    }

    @Test
    fun anInvoiceTheHomeMintDidNotAnswerForNamesIt() {
        var state by
            mutableStateOf(ReceiveState(address = null, amountText = "21", problem = InvoiceProblem.MintNotAnswering("home.example")))
        compose.setContent { ReceiveScreen(state, onBack = {}, onClaim = {}) }
        compose.onNodeWithText(text(R.string.receive_invoice_mint_silent, "home.example")).assertExists()
        // No answer over a network not known to reach the internet: nobody is named.
        state = state.copy(problem = InvoiceProblem.NoAnswer)
        compose.onNodeWithText(text(R.string.receive_invoice_refused)).assertExists()
    }

    @Test
    fun anInvoiceOnAMintWhoseKeyChangedSaysSoAndLeadsToItsDetails() {
        val opened = mutableListOf<String>()
        var state by mutableStateOf(ReceiveState(address = null, amountText = "21", problem = InvoiceProblem.KeyChanged("home.example")))
        compose.setContent { ReceiveScreen(state, onBack = {}, onClaim = {}, InvoiceActions(onOpenMint = { opened += it })) }
        compose.onNodeWithText(text(R.string.receive_invoice_key_changed, "home.example")).assertExists()
        compose.onNodeWithText(text(R.string.receive_invoice_refused)).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.details)).performScrollTo().performClick()
        assertEquals(listOf("home.example"), opened)
        // Where the brand hides mints, none is named, and there is no page to go to.
        state = state.copy(mintsVisible = false)
        compose.onNodeWithText(text(R.string.receive_invoice_key_changed_unnamed)).assertExists()
        compose.onNodeWithText(text(DesignR.string.details)).assertDoesNotExist()
    }

    @Test
    fun theFormSaysUpFrontThatTheMintsKeyChangedAndMakesNothingThere() {
        compose.setContent {
            ReceiveState(
                    address = null,
                    amountText = "21",
                    homeHost = "home.example",
                    mints = twoMints.map { it.copy(keyChanged = it.isHome) },
                )
                .let { ReceiveScreen(it, onBack = {}, onClaim = {}) }
        }
        compose.onNodeWithText(text(R.string.receive_invoice_key_changed, "home.example")).assertExists()
        compose.onNodeWithText(text(R.string.receive_invoice_create)).assertIsNotEnabled()
    }

    @Test
    fun whileTheMintsKeyIsCheckedTheFormSaysSoAndMakesNothingYet() {
        compose.setContent {
            ReceiveState(
                    address = null,
                    amountText = "21",
                    homeHost = "home.example",
                    mints = twoMints,
                    keyChecking = setOf("home.example"),
                )
                .let { ReceiveScreen(it, onBack = {}, onClaim = {}) }
        }
        compose.onNodeWithText(text(R.string.receive_key_checking, "home.example")).assertExists()
        // The button keeps its label and takes no tap.
        compose.onNodeWithText(text(R.string.receive_invoice_create)).assertIsNotEnabled()
    }

    @Test
    fun onceTheMintsKeyIsCheckedTheFormCanMakeAnInvoice() {
        compose.setContent {
            ReceiveState(
                    address = null,
                    amountText = "21",
                    homeHost = "home.example",
                    mints = twoMints,
                    keyChecking = setOf("other.example"),
                )
                .let { ReceiveScreen(it, onBack = {}, onClaim = {}) }
        }
        compose.onNodeWithText(text(R.string.receive_key_checking, "home.example")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.receive_invoice_create)).assertIsEnabled()
    }

    @Test
    fun anAddressThatDoesntReachTheWalletSaysSoWithTheWayToFixIt() {
        var opened = 0
        compose.setContent {
            ReceiveScreen(
                ReceiveState("alice@home.example", addressLost = true),
                onBack = {},
                onClaim = {},
                InvoiceActions(onOpenAddresses = { opened++ }),
            )
        }
        // The address's name still shows, with what is wrong; nothing to scan or pass on, since payments to it fail.
        compose.onNodeWithText("alice@home.example").assertExists()
        compose.onNodeWithContentDescription("LIGHTNING:ALICE@HOME.EXAMPLE").assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.copy)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.receive_request_amount)).assertExists()
        compose.onNodeWithText(text(R.string.receive_address_lost)).assertExists()
        compose.onNodeWithText(text(R.string.receive_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.receive_address_fix)).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun anAddressOnAMintWhoseKeyChangedSaysPaymentsWaitThere() {
        val opened = mutableListOf<String>()
        compose.setContent {
            ReceiveScreen(
                ReceiveState("alice@home.example", addressKeyChanged = true, addressMintNotAnswering = true),
                onBack = {},
                onClaim = {},
                InvoiceActions(onOpenMint = { opened += it }),
            )
        }
        compose.onNodeWithText(text(R.string.receive_address_key_changed, "home.example")).assertExists()
        compose.onNodeWithText(text(R.string.receive_address_mint_silent, "home.example")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.receive_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.details)).performScrollTo().performClick()
        assertEquals(listOf("home.example"), opened)
    }

    @Test
    fun offlineTheAmountFormSaysSoBeforeTheUserTriesAndNotOnceBack() {
        var state by mutableStateOf(ReceiveState(address = null, amountText = "21", offline = true))
        compose.setContent { ReceiveScreen(state, onBack = {}, onClaim = {}) }
        compose.onNodeWithText(text(R.string.receive_invoice_offline)).assertExists()
        compose.onNodeWithText(text(R.string.receive_invoice_create)).assertIsNotEnabled()
        // Tried as the connection dropped: back online, it's gone, not an error left over.
        state = state.copy(offline = false, problem = InvoiceProblem.Offline)
        compose.onNodeWithText(text(R.string.receive_invoice_offline)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.receive_invoice_create)).assertIsEnabled()
    }

    @Test
    fun untilTheAddressesAreReadNothingIsDrawnNotTheAmountFormFirst() {
        compose.setContent { ReceiveScreen(ReceiveState(loaded = false), onBack = {}, onClaim = {}) }
        compose.onNodeWithText(text(R.string.receive_claim)).assertDoesNotExist()
    }

    private val twoMints =
        listOf(ReceiveMint("home.example", isHome = true, address = "alice@home.example"), ReceiveMint("other.example", false, null))

    @Test
    fun withSeveralMintsItSaysWhichOneAndAnotherCanBePicked() {
        val picked = mutableListOf<String>()
        compose.setContent {
            ReceiveScreen(
                ReceiveState("alice@home.example", homeHost = "home.example", mints = twoMints),
                onBack = {},
                onClaim = {},
                InvoiceActions(onReceiveOn = { picked += it }),
            )
        }
        compose.onNodeWithText(text(R.string.receive_on_home, "home.example")).assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_choice_change)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.receive_on_title)).assertExists()
        // What each mint receives with: the address there, else an invoice.
        compose.onNodeWithText(text(R.string.receive_on_home_detail, "alice@home.example")).assertExists()
        compose.onNodeWithText(text(R.string.receive_on_invoice)).assertExists()
        compose.onNodeWithText("other.example").performClick()
        assertEquals(listOf("other.example"), picked)
    }

    @Test
    fun aMintWhoseKeyChangedIsListedButCantBePicked() {
        val picked = mutableListOf<String>()
        compose.setContent {
            ReceiveScreen(
                ReceiveState("alice@home.example", homeHost = "home.example", mints = twoMints.map { it.copy(keyChanged = !it.isHome) }),
                onBack = {},
                onClaim = {},
                InvoiceActions(onReceiveOn = { picked += it }),
            )
        }
        compose.onNodeWithText(text(DesignR.string.mint_choice_change)).performScrollTo().performClick()
        compose.onNodeWithText(text(DesignR.string.key_mismatch_short)).assertExists()
        compose.onNode(hasTestTag(MINT_KEY_WARNING_TAG), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("other.example").performClick()
        assertTrue(picked.isEmpty())
    }

    @Test
    fun aMintPickedWithoutAnAddressThereIsReceivedOnByInvoice() {
        compose.setContent {
            ReceiveScreen(
                ReceiveState(address = null, on = "other.example", homeHost = "home.example", mints = twoMints),
                onBack = {},
                onClaim = {},
            )
        }
        compose.onNodeWithText(text(R.string.receive_on, "other.example")).assertExists()
        compose.onNodeWithText(text(R.string.receive_invoice_create)).assertExists()
    }

    @Test
    fun withOneMintNothingIsSaidAboutMints() {
        compose.setContent {
            ReceiveScreen(
                ReceiveState("alice@home.example", homeHost = "home.example", mints = twoMints.take(1)),
                onBack = {},
                onClaim = {},
            )
        }
        compose.onNodeWithText(text(R.string.receive_on_home, "home.example")).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.mint_choice_change)).assertDoesNotExist()
    }

    @Test
    fun whereTheBrandHidesMintsNoneIsNamed() {
        compose.setContent {
            ReceiveScreen(
                ReceiveState("alice@home.example", homeHost = "home.example", mints = twoMints, mintsVisible = false),
                onBack = {},
                onClaim = {},
            )
        }
        compose.onNodeWithText(text(R.string.receive_on_home, "home.example")).assertDoesNotExist()
    }

    @Test
    fun anInvoiceSaysItsMintAndIsNotMovedToAnother() {
        val request = InvoiceRequest.Ready("id", "lnbcrt1", 2_000_000, 1_999_000, Long.MAX_VALUE)
        compose.setContent {
            ReceiveScreen(
                ReceiveState(invoice = ShownInvoice(request, host = "other.example"), homeHost = "home.example", mints = twoMints),
                onBack = {},
                onClaim = {},
            )
        }
        compose.onNodeWithText(text(R.string.receive_on, "other.example")).performScrollTo().assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_choice_change)).assertDoesNotExist()
    }
}
