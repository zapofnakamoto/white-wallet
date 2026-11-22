package whitewallet.feature.receive

import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.Moment
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.wallet.InvoiceRequest
import whitewallet.core.wallet.InvoiceStatus
import whitewallet.core.wallet.NoHome

// The Receive screen, ready to receive, and its success screen (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only; every text is
// the screen's own.

/** A payment arrived. */
internal val receiveSuccessState = ReceiveState("satoshi@mint.example", receivedMsat = 21_000_000)

@WalletPreviews @Moment @Composable private fun ReceiveSuccessPreview() = ReceiveScreen(receiveSuccessState, onBack = {}, onClaim = {})

/** The account's Lightning address and its QR code, on the home mint; another mint to pick. */
internal val receiveState =
    ReceiveState(
        address = "satoshi@mint.example",
        addressCount = 1,
        homeHost = "mint.example",
        mints =
            listOf(
                ReceiveMint("mint.example", isHome = true, address = "satoshi@mint.example"),
                ReceiveMint("cash.example", isHome = false, address = null),
            ),
    )

@WalletPreviews @Composable private fun ReceivePreview() = ReceiveScreen(receiveState, onBack = {}, onClaim = {})

// Errors, problems and waits. Each is the everyday state with one thing changed.

private val invoiceRequest =
    InvoiceRequest.Ready("r1", "lnbc21u1mintexample", amountMsat = 21_000_000, expectedMsat = 21_000_000, expiresAtMillis = 0)

/** Nothing typed yet: the amount form for an invoice, without an address. */
private val receiveFormState = receiveState.copy(address = null, addressCount = 0, amountText = "2100")

/** The account has no mint at all: add one. */
@WalletPreviews
@Composable
private fun ReceiveNoMintPreview() = ReceiveScreen(ReceiveState(noHome = NoHome.NoMint), onBack = {}, onClaim = {})

/** An invoice made, waiting to be paid. */
@WalletPreviews
@Composable
private fun ReceiveInvoicePreview() =
    ReceiveScreen(receiveState.copy(invoice = ShownInvoice(invoiceRequest, host = "mint.example")), onBack = {}, onClaim = {})

/** An invoice that ran out unpaid. */
@WalletPreviews
@Composable
private fun ReceiveInvoiceExpiredPreview() =
    ReceiveScreen(
        receiveState.copy(invoice = ShownInvoice(invoiceRequest, InvoiceStatus.Expired, host = "mint.example")),
        onBack = {},
        onClaim = {},
    )

/** The mint answered no to the amount asked. */
@WalletPreviews
@Composable
private fun ReceiveInvoiceProblemPreview() =
    ReceiveScreen(receiveFormState.copy(problem = InvoiceProblem.OutOfRange(1_000, 1_000_000)), onBack = {}, onClaim = {})

/** The phone is offline: said before the user tries. */
@WalletPreviews
@Composable
private fun ReceiveInvoiceOfflinePreview() = ReceiveScreen(receiveFormState.copy(offline = true), onBack = {}, onClaim = {})

/** The address shown, its mint not answering. */
@WalletPreviews
@Composable
private fun ReceiveAddressMintSilentPreview() = ReceiveScreen(receiveState.copy(addressMintNotAnswering = true), onBack = {}, onClaim = {})

/** The address shown, its mint's signing key changed: payments wait there, and the way to its details. */
@WalletPreviews
@Composable
private fun ReceiveAddressKeyChangedPreview() = ReceiveScreen(receiveState.copy(addressKeyChanged = true), onBack = {}, onClaim = {})
