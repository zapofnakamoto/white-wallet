package whitewallet.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.Tone
import whitewallet.core.designsystem.QrCode
import whitewallet.core.designsystem.SensitiveClipboard
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCard
import whitewallet.core.designsystem.WalletCircularProgress
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.MintDetails
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.MintUse
import whitewallet.core.wallet.RemoveMintResult
import whitewallet.core.wallet.Wallet
import java.text.NumberFormat
import javax.inject.Inject

data class MintDetailsState(
    val details: MintDetails? = null,
    val loading: Boolean = true,
    val watchOnlyKey: String? = null,
    val showWatchOnly: Boolean = false,
    /** The brand lets users change their home mint (`allowMintChange`): from its page, or to this one. */
    val canChangeHome: Boolean = false,
    /** The account collects at its home mint: money only moves home, so the home's page offers no move. */
    val collects: Boolean = false,
    /** Opened from Settings → Mints: only the mint itself (whose home it is, removing it), nothing of an account. */
    val device: Boolean = false,
    /** The mint as the device knows it ([device] mode): whose home it is, whether it can be removed. */
    val mint: MintInfo? = null,
    /** Why the last removal was refused. */
    val removeRefused: MintUse? = null,
    val removed: Boolean = false,
    /** The account has money on other mints (on the home's page: "Move all money here"). */
    val moneyElsewhere: Boolean = false,
)

@HiltViewModel
class MintDetailsViewModel @Inject constructor(private val wallet: Wallet, savedState: SavedStateHandle, defaults: AppDefaults) : ViewModel() {
    /** The mint to show: the route's `host`, else the account's home mint. */
    private val requestedHost: String? = savedState.get<String>("host")
    private val _state = MutableStateFlow(MintDetailsState(canChangeHome = defaults.allowMintChange, device = savedState.get<Boolean>("device") ?: false))
    val state: StateFlow<MintDetailsState> = _state

    init {
        refresh()
        // After a change of the home mint (see onChangeHome), back here: this page may be the old or the new home.
        viewModelScope.launch { wallet.homeHost.drop(1).collect { refresh() } }
        viewModelScope.launch { wallet.collectAtHome.collect { c -> _state.update { it.copy(collects = c) } } }
        viewModelScope.launch {
            wallet.accountMints.collect { mints -> _state.update { it.copy(moneyElsewhere = mints.any { m -> !m.isHome && m.balanceMsat > 0 }) } }
        }
        viewModelScope.launch {
            wallet.mints.collect { mints -> _state.update { s -> s.copy(mint = mints.firstOrNull { it.host == (s.details?.host ?: requestedHost) }) } }
        }
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val host = requestedHost ?: wallet.homeHost.first() ?: return@launch _state.update { it.copy(loading = false) }
            val details = wallet.mintDetails(host)
            val mint = wallet.mints.first().firstOrNull { it.host == details.host }
            _state.update { it.copy(details = details, loading = false, watchOnlyKey = wallet.watchOnlyKey(details.host), mint = mint) }
        }
    }

    fun toggleWatchOnly() = _state.update { it.copy(showWatchOnly = !it.showWatchOnly) }

    /** Forgets this mint (see [Wallet.removeMint]); the page closes once it's gone. */
    fun remove() {
        val host = _state.value.details?.host ?: return
        viewModelScope.launch {
            when (val r = wallet.removeMint(host)) {
                RemoveMintResult.Removed -> _state.update { it.copy(removed = true) }
                is RemoveMintResult.InUse -> _state.update { it.copy(removeRefused = r.reason) }
            }
        }
    }

    /** Keeps using the mint with the [key] it now presents; if it presents yet another, the refresh shows that. */
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
fun MintDetailsRoute(
    onBack: () -> Unit,
    /** Opens the change of the home mint: to [host] when it is given, else to one the user picks. */
    onChangeHome: (host: String?) -> Unit = {},
    /** Opens moving the account's money on [host] to another known mint. */
    onMoveMoney: (host: String) -> Unit = {},
    /** Opens moving all of the account's money on other mints to its home mint. */
    onMoveAllHere: () -> Unit = {},
    viewModel: MintDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.removed) { if (state.removed) onBack() }
    MintDetailsScreen(
        state, onBack, viewModel::refresh, viewModel::toggleWatchOnly,
        onCopyWatchOnly = { key -> SensitiveClipboard.copy(context, "watch-only key", key) },
        onAcceptKey = viewModel::acceptKey,
        onChangeHome = onChangeHome,
        onMoveMoney = onMoveMoney,
        onMoveAllHere = onMoveAllHere,
        onRemove = viewModel::remove,
    )
}

/**
 * One mint (brief §6): whether it answers, its description, fees and limits, the signing key pinned on first
 * contact (and, if it changed, both keys and a way to use the new one), the account's money there, its website, and
 * the account's watch-only key.
 */
@Composable
fun MintDetailsScreen(
    state: MintDetailsState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleWatchOnly: () -> Unit,
    onCopyWatchOnly: (String) -> Unit,
    onAcceptKey: (String) -> Unit = {},
    onChangeHome: (host: String?) -> Unit = {},
    onMoveMoney: (host: String) -> Unit = {},
    onMoveAllHere: () -> Unit = {},
    onRemove: () -> Unit = {},
) {
    val details = state.details
    Scaffold(topBar = { WalletTopBar(details?.host ?: stringResource(R.string.mint_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            if (details == null) {
                if (state.loading) WalletCircularProgress(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                return@Column
            }
            if (details.reachability == MintDetails.Reachability.KeyChanged) {
                KeyChangedCard(details.host, details.mint?.mintPubkey, details.presentedKey, onAcceptKey.takeUnless { state.loading })
            }
            WalletListItem(
                headlineContent = { Text(stringResource(reachabilityText(details.reachability))) },
                supportingContent = details.description?.let { { Text(it) } },
                trailingContent = {
                    if (state.loading) WalletCircularProgress() else WalletButton(kind = ButtonKind.Text, onClick = onRefresh) { Text(stringResource(R.string.mint_check_again)) }
                },
            )
            if (state.device) {
                state.mint?.homeOf?.takeIf { it.isNotEmpty() }?.let { homes ->
                    WalletListItem(headlineContent = { Text(stringResource(R.string.mints_home_mint_of, homes.joinToString(", "))) })
                }
            } else {
                AccountSection(details, state, onChangeHome, onMoveMoney, onMoveAllHere)
            }
            details.mint?.let { mint ->
                val base = mint.feeBaseMsat
                val ppm = mint.feePpm
                if (base != null && ppm != null) {
                    val percent = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 4 }.format(ppm / 1_000_000.0)
                    WalletListItem(
                        headlineContent = { Text(stringResource(R.string.mint_fees)) },
                        supportingContent = { Text(stringResource(R.string.mint_fees_value, formatAmount(base), percent)) },
                    )
                }
                WalletListItem(
                    headlineContent = { Text(stringResource(R.string.mint_limits)) },
                    supportingContent = { Text(stringResource(R.string.mint_limits_value, formatAmount(mint.minSendableMsat), formatAmount(mint.maxSendableMsat))) },
                )
                WalletListItem(
                    headlineContent = { Text(stringResource(R.string.mint_signing_key)) },
                    supportingContent = {
                        Column {
                            Text(mint.mintPubkey?.chunked(4)?.joinToString(" ") ?: stringResource(R.string.mint_signing_key_unknown), fontFamily = FontFamily.Monospace)
                            Text(stringResource(R.string.mint_signing_key_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                )
            }
            val uri = LocalUriHandler.current
            val website = "https://${details.host}"
            WalletListItem(
                headlineContent = { Text(stringResource(R.string.mint_website)) },
                supportingContent = { Text(website) },
                trailingContent = { WalletButton(kind = ButtonKind.Text, onClick = { runCatching { uri.openUri(website) } }) { Text(stringResource(R.string.mint_open)) } },
            )
            if (state.device) state.mint?.let { RemoveSection(it, state.removeRefused, onRemove) }
            state.watchOnlyKey?.takeUnless { state.device }?.let { key ->
                HorizontalDivider()
                WalletListItem(
                    headlineContent = { Text(stringResource(R.string.mint_watch_only)) },
                    supportingContent = { Text(stringResource(R.string.mint_watch_only_hint)) },
                    trailingContent = {
                        WalletButton(kind = ButtonKind.Text, onClick = onToggleWatchOnly) { Text(stringResource(if (state.showWatchOnly) R.string.mint_hide else R.string.mint_show)) }
                    },
                )
                if (state.showWatchOnly) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrCode(key, description = stringResource(R.string.mint_watch_only_qr))
                        Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                        WalletButton(kind = ButtonKind.Secondary, onClick = { onCopyWatchOnly(key) }) { Text(stringResource(R.string.mint_copy)) }
                    }
                }
            }
        }
    }
}

/** What the active account has and can do on this mint: change or make home, its money there, moving it. */
@Composable
private fun AccountSection(
    details: MintDetails,
    state: MintDetailsState,
    onChangeHome: (host: String?) -> Unit,
    onMoveMoney: (host: String) -> Unit,
    onMoveAllHere: () -> Unit,
) {
    if (details.isHome) {
        WalletListItem(
            headlineContent = { Text(stringResource(R.string.settings_home_mint)) },
            supportingContent = { Text(stringResource(R.string.mint_home_hint)) },
            trailingContent = state.canChangeHome.takeIf { it }?.let {
                { WalletButton(kind = ButtonKind.Text, onClick = { onChangeHome(null) }) { Text(stringResource(R.string.mint_change_home)) } }
            },
        )
    } else if (state.canChangeHome) {
        WalletButton(kind = ButtonKind.Secondary, onClick = { onChangeHome(details.host) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.mint_make_home))
        }
    }
    WalletListItem(
        headlineContent = { Text(stringResource(R.string.mint_your_money)) },
        supportingContent = { Text(pluralStringResource(R.plurals.mint_balance, details.noteCount, formatAmount(details.balanceMsat), details.noteCount)) },
        trailingContent = (details.balanceMsat > 0 && !(details.isHome && state.collects)).takeIf { it }?.let {
            { WalletButton(kind = ButtonKind.Text, onClick = { onMoveMoney(details.host) }) { Text(stringResource(R.string.mint_move_money)) } }
        },
    )
    if (details.isHome && state.moneyElsewhere) {
        WalletButton(kind = ButtonKind.Secondary, onClick = onMoveAllHere, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.mint_move_all_here))
        }
    }
}

/** Removing the mint from this device: only when nothing ties an account to it, after a confirmation. */
@Composable
private fun RemoveSection(mint: MintInfo, refused: MintUse?, onRemove: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    HorizontalDivider()
    if (mint.removable) {
        WalletButton(kind = ButtonKind.Text, onClick = { confirming = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.mints_remove_mint), color = MaterialTheme.colorScheme.error)
        }
    } else {
        WalletListItem(
            headlineContent = { Text(stringResource(R.string.mints_remove_mint)) },
            supportingContent = { Text(stringResource(R.string.mints_remove_in_use)) },
        )
    }
    refused?.let { Text(stringResource(R.string.mints_remove_in_use), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
    if (confirming) {
        WalletDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.mints_remove_title, mint.host)) },
            text = { Text(stringResource(R.string.mints_remove_body)) },
            confirmButton = {
                WalletButton(kind = ButtonKind.Text, onClick = { confirming = false; onRemove() }) { Text(stringResource(R.string.mints_remove_confirm)) }
            },
            dismissButton = { WalletButton(kind = ButtonKind.Text, onClick = { confirming = false }) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }
}

/** The warning for a changed signing key: both keys, and (with [onAccept]) using the new one after a confirmation. */
@Composable
private fun KeyChangedCard(host: String, remembered: String?, presented: String?, onAccept: ((String) -> Unit)?) {
    var confirming by remember { mutableStateOf(false) }
    WalletCard(
        tone = Tone.Warning,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            val color = MaterialTheme.colorScheme.onErrorContainer
            Text(stringResource(R.string.mint_key_changed, host), color = color)
            for ((label, key) in listOf(R.string.mint_key_remembered to remembered, R.string.mint_key_new to presented)) {
                if (key == null) continue
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 12.dp))
                Text(key.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color)
            }
            if (presented != null && onAccept != null) {
                WalletButton(onClick = { confirming = true }, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.mint_key_accept)) }
            }
        }
    }
    if (confirming && presented != null && onAccept != null) {
        WalletDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.mint_key_accept_title)) },
            text = { Text(stringResource(R.string.mint_key_accept_body, host)) },
            confirmButton = {
                WalletButton(kind = ButtonKind.Text, onClick = { confirming = false; onAccept(presented) }) { Text(stringResource(R.string.mint_key_accept_confirm)) }
            },
            dismissButton = { WalletButton(kind = ButtonKind.Text, onClick = { confirming = false }) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }
}

private fun reachabilityText(reachability: MintDetails.Reachability): Int = when (reachability) {
    MintDetails.Reachability.Reachable -> R.string.mint_reachable
    MintDetails.Reachability.Unreachable -> R.string.mint_unreachable
    MintDetails.Reachability.Invalid -> R.string.mint_invalid
    MintDetails.Reachability.KeyChanged -> R.string.mint_key_changed_short
}
