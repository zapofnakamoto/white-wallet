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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.ConnectResult
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.OnboardResult
import whitewallet.core.wallet.TrustDefault
import whitewallet.core.wallet.Wallet
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletSwitch
import whitewallet.core.designsystem.WalletTextField

data class MintsState(
    val mints: List<MintInfo> = emptyList(),
    /** A mint to add, as typed. */
    val newHost: String = "",
    val adding: Boolean = false,
    val problem: MintsProblem? = null,
    /** The brand's suggested mints not in the list yet, offered to add with one tap. */
    val offered: List<String> = emptyList(),
    /** The brand lets users add a mint by typing it (`mints.custom`); without, only [offered] ones can be added. */
    val custom: Boolean = true,
) {
    /** Whether there is anything to add: without [custom], only while a suggested mint is missing from the list. */
    val canAdd: Boolean get() = custom || offered.isNotEmpty()
}

sealed interface MintsProblem {
    data object Unreachable : MintsProblem
    data object Invalid : MintsProblem
}

private data class MintsUi(val newHost: String = "", val adding: Boolean = false, val problem: MintsProblem? = null)

/**
 * The mints this device knows, for all accounts: which of them the user trusts (by signing key), whose home each is,
 * and adding one (removing one is on its page): by its address if the brand lets users (`mints.custom`), or one of the
 * brand's suggested mints. Nothing about one account's money: see Account → Mints.
 */
@HiltViewModel
class MintsViewModel @Inject constructor(private val wallet: Wallet, private val defaults: AppDefaults) : ViewModel() {
    private val ui = MutableStateFlow(MintsUi())

    val state: StateFlow<MintsState> = combine(wallet.mints, ui) { mints, u ->
        val known = mints.map { it.host }
        MintsState(mints, u.newHost, u.adding, u.problem, defaults.mintSuggestions.filter { it !in known }, defaults.customMints)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MintsState(custom = defaults.customMints))

    fun setTrusted(mint: MintInfo, trusted: Boolean) {
        if (mint.pubkey == null) return
        viewModelScope.launch { wallet.setMintTrusted(mint.host, trusted) }
    }

    fun onHost(text: String) = ui.update { it.copy(newHost = text.trim(), problem = null) }

    /**
     * Connects to [host], one of the offered mints, or else the typed one (pinning its key); it then shows in the list,
     * untrusted until switched on.
     */
    fun add(host: String = ui.value.newHost) {
        if (host.isBlank() || ui.value.adding || !defaults.mintAllowed(host)) return
        ui.update { it.copy(adding = true, problem = null) }
        viewModelScope.launch {
            val result = wallet.addMint(host)
            ui.update {
                when (result) {
                    // The typed mint is done; one added from the offered ones leaves what's typed as it is.
                    is ConnectResult.Connected -> if (host == it.newHost) MintsUi() else it.copy(adding = false)
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
    MintsScreen(state, onBack, onOpen, viewModel::setTrusted, viewModel::onHost, { viewModel.add() }, { viewModel.add(it) })
}

@Composable
fun MintsScreen(
    state: MintsState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onTrusted: (MintInfo, Boolean) -> Unit,
    onHost: (String) -> Unit,
    onAdd: () -> Unit,
    onAddOffered: (String) -> Unit = {},
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.settings_mints), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).imePadding().fillMaxWidth()) {
            item { Text(stringResource(R.string.mints_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
            items(state.mints, key = { it.host }) { mint -> MintRow(mint, onOpen, onTrusted) }
            if (state.canAdd) item { AddMint(state, onHost, onAdd, onAddOffered) }
        }
    }
}

@Composable
private fun MintRow(mint: MintInfo, onOpen: (String) -> Unit, onTrusted: (MintInfo, Boolean) -> Unit) {
    val hint = MaterialTheme.typography.bodySmall
    WalletListItem(
        headlineContent = { Text(mint.host) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (mint.homeOf.isNotEmpty()) Text(stringResource(R.string.mints_home_mint_of, mint.homeOf.joinToString(", ")), style = hint, color = MaterialTheme.colorScheme.primary)
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
            WalletSwitch(
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
private fun AddMint(state: MintsState, onHost: (String) -> Unit, onAdd: () -> Unit, onAddOffered: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (state.custom) R.string.mints_add_hint else R.string.mints_add_offered_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.offered.forEach { host ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(host, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                WalletButton(kind = ButtonKind.Secondary, onClick = { onAddOffered(host) }, enabled = !state.adding) { Text(stringResource(R.string.mints_add)) }
            }
        }
        if (state.custom) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WalletTextField(
                value = state.newHost,
                onValueChange = onHost,
                label = { Text(stringResource(R.string.mints_add_field)) },
                singleLine = true,
                enabled = !state.adding,
                isError = state.problem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            WalletButton(kind = ButtonKind.Secondary, onClick = onAdd, enabled = state.newHost.isNotBlank() && !state.adding) { Text(stringResource(R.string.mints_add)) }
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
