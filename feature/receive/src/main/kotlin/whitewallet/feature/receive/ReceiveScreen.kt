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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.QrCode
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.SuccessScreen
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletStatusScope
import whitewallet.core.designsystem.WalletTextField
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.amountInputLabel
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.wallet.FaucetState
import whitewallet.core.wallet.InvoiceStatus

@Composable
fun ReceiveRoute(
    onBack: () -> Unit,
    onClaim: () -> Unit,
    /** Opens where a mint is added ([NoHome.NoMint]) or the account's home is picked ([NoHome.PickHome]). */
    onNoHome: (NoHome) -> Unit = {},
    viewModel: ReceiveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReceiveScreen(
        state,
        onBack,
        onClaim,
        InvoiceActions(
            onRequestAmount = viewModel::requestAmount,
            onOtherAddress = viewModel::otherAddress,
            onAmountChange = viewModel::onAmountChange,
            onCreate = viewModel::createInvoice,
            onNewInvoice = viewModel::newInvoice,
            onReceiveMore = viewModel::receiveMore,
            onFaucet = viewModel::payFromFaucet,
        ),
        onNoHome,
    )
}

/** What the invoice part of the screen can ask for. */
class InvoiceActions(
    val onRequestAmount: () -> Unit = {},
    /** Shows another of the account's Lightning addresses. */
    val onOtherAddress: () -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onNewInvoice: () -> Unit = {},
    /** Back from the success screen to the address or a new invoice. */
    val onReceiveMore: () -> Unit = {},
    /** Developer: have the regtest faucet pay what is shown. */
    val onFaucet: () -> Unit = {},
)

@Composable
fun ReceiveScreen(
    state: ReceiveState,
    onBack: () -> Unit,
    onClaim: () -> Unit,
    invoice: InvoiceActions = InvoiceActions(),
    onNoHome: (NoHome) -> Unit = {},
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.receive_title), onBack) }) { padding ->
        if (!pageLoaded(state.loaded, Modifier.padding(padding))) return@Scaffold
        val arrived = state.arrivedMsat
        if (arrived != null) {
            SuccessScreen(
                anchor = Anchor.ReceiveSuccess,
                status = WalletStatus.PaymentReceived(arrived),
                title = stringResource(R.string.receive_success_title),
                amountMsat = arrived,
                doneLabel = stringResource(R.string.receive_done),
                onDone = onBack,
                moreLabel = stringResource(R.string.receive_more),
                onMore = invoice.onReceiveMore,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            val shown = state.invoice
            WalletStatusScope(receiveStatus(state)) { BrandDecoration(Anchor.ReceiveHeader) }
            when {
                state.noHome != null -> NoHomeContent(state.noHome, onNoHome)
                shown != null -> InvoiceContent(shown, onBack, invoice.onNewInvoice)
                state.showAmountForm -> AmountForm(state, invoice)
                state.address != null -> AddressContent(state.address, others = state.addressCount > 1, invoice)
            }
            if (shown == null) WalletButton(kind = ButtonKind.Text, onClick = onClaim) { Text(stringResource(R.string.receive_claim)) }
            val payable = shown?.status == InvoiceStatus.Waiting || (shown == null && !state.showAmountForm && state.address != null)
            state.faucet?.takeIf { payable }?.let { FaucetButton(it, invoice = shown != null, onPay = invoice.onFaucet) }
        }
    }
}

/** Without a home mint nothing can be received: what is missing, and the way to it. */
@Composable
private fun NoHomeContent(noHome: NoHome, onNoHome: (NoHome) -> Unit) {
    val mint = noHome == NoHome.NoMint
    Text(
        stringResource(if (mint) R.string.receive_no_mint else R.string.receive_no_home),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    PrimaryButton(stringResource(if (mint) R.string.receive_no_mint_action else R.string.receive_no_home_action), { onNoHome(noHome) })
}

/** What the screen is about, for the brand's design: waiting, or a problem. What arrived has its own screen. */
private fun receiveStatus(state: ReceiveState): WalletStatus {
    val invoice = state.invoice?.status
    return when {
        invoice == InvoiceStatus.Expired -> WalletStatus.Problem(ProblemKind.Other)
        state.problem == InvoiceProblem.Unreachable -> WalletStatus.Problem(ProblemKind.Offline)
        state.problem != null -> WalletStatus.Problem(ProblemKind.Other)
        state.invoice != null || (state.address != null && !state.showAmountForm) -> WalletStatus.AwaitingPayment
        else -> WalletStatus.None
    }
}

/** Developer mode with a local regtest mint: the faucet pays the address or the invoice, as `regtest.sh pay` would. */
@Composable
private fun FaucetButton(faucet: FaucetState, invoice: Boolean, onPay: () -> Unit) {
    val label =
        if (invoice) stringResource(R.string.receive_faucet_invoice)
        else stringResource(R.string.receive_faucet_address, formatAmount(FAUCET_SATS * 1_000))
    WalletButton(kind = ButtonKind.Secondary, onClick = onPay, busy = faucet.busy) { Text(label) }
    faucet.error?.let {
        Text(
            stringResource(R.string.receive_faucet_failed, it),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AddressContent(address: String, others: Boolean, actions: InvoiceActions) {
    QrCode("lightning:${address.uppercase()}")
    Text(address, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.receive_hint), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    CopyShare(address, stringResource(R.string.receive_copied))
    WalletButton(kind = ButtonKind.Text, onClick = actions.onRequestAmount) { Text(stringResource(R.string.receive_request_amount)) }
    if (others)
        WalletButton(kind = ButtonKind.Text, onClick = actions.onOtherAddress) { Text(stringResource(R.string.receive_other_address)) }
}

@Composable
private fun AmountForm(state: ReceiveState, actions: InvoiceActions) {
    Text(stringResource(R.string.receive_invoice_intro), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    WalletTextField(
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
        val message =
            when (problem) {
                is InvoiceProblem.OutOfRange ->
                    stringResource(R.string.receive_invoice_range, formatAmount(problem.minMsat), formatAmount(problem.maxMsat))
                InvoiceProblem.Unreachable -> stringResource(R.string.receive_invoice_unreachable)
                InvoiceProblem.Refused -> stringResource(R.string.receive_invoice_refused)
            }
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    PrimaryButton(
        stringResource(R.string.receive_invoice_create),
        actions.onCreate,
        enabled = state.amountMsat != null,
        busy = state.creating,
    )
    if (state.addressCount > 0) {
        WalletButton(kind = ButtonKind.Text, onClick = actions.onOtherAddress) { Text(stringResource(R.string.receive_use_address)) }
    }
}

@Composable
private fun InvoiceContent(shown: ShownInvoice, onDone: () -> Unit, onNewInvoice: () -> Unit) {
    val request = shown.request
    when (val status = shown.status) {
        // Shown by SuccessScreen instead.
        is InvoiceStatus.Paid -> Unit
        InvoiceStatus.Expired -> {
            Text(stringResource(R.string.receive_invoice_expired), textAlign = TextAlign.Center)
            PrimaryButton(stringResource(R.string.receive_invoice_new), onNewInvoice)
        }
        InvoiceStatus.Waiting -> {
            QrCode("lightning:${request.invoice.uppercase()}", description = stringResource(R.string.receive_invoice_qr))
            Text(formatAmount(request.amountMsat), style = MaterialTheme.typography.headlineSmall)
            if (request.expectedMsat < request.amountMsat) {
                Text(
                    stringResource(
                        R.string.receive_invoice_fee,
                        formatAmount(request.expectedMsat),
                        formatAmount(request.amountMsat - request.expectedMsat),
                    ),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                stringResource(R.string.receive_invoice_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CopyShare(request.invoice, stringResource(R.string.receive_invoice_copied))
            WalletButton(kind = ButtonKind.Text, onClick = onNewInvoice) { Text(stringResource(R.string.receive_invoice_other_amount)) }
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
        WalletButton(
            kind = ButtonKind.Secondary,
            onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("lightning", text))) }
                copied = true
            },
        ) {
            Text(stringResource(R.string.receive_copy))
        }
        WalletButton(
            kind = ButtonKind.Secondary,
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, null))
            },
        ) {
            Text(stringResource(R.string.receive_share))
        }
    }
    if (copied) Text(copiedMessage, style = MaterialTheme.typography.bodySmall)
}
