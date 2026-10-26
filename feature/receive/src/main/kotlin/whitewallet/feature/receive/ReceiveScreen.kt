package whitewallet.feature.receive

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.QrCode
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.amountInputLabel
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.InvoiceStatus

@Composable
fun ReceiveRoute(onBack: () -> Unit, onClaim: () -> Unit, viewModel: ReceiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReceiveScreen(
        state, onBack, onClaim,
        InvoiceActions(viewModel::requestAmount, viewModel::onAmountChange, viewModel::createInvoice, viewModel::newInvoice),
    )
}

/** What the invoice part of the screen can ask for. */
class InvoiceActions(
    val onRequestAmount: () -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onNewInvoice: () -> Unit = {},
)

@Composable
fun ReceiveScreen(state: ReceiveState, onBack: () -> Unit, onClaim: () -> Unit, invoice: InvoiceActions = InvoiceActions()) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.receive_title), onBack) }) { padding ->
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            val shown = state.invoice
            when {
                shown != null -> InvoiceContent(shown, onBack, invoice.onNewInvoice)
                state.showAmountForm -> AmountForm(state, invoice)
                state.address != null -> AddressContent(state.address, invoice.onRequestAmount)
            }
            if (state.receivedMsat > 0) {
                Text(stringResource(R.string.receive_got, formatAmount(state.receivedMsat)), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
            if (shown == null) TextButton(onClick = onClaim) { Text(stringResource(R.string.receive_claim)) }
        }
    }
}

@Composable
private fun AddressContent(address: String, onRequestAmount: () -> Unit) {
    QrCode("lightning:${address.uppercase()}")
    Text(address, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.receive_hint), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    CopyShare(address, stringResource(R.string.receive_copied))
    TextButton(onClick = onRequestAmount) { Text(stringResource(R.string.receive_request_amount)) }
}

@Composable
private fun AmountForm(state: ReceiveState, actions: InvoiceActions) {
    Text(stringResource(R.string.receive_invoice_intro), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = state.amountText,
        onValueChange = actions.onAmountChange,
        label = { Text(amountInputLabel()) },
        singleLine = true,
        enabled = !state.creating,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { actions.onCreate() }),
        modifier = Modifier.fillMaxWidth(),
    )
    state.problem?.let { problem ->
        val message = when (problem) {
            is InvoiceProblem.OutOfRange -> stringResource(R.string.receive_invoice_range, formatAmount(problem.minMsat), formatAmount(problem.maxMsat))
            InvoiceProblem.Unreachable -> stringResource(R.string.receive_invoice_unreachable)
            InvoiceProblem.Refused -> stringResource(R.string.receive_invoice_refused)
        }
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    PrimaryButton(stringResource(R.string.receive_invoice_create), actions.onCreate, enabled = state.amountMsat != null, busy = state.creating)
}

@Composable
private fun InvoiceContent(shown: ShownInvoice, onDone: () -> Unit, onNewInvoice: () -> Unit) {
    val request = shown.request
    when (val status = shown.status) {
        is InvoiceStatus.Paid -> {
            Text(stringResource(R.string.receive_got, formatAmount(status.receivedMsat)), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall)
            PrimaryButton(stringResource(R.string.receive_done), onDone)
        }
        InvoiceStatus.Expired -> {
            Text(stringResource(R.string.receive_invoice_expired), textAlign = TextAlign.Center)
            PrimaryButton(stringResource(R.string.receive_invoice_new), onNewInvoice)
        }
        InvoiceStatus.Waiting -> {
            QrCode("lightning:${request.invoice.uppercase()}", description = stringResource(R.string.receive_invoice_qr))
            Text(formatAmount(request.amountMsat), style = MaterialTheme.typography.headlineSmall)
            if (request.expectedMsat < request.amountMsat) {
                Text(
                    stringResource(R.string.receive_invoice_fee, formatAmount(request.expectedMsat), formatAmount(request.amountMsat - request.expectedMsat)),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(stringResource(R.string.receive_invoice_waiting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CopyShare(request.invoice, stringResource(R.string.receive_invoice_copied))
            TextButton(onClick = onNewInvoice) { Text(stringResource(R.string.receive_invoice_other_amount)) }
        }
    }
}

@Composable
private fun CopyShare(text: String, copiedMessage: String) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember(text) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("lightning", text))) }
            copied = true
        }) { Text(stringResource(R.string.receive_copy)) }
        OutlinedButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, null))
        }) { Text(stringResource(R.string.receive_share)) }
    }
    if (copied) Text(copiedMessage, style = MaterialTheme.typography.bodySmall)
}
