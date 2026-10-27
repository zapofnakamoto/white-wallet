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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import whitewallet.core.designsystem.WalletStatusScope
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.brand.ui.Anchor
import whitewallet.brand.WalletStatus
import whitewallet.brand.ProblemKind
import whitewallet.core.designsystem.amountInputLabel
import whitewallet.core.designsystem.AmountText
import whitewallet.core.scanner.QrScanner
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.SuccessScreen
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.FaucetState
import whitewallet.core.wallet.PayDestination
import whitewallet.core.wallet.PayPreview
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.PayQuote
import whitewallet.core.wallet.PayResult
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCard
import whitewallet.core.designsystem.WalletTextField

/** Pay an invoice, a Lightning Address or a pay link: enter or scan it, review the amount and fee, pay. */
@Composable
fun SendRoute(onDone: () -> Unit, onCreateNote: () -> Unit, viewModel: SendViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
        actions = SendActions(
            viewModel::onInputChange, viewModel::startScan, viewModel::stopScan, viewModel::onScanned, viewModel::check,
            viewModel::onAmountChange, viewModel::onCommentChange, viewModel::pay, viewModel::edit, onCreateNote,
            viewModel::invoiceFromFaucet, viewModel::sendMore,
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
)

@Composable
fun SendScreen(state: SendState, onBack: () -> Unit, onDone: () -> Unit, actions: SendActions) {
    BackHandler(enabled = state.scanning || state.preview != null) { onBack() }
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.send_title), onBack.takeIf { !state.paying }) }) { padding ->
        val paid = state.result as? PayResult.Paid
        if (paid != null) {
            val to = state.preview?.payee
            SuccessScreen(
                Anchor.SendSuccess, WalletStatus.Sent(paid.amountMsat), stringResource(R.string.send_paid_title), paid.amountMsat,
                stringResource(R.string.send_done), onDone, stringResource(R.string.send_more), actions.onSendMore, Modifier.padding(padding),
                details = listOfNotNull(
                    to?.let { stringResource(R.string.send_success_to, it) },
                    paid.feeMsat.takeIf { it > 0 }?.let { stringResource(R.string.send_fee, formatAmount(it)) },
                ),
            )
            return@Scaffold
        }
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            val preview = state.preview
            val result = state.result
            if (preview != null) WalletStatusScope(sendStatus(state)) { BrandDecoration(Anchor.SendHeader) }
            when {
                result != null && preview != null -> ResultContent(result, preview, onDone, actions.onEdit)
                preview != null -> ReviewContent(state, preview, actions)
                state.scanning -> ScanContent(actions)
                else -> InputContent(state, actions)
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
        null -> when {
            state.paying -> WalletStatus.Sending
            quote is PayQuote.Invalid -> WalletStatus.Problem(problemKind(quote.problem))
            state.problem != null -> WalletStatus.Problem(problemKind(state.problem))
            else -> WalletStatus.None
        }
    }
}

private fun problemKind(problem: PayProblem): ProblemKind = when (problem) {
    PayProblem.NoFunds, PayProblem.TooMuch -> ProblemKind.NotEnoughFunds
    PayProblem.Unreachable -> ProblemKind.Offline
    else -> ProblemKind.Other
}

@Composable
private fun InputContent(state: SendState, actions: SendActions) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
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
        WalletButton(kind = ButtonKind.Secondary, onClick = actions.onStartScan, enabled = !state.checking, modifier = Modifier.weight(1f)) {
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
        ) { Text(stringResource(R.string.send_paste)) }
    }
    state.problem?.let { Text(problemText(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    PrimaryButton(stringResource(R.string.send_continue), actions.onCheck, enabled = state.input.isNotBlank(), busy = state.checking)
    Text(stringResource(R.string.send_or), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    WalletButton(kind = ButtonKind.Secondary, onClick = actions.onCreateNote, enabled = !state.checking, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.send_create_note))
    }
    state.faucet?.let { FaucetButton(it, enabled = !state.checking, onClick = actions.onFaucet) }
}

/** Developer mode with a local regtest mint: the faucet makes an invoice to pay, as `regtest.sh invoice` would. */
@Composable
private fun FaucetButton(faucet: FaucetState, enabled: Boolean, onClick: () -> Unit) {
    WalletButton(kind = ButtonKind.Secondary, onClick = onClick, enabled = enabled, busy = faucet.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.send_faucet, formatAmount(FAUCET_SATS * 1_000)))
    }
    faucet.error?.let { Text(stringResource(R.string.send_faucet_failed, it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
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
private fun ReviewContent(state: SendState, preview: PayPreview.Ready, actions: SendActions) {
    WalletCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                preview.payee ?: stringResource(R.string.send_to_invoice),
                style = MaterialTheme.typography.titleMedium,
            )
            preview.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (preview.internal) {
                Text(stringResource(R.string.send_internal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
                Text(stringResource(R.string.send_amount_range, formatAmount(preview.minMsat), formatAmount(minOf(preview.maxMsat, preview.availableMsat))))
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
    QuoteText(state.quote)
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
    val (text, error) = when (quote) {
        null -> return
        is PayQuote.Ok -> (if (quote.feeMsat > 0) stringResource(R.string.send_fee, formatAmount(quote.feeMsat)) else stringResource(R.string.send_no_fee)) to false
        is PayQuote.Unreachable -> stringResource(R.string.send_not_exact, formatAmount(quote.allMsat), formatAmount(quote.maxWithChangeMsat)) to true
        is PayQuote.Invalid -> when (quote.problem) {
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

@Composable
private fun ResultContent(result: PayResult, preview: PayPreview.Ready, onDone: () -> Unit, onTryAgain: () -> Unit) {
    val to = preview.payee
    when (result) {
        // Shown by SuccessScreen instead.
        is PayResult.Paid -> Unit
        is PayResult.Pending -> {
            Text(stringResource(R.string.send_pending_title), style = MaterialTheme.typography.headlineSmall)
            val sats = formatAmount(result.amountMsat)
            Text(if (to != null) stringResource(R.string.send_pending_body, sats, to) else stringResource(R.string.send_pending_body_invoice, sats), textAlign = TextAlign.Center)
        }
        is PayResult.Failed -> {
            Text(stringResource(R.string.send_failed_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
            Text(problemText(result.problem), textAlign = TextAlign.Center)
            // Only for an invoice that could still be paid (the money is back or never left).
            if (preview.destination is PayDestination.PayLink || result.problem == PayProblem.PaymentFailed) {
                WalletButton(kind = ButtonKind.Text, onClick = onTryAgain) { Text(stringResource(R.string.send_try_again)) }
            }
        }
    }
    PrimaryButton(stringResource(R.string.send_done), onDone)
}

@Composable
internal fun problemText(problem: PayProblem): String = stringResource(
    when (problem) {
        PayProblem.NotRecognized -> R.string.send_problem_not_recognized
        PayProblem.NoAmount -> R.string.send_problem_no_amount
        PayProblem.Expired -> R.string.send_problem_expired
        PayProblem.Unreachable -> R.string.send_problem_unreachable
        PayProblem.PayeeRefused -> R.string.send_problem_payee_refused
        PayProblem.InvoiceMismatch -> R.string.send_problem_mismatch
        PayProblem.OwnAddress -> R.string.send_problem_own_address
        PayProblem.NoFunds -> R.string.send_problem_no_funds
        PayProblem.TooMuch -> R.string.send_problem_too_much
        PayProblem.NotExact -> R.string.send_problem_not_exact
        PayProblem.BelowMinimum -> R.string.send_problem_below_minimum
        PayProblem.AboveMaximum -> R.string.send_problem_above_maximum
        PayProblem.Refused -> R.string.send_problem_refused
        PayProblem.PaymentFailed -> R.string.send_problem_payment_failed
    },
)
