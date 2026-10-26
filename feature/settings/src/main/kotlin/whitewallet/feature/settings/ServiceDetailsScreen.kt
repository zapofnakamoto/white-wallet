package whitewallet.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.NumberFormat
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.designsystem.QrCode
import whitewallet.core.designsystem.SensitiveClipboard
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.ServiceDetails
import whitewallet.core.wallet.Wallet

data class ServiceDetailsState(
    val details: ServiceDetails? = null,
    val loading: Boolean = true,
    val watchOnlyKey: String? = null,
    val showWatchOnly: Boolean = false,
)

@HiltViewModel
class ServiceDetailsViewModel @Inject constructor(private val wallet: Wallet, savedState: SavedStateHandle) : ViewModel() {
    /** The service to show: the route's `host`, else the account's home service. */
    private val requestedHost: String? = savedState.get<String>("host")
    private val _state = MutableStateFlow(ServiceDetailsState())
    val state: StateFlow<ServiceDetailsState> = _state

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val host = requestedHost ?: wallet.homeHost.first() ?: return@launch _state.update { it.copy(loading = false) }
            val details = wallet.serviceDetails(host)
            _state.update { it.copy(details = details, loading = false, watchOnlyKey = wallet.watchOnlyKey(details.host)) }
        }
    }

    fun toggleWatchOnly() = _state.update { it.copy(showWatchOnly = !it.showWatchOnly) }

    /** Keeps using the service with the [key] it now presents; if it presents yet another, the refresh shows that. */
    fun acceptKey(key: String) {
        val host = _state.value.details?.host ?: return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            wallet.acceptMintKey(host, key)
            refresh()
        }
    }
}

@Composable
fun ServiceDetailsRoute(onBack: () -> Unit, viewModel: ServiceDetailsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ServiceDetailsScreen(
        state, onBack, viewModel::refresh, viewModel::toggleWatchOnly,
        onCopyWatchOnly = { key -> SensitiveClipboard.copy(context, "watch-only key", key) },
        onAcceptKey = viewModel::acceptKey,
    )
}

/**
 * One service (brief §6): whether it answers, its description, fees and limits, the signing key pinned on first
 * contact (and, if it changed, both keys and a way to use the new one), the account's money there, its website, and
 * the account's watch-only key.
 */
@Composable
fun ServiceDetailsScreen(
    state: ServiceDetailsState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleWatchOnly: () -> Unit,
    onCopyWatchOnly: (String) -> Unit,
    onAcceptKey: (String) -> Unit = {},
) {
    val details = state.details
    Scaffold(topBar = { WalletTopBar(details?.host ?: stringResource(R.string.service_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            if (details == null) {
                if (state.loading) CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                return@Column
            }
            if (details.reachability == ServiceDetails.Reachability.KeyChanged) {
                KeyChangedCard(details.host, details.service?.mintPubkey, details.presentedKey, onAcceptKey.takeUnless { state.loading })
            }
            ListItem(
                headlineContent = { Text(stringResource(reachabilityText(details.reachability))) },
                supportingContent = details.description?.let { { Text(it) } },
                trailingContent = {
                    if (state.loading) CircularProgressIndicator() else TextButton(onClick = onRefresh) { Text(stringResource(R.string.service_check_again)) }
                },
            )
            if (details.isHome) {
                ListItem(headlineContent = { Text(stringResource(R.string.settings_home_mint)) }, supportingContent = { Text(stringResource(R.string.service_home_hint)) })
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.service_your_money)) },
                supportingContent = { Text(pluralStringResource(R.plurals.service_balance, details.noteCount, formatAmount(details.balanceMsat), details.noteCount)) },
            )
            details.service?.let { service ->
                val base = service.feeBaseMsat
                val ppm = service.feePpm
                if (base != null && ppm != null) {
                    val percent = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 4 }.format(ppm / 1_000_000.0)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.service_fees)) },
                        supportingContent = { Text(stringResource(R.string.service_fees_value, formatAmount(base), percent)) },
                    )
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.service_limits)) },
                    supportingContent = { Text(stringResource(R.string.service_limits_value, formatAmount(service.minSendableMsat), formatAmount(service.maxSendableMsat))) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.service_signing_key)) },
                    supportingContent = {
                        Column {
                            Text(service.mintPubkey?.chunked(4)?.joinToString(" ") ?: stringResource(R.string.service_signing_key_unknown), fontFamily = FontFamily.Monospace)
                            Text(stringResource(R.string.service_signing_key_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                )
            }
            val uri = LocalUriHandler.current
            val website = "https://${details.host}"
            ListItem(
                headlineContent = { Text(stringResource(R.string.service_website)) },
                supportingContent = { Text(website) },
                trailingContent = { TextButton(onClick = { runCatching { uri.openUri(website) } }) { Text(stringResource(R.string.service_open)) } },
            )
            state.watchOnlyKey?.let { key ->
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.service_watch_only)) },
                    supportingContent = { Text(stringResource(R.string.service_watch_only_hint)) },
                    trailingContent = {
                        TextButton(onClick = onToggleWatchOnly) { Text(stringResource(if (state.showWatchOnly) R.string.service_hide else R.string.service_show)) }
                    },
                )
                if (state.showWatchOnly) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrCode(key, description = stringResource(R.string.service_watch_only_qr))
                        Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                        OutlinedButton(onClick = { onCopyWatchOnly(key) }) { Text(stringResource(R.string.service_copy)) }
                    }
                }
            }
        }
    }
}

/** The warning for a changed signing key: both keys, and (with [onAccept]) using the new one after a confirmation. */
@Composable
private fun KeyChangedCard(host: String, remembered: String?, presented: String?, onAccept: ((String) -> Unit)?) {
    var confirming by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            val color = MaterialTheme.colorScheme.onErrorContainer
            Text(stringResource(R.string.service_key_changed, host), color = color)
            for ((label, key) in listOf(R.string.service_key_remembered to remembered, R.string.service_key_new to presented)) {
                if (key == null) continue
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 12.dp))
                Text(key.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color)
            }
            if (presented != null && onAccept != null) {
                Button(onClick = { confirming = true }, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.service_key_accept)) }
            }
        }
    }
    if (confirming && presented != null && onAccept != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.service_key_accept_title)) },
            text = { Text(stringResource(R.string.service_key_accept_body, host)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onAccept(presented) }) { Text(stringResource(R.string.service_key_accept_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }
}

private fun reachabilityText(reachability: ServiceDetails.Reachability): Int = when (reachability) {
    ServiceDetails.Reachability.Reachable -> R.string.service_reachable
    ServiceDetails.Reachability.Unreachable -> R.string.service_unreachable
    ServiceDetails.Reachability.Invalid -> R.string.service_invalid
    ServiceDetails.Reachability.KeyChanged -> R.string.service_key_changed_short
}
