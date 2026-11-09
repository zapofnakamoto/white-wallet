package whitewallet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletSwitch
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.TrustDefault
import whitewallet.core.wallet.Wallet

data class MintsState(
    val mints: List<MintInfo> = emptyList(),
    /** The brand's suggested mints not in the list yet. */
    val offered: List<String> = emptyList(),
    /** The brand lets users add a mint by typing it (`mints.custom`); without, only [offered] ones can be added. */
    val custom: Boolean = true,
) {
    /** Whether there is anything to add: without [custom], only while a suggested mint is missing from the list. */
    val canAdd: Boolean
        get() = custom || offered.isNotEmpty()
}

/**
 * The active wallet's mints, for all its accounts: which of them the user trusts (by signing key), which is the home, and the way to add
 * one (removing one is on its page): by its address if the brand lets users (`mints.custom`), or one of the brand's suggested mints.
 * Nothing about one account's money: see Account → Mints.
 */
@HiltViewModel
class MintsViewModel @Inject constructor(private val wallet: Wallet, private val defaults: AppDefaults) : ViewModel() {
    val state: StateFlow<MintsState> =
        wallet.mints
            .map { mints ->
                val known = mints.map { it.host }
                MintsState(mints, offered = defaults.mintSuggestions.filter { it !in known }, custom = defaults.customMints)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MintsState(custom = defaults.customMints))

    fun setTrusted(mint: MintInfo, trusted: Boolean) {
        if (mint.pubkey == null) return
        viewModelScope.launch { wallet.setMintTrusted(mint.host, trusted) }
    }
}

@Composable
fun MintsRoute(onBack: () -> Unit, onOpen: (host: String) -> Unit, onAdd: () -> Unit, viewModel: MintsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MintsScreen(state, onBack, onOpen, onTrusted = viewModel::setTrusted, onAdd = onAdd)
}

@Composable
fun MintsScreen(
    state: MintsState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onTrusted: (MintInfo, Boolean) -> Unit,
    onAdd: () -> Unit,
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.settings_mints), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).imePadding().fillMaxWidth()) {
            item {
                Text(stringResource(R.string.mints_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
            items(state.mints, key = { it.host }) { mint -> MintRow(mint, onOpen, onTrusted) }
            if (state.canAdd)
                item {
                    WalletButton(kind = ButtonKind.Secondary, onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.mints_add))
                    }
                }
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
                if (mint.isHome) Text(stringResource(R.string.settings_home_mint), style = hint, color = MaterialTheme.colorScheme.primary)
                Text(
                    mint.pubkey?.let { stringResource(R.string.mints_key, it.take(8) + "…" + it.takeLast(8)) }
                        ?: stringResource(R.string.mints_no_key),
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
                            }
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
