package whitewallet.feature.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.Page
import whitewallet.core.designsystem.BrandBody
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.core.designsystem.CopyShare
import whitewallet.core.designsystem.EmptyState
import whitewallet.core.designsystem.MintChoiceLine
import whitewallet.core.designsystem.MintChoiceSheet
import whitewallet.core.designsystem.MintOption
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.QrCode
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.SuccessAction
import whitewallet.core.designsystem.SuccessScreen
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCircularProgress
import whitewallet.core.designsystem.WalletStatusScope
import whitewallet.core.designsystem.WalletTextField
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.amountInputLabel
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.wallet.FaucetState
import whitewallet.core.wallet.InvoiceStatus
import whitewallet.core.wallet.NoHome

@Composable
fun ReceiveRoute(
    onBack: () -> Unit,
    onClaim: () -> Unit,
    /** Opens where a mint is added ([NoHome.NoMint]) or the account's home is picked ([NoHome.PickHome]). */
    onNoHome: (NoHome) -> Unit = {},
    /** Opens a mint's details, where a new signing key is accepted. */
    onOpenMint: (String) -> Unit = {},
    /** Opens the Lightning addresses, where one that doesn't reach the wallet any more is fixed. */
    onOpenAddresses: () -> Unit = {},
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
            onReceiveOn = viewModel::receiveOn,
            onOpenMint = onOpenMint,
            onOpenAddresses = onOpenAddresses,
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
    /** Receives on the mint picked. */
    val onReceiveOn: (String) -> Unit = {},
    /** Opens a mint's details (its signing key changed). */
    val onOpenMint: (String) -> Unit = {},
    /** Opens the Lightning addresses (the one shown doesn't reach the wallet any more). */
    val onOpenAddresses: () -> Unit = {},
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
            val payments = state.arrivedPayments
            SuccessScreen(
                anchor = Anchor.ReceiveSuccess,
                status = WalletStatus.PaymentReceived(arrived, payments),
                // One payment keeps the plain title (a brand may word it); several are counted.
                title =
                    if (payments > 1) pluralStringResource(R.plurals.receive_payments_received, payments, payments)
                    else stringResource(R.string.receive_success_title),
                amountMsat = arrived,
                done = SuccessAction(stringResource(DesignR.string.done), onBack),
                more = SuccessAction(stringResource(R.string.receive_more), invoice.onReceiveMore),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        BrandBody(Page.Receive, if (state.noHome != null) WalletStatus.Empty else receiveStatus(state)) {
            ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
                val shown = state.invoice
                WalletStatusScope(receiveStatus(state)) { BrandDecoration(Anchor.ReceiveHeader) }
                var picking by rememberSaveable { mutableStateOf(false) }
                // Which mint it is received on, once there is more than one it could be: changed until an invoice is made for it.
                val mintLine: @Composable () -> Unit = { MintLine(state, onChange = { picking = true }.takeIf { shown == null }) }
                when {
                    state.noHome != null -> NoHomeContent(state.noHome, onNoHome)
                    shown != null -> InvoiceContent(shown, onBack, invoice.onNewInvoice, mintLine)
                    state.showAmountForm -> AmountForm(state, invoice, mintLine)
                    state.address != null -> AddressContent(state, others = state.addressCount > 1, invoice, mintLine)
                }
                if (picking && state.canPickMint) {
                    MintChoiceSheet(
                        title = stringResource(R.string.receive_on_title),
                        options =
                            state.mints.map {
                                MintOption(it.host, it.isHome, mintDetail(it), enabled = !it.keyChanged, keyChanged = it.keyChanged)
                            },
                        selected = state.receivingHost,
                        onSelect = { host ->
                            picking = false
                            host?.let(invoice.onReceiveOn)
                        },
                        onDismiss = { picking = false },
                    )
                }
                if (shown == null) WalletButton(kind = ButtonKind.Text, onClick = onClaim) { Text(stringResource(R.string.receive_claim)) }
                val payable = shown?.status == InvoiceStatus.Waiting || (shown == null && !state.showAmountForm && state.address != null)
                state.faucet?.takeIf { payable }?.let { FaucetButton(it, invoice = shown != null, onPay = invoice.onFaucet) }
            }
        }
    }
}

/** "On mint.example.com · Change": shown where mints are named and there is a mint to pick, or one was picked. */
@Composable
private fun MintLine(state: ReceiveState, onChange: (() -> Unit)?) {
    val host = state.receivingHost ?: return
    if (!state.mintsVisible || !(state.canPickMint || state.on != null)) return
    MintChoiceLine(
        if (host == state.homeHost) stringResource(R.string.receive_on_home, host) else stringResource(R.string.receive_on, host),
        onChange.takeIf { state.canPickMint },
    )
}

/** What [mint] has to receive with: its address there, else an invoice; home said first. */
@Composable
private fun mintDetail(mint: ReceiveMint): String {
    if (mint.keyChanged) return stringResource(DesignR.string.key_mismatch_short)
    val how = mint.address ?: stringResource(R.string.receive_on_invoice)
    return if (mint.isHome) stringResource(R.string.receive_on_home_detail, how) else how
}

/** Without a home mint nothing can be received: what is missing, and the way to it. */
@Composable
private fun NoHomeContent(noHome: NoHome, onNoHome: (NoHome) -> Unit) {
    val mint = noHome == NoHome.NoMint
    EmptyState(stringResource(if (mint) R.string.receive_no_mint else R.string.receive_no_home), padding = PaddingValues()) {
        PrimaryButton(stringResource(if (mint) R.string.receive_no_mint_action else R.string.receive_no_home_action), { onNoHome(noHome) })
    }
}

/** What the screen is about, for the brand's design: waiting, or a problem. What arrived has its own screen. */
private fun receiveStatus(state: ReceiveState): WalletStatus {
    val invoice = state.invoice?.status
    return when {
        invoice == InvoiceStatus.Expired -> WalletStatus.Problem(ProblemKind.Other)
        state.problem == InvoiceProblem.Offline || (state.offline && state.showAmountForm) -> WalletStatus.Problem(ProblemKind.Offline)
        state.problem is InvoiceProblem.MintNotAnswering -> WalletStatus.Problem(ProblemKind.MintNotAnswering)
        state.problem != null -> WalletStatus.Problem(ProblemKind.Other)
        state.invoice == null && state.address != null && !state.showAmountForm && state.addressMintNotAnswering ->
            WalletStatus.Problem(ProblemKind.MintNotAnswering)
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
private fun AddressContent(state: ReceiveState, others: Boolean, actions: InvoiceActions, mintLine: @Composable () -> Unit) {
    val address = state.address ?: return
    QrCode("LIGHTNING:${address.uppercase()}")
    Text(address, style = MaterialTheme.typography.titleMedium)
    // The address still shows: the mint may be back by the time someone pays it, and the screen keeps asking.
    if (state.addressLost) {
        Text(
            stringResource(R.string.receive_address_lost),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        WalletButton(onClick = actions.onOpenAddresses) { Text(stringResource(R.string.receive_address_fix)) }
    } else if (state.addressKeyChanged) {
        KeyChanged(
            state,
            if (state.mintsVisible) stringResource(R.string.receive_address_key_changed, state.addressHost.orEmpty())
            else stringResource(R.string.receive_address_key_changed_unnamed),
            state.addressHost,
            actions.onOpenMint,
        )
    } else if (state.addressHost in state.keyChecking) {
        Checking(state, state.addressHost.orEmpty())
    } else if (state.addressMintNotAnswering) {
        Text(
            if (state.mintsVisible) stringResource(R.string.receive_address_mint_silent, state.addressHost.orEmpty())
            else stringResource(R.string.receive_address_mint_silent_unnamed),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Text(stringResource(R.string.receive_hint), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
    mintLine()
    CopyShare(address, stringResource(R.string.receive_copied))
    WalletButton(kind = ButtonKind.Text, onClick = actions.onRequestAmount) { Text(stringResource(R.string.receive_request_amount)) }
    if (others)
        WalletButton(kind = ButtonKind.Text, onClick = actions.onOtherAddress) { Text(stringResource(R.string.receive_other_address)) }
}

/** The mint's signing key is being asked for, so nothing is made there yet. */
@Composable
private fun Checking(state: ReceiveState, host: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        WalletCircularProgress(Modifier.size(16.dp))
        Text(
            if (state.mintsVisible) stringResource(R.string.receive_key_checking, host)
            else stringResource(R.string.receive_key_checking_unnamed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Why nothing is received on [host] now, with the way to its details where the new key is accepted (where the brand names mints). */
@Composable
private fun KeyChanged(state: ReceiveState, message: String, host: String?, onOpenMint: (String) -> Unit) {
    Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    if (state.mintsVisible && host != null) {
        WalletButton(kind = ButtonKind.Text, onClick = { onOpenMint(host) }) { Text(stringResource(DesignR.string.details)) }
    }
}

@Composable
private fun AmountForm(state: ReceiveState, actions: InvoiceActions, mintLine: @Composable () -> Unit) {
    Text(stringResource(R.string.receive_invoice_intro), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    mintLine()
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
    // Being offline is said as it is, before the user tries, and not as an error once the phone is back.
    if (!state.receivingChecked) Checking(state, state.receivingHost.orEmpty())
    // Known before the user tries, said up front; else as the mint's answer to the attempt.
    val keyChanged = state.receivingHost.takeIf { state.receivingKeyChanged } ?: (state.problem as? InvoiceProblem.KeyChanged)?.host
    keyChanged?.let { host ->
        KeyChanged(
            state,
            if (state.mintsVisible) stringResource(R.string.receive_invoice_key_changed, host)
            else stringResource(R.string.receive_invoice_key_changed_unnamed),
            host,
            actions.onOpenMint,
        )
    }
    state.problem
        ?.takeUnless { it == InvoiceProblem.Offline || it is InvoiceProblem.KeyChanged }
        ?.let { problem ->
            val message =
                when (problem) {
                    is InvoiceProblem.OutOfRange ->
                        stringResource(R.string.receive_invoice_range, formatAmount(problem.minMsat), formatAmount(problem.maxMsat))
                    is InvoiceProblem.MintNotAnswering ->
                        if (state.mintsVisible) stringResource(R.string.receive_invoice_mint_silent, problem.host)
                        else stringResource(R.string.receive_invoice_mint_silent_unnamed)
                    InvoiceProblem.Offline,
                    InvoiceProblem.NoAnswer,
                    InvoiceProblem.Refused,
                    is InvoiceProblem.KeyChanged -> stringResource(R.string.receive_invoice_refused)
                }
            Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    if (state.offline) Text(stringResource(R.string.receive_invoice_offline), textAlign = TextAlign.Center)
    PrimaryButton(
        stringResource(R.string.receive_invoice_create),
        actions.onCreate,
        enabled = state.amountMsat != null && !state.offline && !state.receivingKeyChanged && state.receivingChecked,
        busy = state.creating || !state.receivingChecked,
    )
    if (state.addressCount > 0) {
        WalletButton(kind = ButtonKind.Text, onClick = actions.onOtherAddress) { Text(stringResource(R.string.receive_use_address)) }
    }
}

@Composable
private fun InvoiceContent(shown: ShownInvoice, onDone: () -> Unit, onNewInvoice: () -> Unit, mintLine: @Composable () -> Unit) {
    val request = shown.request
    when (val status = shown.status) {
        // Shown by SuccessScreen instead.
        is InvoiceStatus.Paid -> Unit
        InvoiceStatus.Expired -> {
            Text(stringResource(R.string.receive_invoice_expired), textAlign = TextAlign.Center)
            PrimaryButton(stringResource(R.string.receive_invoice_new), onNewInvoice)
        }
        InvoiceStatus.Waiting -> {
            QrCode("LIGHTNING:${request.invoice.uppercase()}", description = stringResource(R.string.receive_invoice_qr))
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
            mintLine()
            CopyShare(request.invoice, stringResource(R.string.receive_invoice_copied))
            WalletButton(kind = ButtonKind.Text, onClick = onNewInvoice) { Text(stringResource(R.string.receive_invoice_other_amount)) }
        }
    }
}
