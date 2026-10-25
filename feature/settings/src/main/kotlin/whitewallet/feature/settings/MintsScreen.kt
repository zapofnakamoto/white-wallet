package whitewallet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.ConnectResult
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.OnboardResult
import whitewallet.core.wallet.TrustDefault
import whitewallet.core.wallet.Wallet

data class MintsState(
    val mints: List<MintInfo> = emptyList(),
    /** A mint to add, as typed. */
    val newHost: String = "",
    val adding: Boolean = false,
    val problem: MintsProblem? = null,
)

sealed interface MintsProblem {
    data object Unreachable : MintsProblem
    data object Invalid : MintsProblem
}

private data class MintsUi(val newHost: String = "", val adding: Boolean = false, val problem: MintsProblem? = null)

/** The mints this wallet knows, which of them the user trusts (by signing key), and adding one by its address. */
@HiltViewModel
class MintsViewModel @Inject constructor(private val wallet: Wallet) : ViewModel() {
    private val ui = MutableStateFlow(MintsUi())

    val state: StateFlow<MintsState> = combine(wallet.mints, ui) { mints, u -> MintsState(mints, u.newHost, u.adding, u.problem) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MintsState())

    fun setTrusted(mint: MintInfo, trusted: Boolean) {
        if (mint.pubkey == null) return
        viewModelScope.launch { wallet.setMintTrusted(mint.host, trusted) }
    }

    fun onHost(text: String) = ui.update { it.copy(newHost = text.trim(), problem = null) }

    /** Connects to the typed mint (pinning its key); it then shows in the list, untrusted until switched on. */
    fun add() {
        val host = ui.value.newHost
        if (host.isBlank() || ui.value.adding) return
        ui.update { it.copy(adding = true, problem = null) }
        viewModelScope.launch {
            val result = wallet.addMint(host)
            ui.update {
                when (result) {
                    is ConnectResult.Connected -> MintsUi()
                    is ConnectResult.Failed -> it.copy(
                        adding = false,
                        problem = if (result.result is OnboardResult.NetworkError) MintsProblem.Unreachable else MintsProblem.Invalid,
                    )
                }
            }
        }
    }
}

@Composable
fun MintsRoute(onBack: () -> Unit, onOpen: (host: String) -> Unit, viewModel: MintsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MintsScreen(state, onBack, onOpen, viewModel::setTrusted, viewModel::onHost, viewModel::add)
}

@Composable
fun MintsScreen(
    state: MintsState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onTrusted: (MintInfo, Boolean) -> Unit,
    onHost: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.settings_mints), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).imePadding().fillMaxWidth()) {
            item { Text(stringResource(R.string.mints_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
            items(state.mints, key = { it.host }) { mint -> MintRow(mint, onOpen, onTrusted) }
            item { AddMint(state, onHost, onAdd) }
        }
    }
}

@Composable
private fun MintRow(mint: MintInfo, onOpen: (String) -> Unit, onTrusted: (MintInfo, Boolean) -> Unit) {
    val hint = MaterialTheme.typography.bodySmall
    ListItem(
        headlineContent = { Text(mint.host) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (mint.isHome) Text(stringResource(R.string.settings_home_mint), style = hint, color = MaterialTheme.colorScheme.primary)
                if (mint.noteCount > 0) Text(pluralStringResource(R.plurals.service_balance, mint.noteCount, formatAmount(mint.balanceMsat), mint.noteCount), style = hint)
                Text(
                    mint.pubkey?.let { stringResource(R.string.mints_key, it.take(8) + "…" + it.takeLast(8)) } ?: stringResource(R.string.mints_no_key),
                    style = hint,
                    fontFamily = FontFamily.Monospace,
                )
                mint.trustedByDefault?.let { default ->
                    Text(
                        stringResource(
                            when {
                                !mint.trusted -> R.string.mints_untrusted_default
                                default == TrustDefault.Home -> R.string.mints_default_home
                                else -> R.string.mints_default_brand
                            },
                        ),
                        style = hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            Switch(
                checked = mint.trusted,
                onCheckedChange = { onTrusted(mint, it) },
                enabled = mint.pubkey != null,
                modifier = Modifier,
            )
        },
        modifier = Modifier.clickable { onOpen(mint.host) },
    )
    HorizontalDivider()
}

@Composable
private fun AddMint(state: MintsState, onHost: (String) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mints_add_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.newHost,
                onValueChange = onHost,
                label = { Text(stringResource(R.string.mints_add_field)) },
                singleLine = true,
                enabled = !state.adding,
                isError = state.problem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onAdd, enabled = state.newHost.isNotBlank() && !state.adding) { Text(stringResource(R.string.mints_add)) }
        }
        state.problem?.let {
            Text(
                stringResource(if (it == MintsProblem.Unreachable) R.string.mints_add_unreachable else R.string.mints_add_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
