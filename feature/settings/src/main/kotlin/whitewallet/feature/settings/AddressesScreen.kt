package whitewallet.feature.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.Tone
import whitewallet.brand.ui.walletColors
import whitewallet.core.designsystem.QrCode
import whitewallet.core.designsystem.SensitiveClipboard
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCard
import whitewallet.core.designsystem.WalletCheckbox
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.wallet.AddressEntry
import whitewallet.core.wallet.AddressStatus
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.PayResult
import whitewallet.core.wallet.UnregisterResult
import whitewallet.core.wallet.Wallet

data class AddressesState(
    val entries: List<AddressEntry> = emptyList(),
    /** How many addresses the account may hold per mint right now. */
    val limit: Int = 1,
    /** Addresses being checked (by address), or every one while "Check all" runs. */
    val checking: Set<String> = emptySet(),
    val busy: Boolean = false,
    /** An unregister waiting for confirmation. */
    val confirm: RegisteredAddress? = null,
    val message: AddressesMessage? = null,
    /** The address whose watch-only key is showing, and that key. */
    val watchOnly: WatchOnlyKey? = null,
    /** The account's home mint, where new addresses are added. */
    val homeHost: String? = null,
    /**
     * The brand lets users pick other mints (`mints.changeable`) and allows one besides the home (`mints.custom`), so addresses can be
     * added there too.
     */
    val otherMints: Boolean = false,
) {
    /** Addresses on the home mint (the primary address's), where new ones are added. */
    val onPrimaryMint: Int
        get() = entries.count { it.address.host == homeHost }

    val canAdd: Boolean
        get() = !busy && onPrimaryMint < limit

    /** Addresses on other mints, per mint in the order they were added. */
    val elsewhere: Map<String, List<AddressEntry>>
        get() = entries.filter { it.address.host != homeHost }.groupBy { it.address.host }
}

data class WatchOnlyKey(val address: String, val key: String)

sealed interface AddressesMessage {
    data class Unregistered(val address: String) : AddressesMessage

    data class NotOurs(val address: String) : AddressesMessage

    data class Failed(val address: String, val reason: String) : AddressesMessage

    data class Unknown(val address: String, val reason: String) : AddressesMessage

    data class PrimaryChanged(val address: String) : AddressesMessage

    /** Its fee could not be paid from the balance; it stays held. */
    data class FeeNotPaid(val address: String) : AddressesMessage
}

private data class AddressesUi(
    val checking: Set<String> = emptySet(),
    val busy: Boolean = false,
    val confirm: RegisteredAddress? = null,
    val message: AddressesMessage? = null,
    val watchOnly: WatchOnlyKey? = null,
)

@HiltViewModel
class AddressesViewModel @Inject constructor(private val wallet: Wallet, preferences: AppPreferences, defaults: AppDefaults) : ViewModel() {
    private val ui = MutableStateFlow(AddressesUi())
    private val limit = combine(preferences.developerMode, preferences.maxAddressesPerMint, defaults::addressLimit)

    val state: StateFlow<AddressesState> =
        combine(wallet.addresses, limit, ui, wallet.homeHost) { entries, limit, ui, home ->
                AddressesState(
                    entries,
                    limit,
                    ui.checking,
                    ui.busy,
                    ui.confirm,
                    ui.message,
                    ui.watchOnly,
                    homeHost = home,
                    otherMints = defaults.allowMintChange && defaults.otherMintsAllowed(home),
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddressesState())

    /** How soon a copied key is cleared from the clipboard. */
    val clipboardClearMillis = defaults.timings.clipboardClearMillis

    init {
        checkAll()
    }

    fun toggleWatchOnly(address: RegisteredAddress) {
        if (ui.value.watchOnly?.address == address.address) return ui.update { it.copy(watchOnly = null) }
        viewModelScope.launch {
            val key = wallet.watchOnlyKey(address) ?: return@launch
            ui.update { it.copy(watchOnly = WatchOnlyKey(address.address, key)) }
        }
    }

    fun checkAll() = check(null)

    fun check(address: RegisteredAddress?) {
        val keys = address?.let { setOf(it.address) } ?: state.value.entries.map { it.address.address }.toSet().ifEmpty { setOf(ALL) }
        ui.update { it.copy(checking = it.checking + keys) }
        viewModelScope.launch {
            try {
                wallet.checkAddresses(address)
            } finally {
                ui.update { it.copy(checking = it.checking - keys) }
            }
        }
    }

    fun makePrimary(address: RegisteredAddress) = act {
        wallet.setPrimaryAddress(address)
        ui.update { it.copy(message = AddressesMessage.PrimaryChanged(address.address)) }
    }

    fun payFee(address: RegisteredAddress) = act {
        if (wallet.payAddressFee(address) !is PayResult.Paid) ui.update { it.copy(message = AddressesMessage.FeeNotPaid(address.address)) }
    }

    fun askUnregister(address: RegisteredAddress) = ui.update { it.copy(confirm = address, message = null) }

    fun dismissUnregister() = ui.update { it.copy(confirm = null) }

    fun confirmUnregister() {
        val address = ui.value.confirm ?: return
        ui.update { it.copy(confirm = null) }
        act {
            val message =
                when (val r = wallet.unregisterAddress(address)) {
                    UnregisterResult.Unregistered -> AddressesMessage.Unregistered(address.address)
                    UnregisterResult.NotOurs -> AddressesMessage.NotOurs(address.address)
                    UnregisterResult.IsPrimary -> AddressesMessage.Failed(address.address, "primary")
                    is UnregisterResult.Failed -> AddressesMessage.Failed(address.address, r.reason)
                    is UnregisterResult.NetworkError -> AddressesMessage.Unknown(address.address, r.reason)
                }
            ui.update { it.copy(message = message) }
        }
    }

    private fun act(block: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                ui.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        /** Stands for "everything" while the list has not loaded yet. */
        const val ALL = "*"
    }
}

class AddressActions(
    val onCheck: (RegisteredAddress) -> Unit,
    val onMakePrimary: (RegisteredAddress) -> Unit,
    val onUnregister: (RegisteredAddress) -> Unit,
    val onPayFee: (RegisteredAddress) -> Unit = {},
    val onToggleWatchOnly: (RegisteredAddress) -> Unit = {},
    val onCopyWatchOnly: (String) -> Unit = {},
)

@Composable
fun AddressesRoute(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onChangePrimary: () -> Unit,
    onAddElsewhere: () -> Unit = {},
    viewModel: AddressesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    AddressesScreen(
        state,
        onBack,
        onAdd,
        onChangePrimary,
        onCheckAll = viewModel::checkAll,
        actions =
            AddressActions(
                viewModel::check,
                viewModel::makePrimary,
                viewModel::askUnregister,
                viewModel::payFee,
                viewModel::toggleWatchOnly,
                onCopyWatchOnly = { key -> SensitiveClipboard.copy(context, "watch-only key", key, viewModel.clipboardClearMillis) },
            ),
        onConfirmUnregister = viewModel::confirmUnregister,
        onDismissUnregister = viewModel::dismissUnregister,
        onAddElsewhere,
    )
}

/** The active account's Lightning addresses: check them, pick the primary one, add or unregister addresses. */
@Composable
fun AddressesScreen(
    state: AddressesState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onChangePrimary: () -> Unit,
    onCheckAll: () -> Unit,
    actions: AddressActions,
    onConfirmUnregister: () -> Unit,
    onDismissUnregister: () -> Unit,
    onAddElsewhere: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            WalletTopBar(stringResource(R.string.addresses_title), onBack) {
                WalletButton(kind = ButtonKind.Text, onClick = onCheckAll, enabled = state.checking.isEmpty()) {
                    Text(stringResource(R.string.addresses_check_all))
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxWidth()) {
            item {
                Text(
                    stringResource(if (state.entries.isEmpty()) R.string.addresses_none else R.string.addresses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.homeHost?.let { home ->
                item {
                    Text(
                        stringResource(R.string.addresses_home_mint, home),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            state.message?.let { item { MessageText(it) } }
            items(state.entries.filter { it.address.host == state.homeHost }, key = { it.address.address }) { entry ->
                AddressCard(entry, state, actions)
            }
            item { AddSection(state, onAdd, onChangePrimary) }
            state.elsewhere.forEach { (host, entries) ->
                item(key = "host:$host") {
                    Text(
                        host,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    )
                }
                items(entries, key = { it.address.address }) { entry -> AddressCard(entry, state, actions) }
            }
            if (state.otherMints) item { AddElsewhereSection(state, onAddElsewhere) }
        }
    }
    state.confirm?.let { UnregisterDialog(it, onConfirmUnregister, onDismissUnregister) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddressCard(entry: AddressEntry, state: AddressesState, actions: AddressActions) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val a = entry.address
    val checking = a.address in state.checking || "*" in state.checking
    val watchOnly = state.watchOnly?.takeIf { it.address == a.address }
    WalletCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.address, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (entry.primary) {
                    Text(
                        stringResource(R.string.addresses_primary),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (a.feeInvoice != null) {
                Text(stringResource(R.string.addresses_held), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WalletButton(
                        kind = ButtonKind.Text,
                        onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("invoice", a.feeInvoice))) } },
                    ) {
                        Text(stringResource(R.string.addresses_held_copy))
                    }
                    WalletButton(kind = ButtonKind.Text, onClick = { actions.onPayFee(a) }, enabled = !state.busy) {
                        Text(stringResource(R.string.addresses_held_pay))
                    }
                }
                return@Column
            }
            if (checking) Text(stringResource(R.string.addresses_checking), style = MaterialTheme.typography.bodySmall)
            else StatusLine(entry.status)
            if (entry.primary && state.entries.size > 1) {
                Text(
                    stringResource(R.string.addresses_primary_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WalletButton(kind = ButtonKind.Text, onClick = { actions.onCheck(a) }, enabled = !checking) {
                    Text(stringResource(R.string.addresses_check))
                }
                WalletButton(
                    kind = ButtonKind.Text,
                    onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("address", a.address))) } },
                ) {
                    Text(stringResource(R.string.addresses_copy))
                }
                WalletButton(kind = ButtonKind.Text, onClick = { actions.onToggleWatchOnly(a) }) {
                    Text(stringResource(if (watchOnly != null) R.string.mint_hide else R.string.addresses_watch_only))
                }
                if (!entry.primary) {
                    // The primary address is always on the home mint.
                    if (a.host == state.homeHost) {
                        WalletButton(kind = ButtonKind.Text, onClick = { actions.onMakePrimary(a) }, enabled = !state.busy) {
                            Text(stringResource(R.string.addresses_make_primary))
                        }
                    }
                    WalletButton(
                        kind = ButtonKind.Text,
                        onClick = { actions.onUnregister(a) },
                        enabled = !state.busy,
                        tone = Tone.Danger,
                    ) {
                        Text(stringResource(R.string.addresses_unregister))
                    }
                }
            }
            watchOnly?.let { WatchOnlySection(it.key, actions.onCopyWatchOnly) }
        }
    }
}

@Composable
private fun WatchOnlySection(key: String, onCopy: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.addresses_watch_only_hint), style = MaterialTheme.typography.bodySmall)
        QrCode(key, description = stringResource(R.string.addresses_watch_only_qr))
        Text(
            key,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        WalletButton(kind = ButtonKind.Secondary, onClick = { onCopy(key) }) { Text(stringResource(R.string.mint_copy)) }
    }
}

@Composable
private fun StatusLine(status: AddressStatus?) {
    val (text, color) =
        when (status) {
            null -> stringResource(R.string.addresses_not_checked) to MaterialTheme.colorScheme.onSurfaceVariant
            AddressStatus.Ours -> stringResource(R.string.settings_address_active) to MaterialTheme.colorScheme.primary
            AddressStatus.Unconfirmed -> stringResource(R.string.settings_address_unconfirmed) to MaterialTheme.colorScheme.onSurfaceVariant
            AddressStatus.Taken -> stringResource(R.string.addresses_status_taken) to MaterialTheme.colorScheme.error
            AddressStatus.Available -> stringResource(R.string.addresses_status_free) to MaterialTheme.colorScheme.error
            is AddressStatus.Invalid -> status.reason to MaterialTheme.colorScheme.error
            is AddressStatus.CheckFailed ->
                stringResource(R.string.addresses_status_failed, status.reason) to MaterialTheme.colorScheme.error
        }
    Text(text, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun MessageText(message: AddressesMessage) {
    val (text, error) =
        when (message) {
            is AddressesMessage.FeeNotPaid -> stringResource(R.string.addresses_held_not_paid, message.address) to true
            is AddressesMessage.Unregistered -> stringResource(R.string.addresses_unregistered, message.address) to false
            is AddressesMessage.NotOurs -> stringResource(R.string.addresses_not_ours, message.address) to true
            is AddressesMessage.Failed -> stringResource(R.string.addresses_unregister_failed, message.address, message.reason) to true
            is AddressesMessage.Unknown -> stringResource(R.string.addresses_unregister_unknown, message.address, message.reason) to true
            is AddressesMessage.PrimaryChanged -> stringResource(R.string.addresses_primary_changed, message.address) to false
        }
    Text(
        text,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.walletColors.success,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun AddSection(state: AddressesState, onAdd: () -> Unit, onChangePrimary: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Addresses on other mints don't count: the first one here becomes the primary address.
        val first = state.onPrimaryMint == 0
        WalletButton(onClick = onAdd, enabled = state.canAdd, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (first) R.string.addresses_get else R.string.addresses_add))
        }
        if (!first && state.limit != AppDefaults.UNLIMITED) {
            Text(
                if (state.onPrimaryMint > state.limit) {
                    stringResource(R.string.addresses_over_limit, state.limit)
                } else {
                    pluralStringResource(R.plurals.addresses_limit, state.limit, state.onPrimaryMint, state.limit)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!first) {
            WalletButton(
                kind = ButtonKind.Secondary,
                onClick = onChangePrimary,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.addresses_change_primary))
            }
            Text(
                stringResource(R.string.addresses_change_primary_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Unregistering frees the name for anyone, so it takes an explicit acknowledgement on top of the confirm button. */
@Composable
private fun UnregisterDialog(address: RegisteredAddress, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by remember(address) { mutableStateOf(false) }
    WalletDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.addresses_unregister_title, address.address)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.addresses_unregister_body))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WalletCheckbox(checked = understood, onCheckedChange = { understood = it })
                    Text(stringResource(R.string.addresses_unregister_ack), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            WalletButton(
                kind = ButtonKind.Text,
                onClick = onConfirm,
                enabled = understood,
                tone = Tone.Danger,
            ) {
                Text(stringResource(R.string.addresses_unregister))
            }
        },
        dismissButton = { WalletButton(kind = ButtonKind.Text, onClick = onDismiss) { Text(stringResource(R.string.addresses_keep)) } },
    )
}

/** An address on another mint: same recovery phrase, that mint's own keys; payments arrive as notes there. */
@Composable
private fun AddElsewhereSection(state: AddressesState, onAddElsewhere: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WalletButton(kind = ButtonKind.Secondary, onClick = onAddElsewhere, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.addresses_add_elsewhere))
        }
        Text(
            stringResource(R.string.addresses_add_elsewhere_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
