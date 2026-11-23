package whitewallet.feature.send

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.AmountText
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.core.designsystem.MintChoiceLine
import whitewallet.core.designsystem.MintChoiceSheet
import whitewallet.core.designsystem.MintOption
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.ResultScreen
import whitewallet.core.designsystem.ResultTone
import whitewallet.core.designsystem.SuccessAction
import whitewallet.core.designsystem.SuccessScreen
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCard
import whitewallet.core.designsystem.WalletStatusScope
import whitewallet.core.designsystem.WalletTextField
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.amountInputLabel
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.scanner.QrScanner
import whitewallet.core.wallet.FaucetState
import whitewallet.core.wallet.PayDestination
import whitewallet.core.wallet.PayPreview
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.PayQuote
import whitewallet.core.wallet.PayResult

/**
 * Pay an invoice, a Lightning address or a pay link: enter or scan it, review the amount and fee, pay. A note link entered here goes to
 * [onClaim].
 */
@Composable
fun SendRoute(
    onDone: () -> Unit,
    /** Opens making a note link: from the mint given, else as the wallet chooses. */
    onCreateNote: (from: String?) -> Unit,
    onClaim: (String) -> Unit = {},
    /** Opens a mint's details: where a new signing key is accepted. */
    onOpenMint: (String) -> Unit = {},
    viewModel: SendViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.claim) {
        state.claim?.let {
            onClaim(it)
            viewModel.claimOpened()
        }
    }
    SendScreen(
        state,
        onBack = {
            when {
                state.paying -> Unit
                state.result != null -> onDone()
                state.scanning -> viewModel.stopScan()
                state.preview != null -> viewModel.edit()
                else -> onDone()
            }
        },
        onDone = onDone,
        actions =
            SendActions(
                onInputChange = viewModel::onInputChange,
                onStartScan = viewModel::startScan,
                onStopScan = viewModel::stopScan,
                onScanned = viewModel::onScanned,
                onCheck = viewModel::check,
                onAmountChange = viewModel::onAmountChange,
                onCommentChange = viewModel::onCommentChange,
                onPay = viewModel::pay,
                onEdit = viewModel::edit,
                onCreateNote = { onCreateNote(state.from) },
                onFaucet = viewModel::invoiceFromFaucet,
                onSendMore = viewModel::sendMore,
                onPayFrom = viewModel::payFrom,
                onOpenMint = onOpenMint,
            ),
    )
}

class SendActions(
    val onInputChange: (String) -> Unit,
    val onStartScan: () -> Unit,
    val onStopScan: () -> Unit,
    val onScanned: (String) -> Unit,
    val onCheck: () -> Unit,
    val onAmountChange: (String) -> Unit,
    val onCommentChange: (String) -> Unit,
    val onPay: () -> Unit,
    val onEdit: () -> Unit,
    val onCreateNote: () -> Unit,
    /** Developer: have the regtest faucet make an invoice to pay. */
    val onFaucet: () -> Unit = {},
    /** From the success screen back to an empty form, for another payment. */
    val onSendMore: () -> Unit = {},
    /** Pays from the mint picked; null leaves it to the wallet. */
    val onPayFrom: (String?) -> Unit = {},
    /** Opens a mint's details: where a new signing key is accepted. */
    val onOpenMint: (String) -> Unit = {},
)

@Composable
fun SendScreen(state: SendState, onBack: () -> Unit, onDone: () -> Unit, actions: SendActions) {
    BackHandler(enabled = state.scanning || state.preview != null) { onBack() }
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.send_title), onBack.takeIf { !state.paying }) }) { padding ->
        if (!pageLoaded(state.loaded, Modifier.padding(padding))) return@Scaffold
        val paid = state.result as? PayResult.Paid
        if (paid != null) {
            val to = state.preview?.payee
            SuccessScreen(
                anchor = Anchor.SendSuccess,
                status = WalletStatus.Sent(paid.amountMsat),
                title = stringResource(R.string.send_paid_title),
                done = SuccessAction(stringResource(R.string.send_done), onDone),
                more = SuccessAction(stringResource(R.string.send_more), actions.onSendMore),
                amountMsat = paid.amountMsat,
                modifier = Modifier.padding(padding),
                lines =
                    listOfNotNull(
                        to?.let { stringResource(R.string.send_success_to, it) },
                        paid.feeMsat.takeIf { it > 0 }?.let { stringResource(R.string.send_fee, formatAmount(it)) },
                    ),
            )
            return@Scaffold
        }
        // Still on its way or not paid: on the same layout, as a wait or a problem.
        val result = state.result
        if (result != null && state.preview != null) {
            PayEnded(result, state.preview, sendStatus(state), state.mintsVisible, onDone, actions.onEdit, Modifier.padding(padding))
            return@Scaffold
        }
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            val preview = state.preview
            if (preview != null) WalletStatusScope(sendStatus(state)) { BrandDecoration(Anchor.SendHeader) }
            var picking by rememberSaveable { mutableStateOf(false) }
            val onPickMint = { picking = true }.takeIf { state.canPickMint }
            when {
                preview != null -> ReviewContent(state, preview, actions, onPickMint)
                state.scanning -> ScanContent(actions)
                else -> InputContent(state, actions, onPickMint)
            }
            if (picking && state.canPickMint) {
                SendFromSheet(state.mints, state.from, state.amountMsat.takeIf { preview != null }, onDismiss = { picking = false }) {
                    picking = false
                    actions.onPayFrom(it)
                }
            }
        }
    }
}

/** What the screen is about, for the brand's design: the payment going out, its outcome, or what stops it. */
private fun sendStatus(state: SendState): WalletStatus {
    val quote = state.quote
    return when (val result = state.result) {
        is PayResult.Paid -> WalletStatus.Sent(result.amountMsat)
        is PayResult.Pending -> WalletStatus.Sending
        is PayResult.Failed -> WalletStatus.Problem(problemKind(result.problem))
        null ->
            when {
                state.paying -> WalletStatus.Sending
                quote is PayQuote.Invalid -> WalletStatus.Problem(problemKind(quote.problem))
                state.problem != null -> WalletStatus.Problem(problemKind(state.problem))
                else -> WalletStatus.None
            }
    }
}

/** Picks the mint to send [amountMsat] (when known) from among [mints], or leaves it to the wallet; [from] is the one picked. */
@Composable
internal fun SendFromSheet(mints: List<SendMint>, from: String?, amountMsat: Long?, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    MintChoiceSheet(
        title = stringResource(R.string.send_from_title),
        options =
            mints.map {
                MintOption(
                    it.host,
                    mintDetail(it, amountMsat),
                    enabled = !it.keyChanged && (amountMsat == null || it.maxMsat >= amountMsat),
                )
            },
        selected = from,
        onSelect = onSelect,
        onDismiss = onDismiss,
        automatic = stringResource(R.string.send_from_automatic) to stringResource(R.string.send_from_automatic_detail),
    )
}

/**
 * What [mint] can pay: the most, home said first; not enough for [amountMsat], when given and more than that. Nothing, if its signing key
 * doesn't match.
 */
@Composable
internal fun mintDetail(mint: SendMint, amountMsat: Long?): String {
    val most = formatAmount(mint.maxMsat)
    return when {
        mint.keyChanged -> stringResource(R.string.send_from_key_changed)
        amountMsat != null && mint.maxMsat < amountMsat -> stringResource(R.string.send_from_not_enough, most)
        mint.isHome -> stringResource(R.string.send_from_detail_home, most)
        else -> stringResource(R.string.send_from_detail, most)
    }
}

internal fun problemKind(problem: PayProblem): ProblemKind =
    when (problem) {
        PayProblem.NoFunds,
        PayProblem.TooMuch -> ProblemKind.NotEnoughFunds
        PayProblem.Unreachable -> ProblemKind.Offline
        PayProblem.MintNotAnswering -> ProblemKind.MintNotAnswering
        else -> ProblemKind.Other
    }

@Composable
private fun InputContent(state: SendState, actions: SendActions, onPickMint: (() -> Unit)?) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Opened for a mint: paid from there, up to what it holds. Otherwise the wallet picks once it knows the payment.
    val from = state.from?.takeIf { state.mintsVisible }
    val stuck = state.mints.filter { it.keyChanged }
    val sendable = state.mints.filterNot { it.keyChanged }
    if (from != null) {
        val most = state.mints.firstOrNull { it.host == from }?.maxMsat ?: 0
        MintChoiceLine(stringResource(R.string.send_from_up_to, from, formatAmount(most)), onPickMint)
    } else if (stuck.isNotEmpty()) {
        // Money on a mint whose signing key doesn't match isn't sendable: said with the most one mint can pay, and where to fix it. With
        // nothing else to pay from, that is all there is to say.
        sendable.maxOfOrNull { it.maxMsat }?.let { LimitText(stringResource(R.string.send_available, formatAmount(it))) }
        KeyChangedHeld(stuck, state.mintsVisible, actions.onOpenMint)
        if (sendable.isEmpty()) return
    } else
        state.limitMsat?.let {
            LimitText(stringResource(R.string.send_limit_one_payment, formatAmount(it), formatAmount(state.balanceMsat)))
        }
    WalletTextField(
        value = state.input,
        onValueChange = actions.onInputChange,
        label = { Text(stringResource(R.string.send_input)) },
        placeholder = { Text(stringResource(R.string.send_input_hint)) },
        minLines = 3,
        enabled = !state.checking,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        WalletButton(
            kind = ButtonKind.Secondary,
            onClick = actions.onStartScan,
            enabled = !state.checking,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.send_scan))
        }
        WalletButton(
            kind = ButtonKind.Secondary,
            onClick = {
                scope.launch {
                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    if (!text.isNullOrBlank()) actions.onScanned(text)
                }
            },
            enabled = !state.checking,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.send_paste))
        }
    }
    state.shownProblem?.let { Text(problemText(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    // Offline, said before the user tries. Continue stays: a note link entered here goes to Claim, which keeps it until the phone is back.
    if (state.offline) Text(stringResource(R.string.send_offline), textAlign = TextAlign.Center)
    PrimaryButton(stringResource(R.string.send_continue), actions.onCheck, enabled = state.input.isNotBlank(), busy = state.checking)
    Text(stringResource(R.string.send_or), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    WalletButton(
        kind = ButtonKind.Secondary,
        onClick = actions.onCreateNote,
        enabled = !state.checking && !state.offline,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.send_create_note))
    }
    state.faucet?.let { FaucetButton(it, enabled = !state.checking, onClick = actions.onFaucet) }
}

@Composable
private fun LimitText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * What [stuck] mints hold, whose signing key doesn't match: not sendable until it does. Details opens each, where its new key can be
 * accepted; where the brand doesn't name mints, one sentence says it for all of them.
 */
@Composable
internal fun KeyChangedHeld(stuck: List<SendMint>, mintsVisible: Boolean, onOpenMint: (String) -> Unit) {
    if (!mintsVisible) {
        LimitText(stringResource(R.string.send_key_changed_held_unnamed, formatAmount(stuck.sumOf { it.maxMsat })))
        return
    }
    stuck.forEach { mint ->
        LimitText(stringResource(R.string.send_key_changed_held, formatAmount(mint.maxMsat), mint.host))
        WalletButton(kind = ButtonKind.Text, onClick = { onOpenMint(mint.host) }) { Text(stringResource(R.string.send_key_changed_action)) }
    }
}

/** Developer mode with a local regtest mint: the faucet makes an invoice to pay, as `regtest.sh invoice` would. */
@Composable
private fun FaucetButton(faucet: FaucetState, enabled: Boolean, onClick: () -> Unit) {
    WalletButton(
        kind = ButtonKind.Secondary,
        onClick = onClick,
        enabled = enabled,
        busy = faucet.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.send_faucet, formatAmount(FAUCET_SATS * 1_000)))
    }
    faucet.error?.let {
        Text(
            stringResource(R.string.send_faucet_failed, it),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ScanContent(actions: SendActions) {
    Text(stringResource(R.string.send_scan_hint), textAlign = TextAlign.Center)
    QrScanner(
        onScanned = actions.onScanned,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large),
    )
    WalletButton(kind = ButtonKind.Text, onClick = actions.onStopScan) { Text(stringResource(R.string.send_scan_cancel)) }
}

@Composable
private fun ReviewContent(state: SendState, preview: PayPreview.Ready, actions: SendActions, onPickMint: (() -> Unit)?) {
    WalletCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                preview.payee ?: stringResource(R.string.send_to_invoice),
                style = MaterialTheme.typography.titleMedium,
            )
            preview.description
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            if (preview.internal) {
                Text(
                    stringResource(R.string.send_internal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    val fixed = preview.fixedAmountMsat
    if (fixed != null) {
        AmountText(fixed, Modifier.fillMaxWidth())
    } else {
        WalletTextField(
            value = state.amountText,
            onValueChange = actions.onAmountChange,
            label = { Text(amountInputLabel()) },
            supportingText = {
                Text(
                    stringResource(
                        R.string.send_amount_range,
                        formatAmount(preview.minMsat),
                        formatAmount(minOf(preview.maxMsat, preview.availableMsat)),
                    )
                )
            },
            singleLine = true,
            enabled = !state.paying,
            textStyle = MaterialTheme.typography.headlineSmall,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (preview.commentMaxLength > 0) {
        WalletTextField(
            value = state.comment,
            onValueChange = actions.onCommentChange,
            label = { Text(stringResource(R.string.send_comment)) },
            supportingText = { Text(stringResource(R.string.send_comment_count, state.comment.length, preview.commentMaxLength)) },
            enabled = !state.paying,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        stringResource(R.string.send_available, formatAmount(preview.availableMsat)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Which mint pays, once known (picked, or chosen by the wallet for this amount), where there is another to pick.
    state.payingHost
        ?.takeIf { state.mintsVisible && (state.canPickMint || state.from != null) }
        ?.let { MintChoiceLine(stringResource(R.string.send_from, it), onPickMint.takeIf { !state.paying && !state.checking }) }
    state.shownProblem?.let { Text(problemText(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    QuoteText(state.quote)
    if (state.offline) Text(stringResource(R.string.send_offline), textAlign = TextAlign.Center)
    val amount = state.amountMsat
    PrimaryButton(
        if (amount != null) stringResource(R.string.send_pay_amount, formatAmount(amount)) else stringResource(R.string.send_pay),
        actions.onPay,
        enabled = state.canPay,
        busy = state.paying,
    )
    if (state.paying) {
        Text(stringResource(R.string.send_paying), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun QuoteText(quote: PayQuote?) {
    val (text, error) =
        when (quote) {
            null -> return
            is PayQuote.Ok ->
                (if (quote.feeMsat > 0) stringResource(R.string.send_fee, formatAmount(quote.feeMsat))
                else stringResource(R.string.send_no_fee)) to false
            is PayQuote.Unreachable ->
                stringResource(R.string.send_not_exact, formatAmount(quote.allMsat), formatAmount(quote.maxWithChangeMsat)) to true
            is PayQuote.Invalid ->
                when (quote.problem) {
                    PayProblem.BelowMinimum -> stringResource(R.string.send_below_minimum, formatAmount(quote.limitMsat ?: 0))
                    PayProblem.AboveMaximum -> stringResource(R.string.send_above_maximum, formatAmount(quote.limitMsat ?: 0))
                    PayProblem.TooMuch -> stringResource(R.string.send_too_much, formatAmount(quote.limitMsat ?: 0))
                    else -> problemText(quote.problem)
                } to true
        }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A payment that is still on its way, or wasn't paid: what happened, the mint's own words if it gave any, and another try where one can help. */
@Composable
private fun PayEnded(
    result: PayResult,
    preview: PayPreview.Ready,
    status: WalletStatus,
    mintsVisible: Boolean,
    onDone: () -> Unit,
    onTryAgain: () -> Unit,
    modifier: Modifier,
) {
    val to = preview.payee
    val done = SuccessAction(stringResource(R.string.send_done), onDone)
    when (result) {
        // Shown by SuccessScreen instead.
        is PayResult.Paid -> Unit
        is PayResult.Pending -> {
            val sats = formatAmount(result.amountMsat)
            ResultScreen(
                ResultTone.Waiting,
                Anchor.SendResult,
                status,
                stringResource(R.string.send_pending_title),
                done = done,
                modifier = modifier,
                lines =
                    listOf(
                        if (to != null) stringResource(R.string.send_pending_body, sats, to)
                        else stringResource(R.string.send_pending_body_invoice, sats)
                    ),
            )
        }
        is PayResult.Failed ->
            ResultScreen(
                ResultTone.Problem,
                Anchor.SendResult,
                status,
                stringResource(R.string.send_failed_title),
                done = done,
                modifier = modifier,
                // Only a pay link can be tried again, with a new invoice: a mint pays an invoice once, and takes no second try at one that
                // failed. One that no mint was asked to pay is as good as new.
                more =
                    SuccessAction(stringResource(R.string.send_try_again), onTryAgain).takeIf {
                        preview.destination is PayDestination.PayLink || result.problem == PayProblem.MintNotAnswering
                    },
                lines =
                    listOfNotNull(
                        problemText(result.problem, result.host?.takeIf { mintsVisible }),
                        result.mintMessage?.let { stringResource(R.string.send_problem_mint_message, it) },
                    ),
            )
    }
}

/** What [problem] means, in a full sentence; [host] names the mint that didn't answer ([PayProblem.MintNotAnswering]), where mints show. */
@Composable
internal fun problemText(problem: PayProblem, host: String? = null): String =
    if (problem == PayProblem.MintNotAnswering && host != null) stringResource(R.string.send_problem_mint_silent, host)
    else
        stringResource(
            when (problem) {
                PayProblem.NotRecognized -> R.string.send_problem_not_recognized
                PayProblem.WithdrawLink -> R.string.send_problem_withdraw_link
                PayProblem.NoAmount -> R.string.send_problem_no_amount
                PayProblem.Expired -> R.string.send_problem_expired
                PayProblem.Unreachable -> R.string.send_problem_unreachable
                PayProblem.MintNotAnswering -> R.string.send_problem_mint_silent_unnamed
                PayProblem.KeyChanged -> R.string.send_problem_key_changed
                PayProblem.PayeeRefused -> R.string.send_problem_payee_refused
                PayProblem.InvoiceMismatch -> R.string.send_problem_mismatch
                PayProblem.OwnAddress -> R.string.send_problem_own_address
                PayProblem.NoFunds -> R.string.send_problem_no_funds
                PayProblem.TooMuch -> R.string.send_problem_too_much
                PayProblem.NotExact -> R.string.send_problem_not_exact
                PayProblem.BelowMinimum -> R.string.send_problem_below_minimum
                PayProblem.AboveMaximum -> R.string.send_problem_above_maximum
                PayProblem.Refused -> R.string.send_problem_refused
                PayProblem.OwnMintInvoice -> R.string.send_problem_own_mint_invoice
                PayProblem.InvoiceUsed -> R.string.send_problem_invoice_used
                PayProblem.PaymentFailed -> R.string.send_problem_payment_failed
            }
        )
