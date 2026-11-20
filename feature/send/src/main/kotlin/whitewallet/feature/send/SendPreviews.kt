package whitewallet.feature.send

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.Moment
import whitewallet.core.designsystem.PREVIEW_MILLIS
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.wallet.PayDestination
import whitewallet.core.wallet.PayPreview
import whitewallet.core.wallet.PayQuote
import whitewallet.core.wallet.PayResult
import whitewallet.core.wallet.SharedNote
import whitewallet.core.wallet.SharedNoteState

// Sending, making a note and showing it, and scanning, in their everyday state, and the Send screen's success screen
// (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only; every text is the screen's own.

/** A payment to a Lightning address went out, with a fee. */
internal val sendSuccessState =
    SendState(
        preview =
            PayPreview.Ready(
                destination = PayDestination.PayLink("https://mint.example/.well-known/lnurlp/hal", "", "", 1_000, 1_000_000_000, 0, false),
                fixedAmountMsat = null,
                minMsat = 1_000,
                maxMsat = 1_000_000_000,
                commentMaxLength = 0,
                payee = "hal@mint.example",
                description = null,
                availableMsat = 100_000_000,
            ),
        result = PayResult.Paid(amountMsat = 21_000_000, feeMsat = 2_000),
    )

@WalletPreviews
@Moment
@Composable
private fun SendSuccessPreview() =
    SendScreen(sendSuccessState, onBack = {}, onDone = {}, actions = SendActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}))

private const val HOME = "mint.example"

private val sendMints =
    listOf(SendMint(HOME, isHome = true, maxMsat = 21_000_000), SendMint("cash.example", isHome = false, maxMsat = 4_000_000))

/** A Lightning address checked, an amount typed and the fee known: one tap from paying. */
internal val sendState =
    sendSuccessState.copy(
        result = null,
        amountText = "2100",
        quote = PayQuote.Ok(amountMsat = 2_100_000, feeMsat = 2_000, host = HOME),
        balanceMsat = 25_000_000,
        mints = sendMints,
    )

internal val createNoteState =
    CreateNoteState(
        availableMsat = 25_000_000,
        amountText = "2100",
        quote = PayQuote.Ok(amountMsat = 2_100_000, feeMsat = 0, host = HOME),
        mints = sendMints,
    )

/** A note just made, to hand over: its QR code. */
internal val sharedNoteState =
    SharedNoteUiState(
        note =
            SharedNote(
                "s1",
                HOME,
                amountMsat = 2_100_000,
                certified = true,
                state = SharedNoteState.Waiting,
                createdAtMillis = PREVIEW_MILLIS,
                link = "lnurlw://$HOME/w?k1=" + "ab".repeat(32),
            ),
        loading = false,
    )

@WalletPreviews
@Composable
private fun SendPreview() = SendScreen(sendState, onBack = {}, onDone = {}, actions = SendActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}))

@WalletPreviews
@Composable
private fun CreateNotePreview() = CreateNoteScreen(createNoteState, onBack = {}, onAmountChange = {}, onCreate = {})

@WalletPreviews
@Composable
private fun SharedNotePreview() =
    SharedNoteScreen(sharedNoteState, onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {})

/** The scanner's frame, without a camera behind it. */
@WalletPreviews
@Composable
private fun QrScanPreview() = ScanScreen(onBack = {}, onScanned = {}, scanner = { modifier, _ -> Box(modifier) })
