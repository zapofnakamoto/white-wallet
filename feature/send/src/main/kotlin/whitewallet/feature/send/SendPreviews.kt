package whitewallet.feature.send

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.Moment
import whitewallet.core.designsystem.PREVIEW_MILLIS
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.wallet.PayDestination
import whitewallet.core.wallet.PayPreview
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.PayQuote
import whitewallet.core.wallet.PayResult
import whitewallet.core.wallet.SharedNote
import whitewallet.core.wallet.ReclaimResult
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

// Errors, problems and waits. Each is the everyday state with one thing changed.

private val sendActions = SendActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})

@Composable
private fun SharedNoteOf(state: SharedNoteUiState) =
    SharedNoteScreen(state, onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {})

/** Nothing typed yet: the Lightning address field, the mints to pay from behind it. */
private val sendInputState = SendState(balanceMsat = 25_000_000, mints = sendMints)

/** A payment to a Lightning address was sent but its outcome isn't known yet. */
@WalletPreviews
@Composable
private fun SendPendingPreview() =
    SendScreen(sendSuccessState.copy(result = PayResult.Pending(21_000_000)), onBack = {}, onDone = {}, actions = sendActions)

/** A payment to an invoice was sent but its outcome isn't known yet. */
@WalletPreviews
@Composable
private fun SendPendingInvoicePreview() =
    SendScreen(
        sendSuccessState.copy(preview = sendSuccessState.preview?.copy(payee = null), result = PayResult.Pending(21_000_000)),
        onBack = {},
        onDone = {},
        actions = sendActions,
    )

/** A payment failed, and the mint said why in its own words. */
@WalletPreviews
@Composable
private fun SendFailedPreview() =
    SendScreen(
        sendSuccessState.copy(result = PayResult.Failed(PayProblem.PaymentFailed, mintMessage = "No route to the payee")),
        onBack = {},
        onDone = {},
        actions = sendActions,
    )

/** What was typed isn't something that can be paid. */
@WalletPreviews
@Composable
private fun SendProblemPreview() =
    SendScreen(sendInputState.copy(input = "hal@", problem = PayProblem.NotRecognized), onBack = {}, onDone = {}, actions = sendActions)

/** The phone is offline: said before the user tries. */
@WalletPreviews
@Composable
private fun SendOfflinePreview() =
    SendScreen(sendInputState.copy(input = "hal@mint.example", offline = true), onBack = {}, onDone = {}, actions = sendActions)

/** The amount is more than the payee takes. */
@WalletPreviews
@Composable
private fun SendQuoteProblemPreview() =
    SendScreen(
        sendState.copy(amountText = "2000000", quote = PayQuote.Invalid(PayProblem.AboveMaximum, limitMsat = 1_000_000_000)),
        onBack = {},
        onDone = {},
        actions = sendActions,
    )

/** Money is held on a mint whose signing key changed: nothing is sent from it, and the way to its details. */
@WalletPreviews
@Composable
private fun SendKeyChangedPreview() =
    SendScreen(
        sendInputState.copy(
            mints = listOf(SendMint(HOME, isHome = true, maxMsat = 21_000_000), SendMint("cash.example", false, 4_000_000, true))
        ),
        onBack = {},
        onDone = {},
        actions = sendActions,
    )

/** A note can't be made: the mint refused. */
@WalletPreviews
@Composable
private fun CreateNoteProblemPreview() =
    CreateNoteScreen(createNoteState.copy(problem = PayProblem.Refused), onBack = {}, onAmountChange = {}, onCreate = {})

/** The split for a note was sent but its outcome isn't known yet. */
@WalletPreviews
@Composable
private fun CreateNotePendingPreview() =
    CreateNoteScreen(createNoteState.copy(pending = true), onBack = {}, onAmountChange = {}, onCreate = {})

/** The phone is offline: a note is made at its mint. */
@WalletPreviews
@Composable
private fun CreateNoteOfflinePreview() =
    CreateNoteScreen(createNoteState.copy(offline = true), onBack = {}, onAmountChange = {}, onCreate = {})

/** A note someone claimed. */
@WalletPreviews
@Composable
private fun SharedNoteSpentPreview() =
    SharedNoteOf(sharedNoteState.copy(note = sharedNoteState.note?.copy(state = SharedNoteState.Spent, link = null)))

/** A note taken back into the balance. */
@WalletPreviews
@Composable
private fun SharedNoteTakenBackPreview() =
    SharedNoteOf(sharedNoteState.copy(note = sharedNoteState.note?.copy(state = SharedNoteState.Reclaimed, link = null)))

/** A note taken back, its mint not yet sure. */
@WalletPreviews
@Composable
private fun SharedNoteReclaimPendingPreview() =
    SharedNoteOf(
        sharedNoteState.copy(note = sharedNoteState.note?.copy(state = SharedNoteState.Reclaimed, link = null), takenBack = ReclaimResult.Pending)
    )

/** Taken back on its page just now: the success screen. */
@WalletPreviews
@Composable
private fun SharedNoteTakeBackDonePreview() =
    SharedNoteOf(
        sharedNoteState.copy(
            note = sharedNoteState.note?.copy(state = SharedNoteState.Reclaimed, link = null),
            takenBack = ReclaimResult.Reclaimed(sharedNoteState.note?.amountMsat ?: 0),
        )
    )

/** A note removed while its mint didn't know it: whether it was claimed isn't known. */
@WalletPreviews
@Composable
private fun SharedNoteUnverifiedPreview() =
    SharedNoteOf(sharedNoteState.copy(note = sharedNoteState.note?.copy(state = SharedNoteState.Unverified, link = null)))

/** A note its mint doesn't know. */
@WalletPreviews
@Composable
private fun SharedNoteUnknownPreview() =
    SharedNoteOf(
        sharedNoteState.copy(note = sharedNoteState.note?.copy(confirmed = false, unknownToMint = true, checkedAtMillis = PREVIEW_MILLIS))
    )

/** Taking a note back failed, and the mint said why in its own words. */
@WalletPreviews
@Composable
private fun SharedNoteReclaimFailedPreview() =
    SharedNoteOf(sharedNoteState.copy(takenBack = ReclaimResult.Failed(PayProblem.Refused, "Try again later")))

/** The question before taking a note back. */
@WalletPreviews @Composable private fun SharedNoteReclaimConfirmPreview() = SharedNoteOf(sharedNoteState.copy(confirmReclaim = true))

/** The note is gone from the phone. */
@WalletPreviews @Composable private fun SharedNoteNotFoundPreview() = SharedNoteOf(SharedNoteUiState(note = null, loading = false))
