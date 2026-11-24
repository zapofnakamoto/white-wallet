package whitewallet.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.NumberFormat
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.AccountActions
import whitewallet.core.designsystem.AccountSwitcher
import whitewallet.core.designsystem.HostText
import whitewallet.core.designsystem.KeyChangedCard
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.WalletAccountButton
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCircularProgress
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletMintMark
import whitewallet.core.designsystem.WalletSnackbarHost
import whitewallet.core.designsystem.WalletSwitch
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.designsystem.screenLoaded
import whitewallet.core.designsystem.showsWorking
import whitewallet.core.model.AccountChoice
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.MintDetails
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.MintUse
import whitewallet.core.wallet.RemoveMintResult
import whitewallet.core.wallet.TrustDefault
import whitewallet.core.wallet.Wallet

data class MintDetailsState(
    val details: MintDetails? = null,
    val loading: Boolean = true,
    /** This page offers changing the home mint ([canChangeHome]). */
    val canChangeHome: Boolean = false,
    /** The account collects at its home mint: money only moves home, so the home's page offers no move. */
    val collects: Boolean = false,
    /** The mint as the wallet knows it: whether it is trusted (and by default), whether it can be removed. */
    val mint: MintInfo? = null,
    /** Why the last removal was refused. */
    val removeRefused: MintUse? = null,
    /** Asked to remove it: the wallet gets to it once it is done with what it works on (a refresh). */
    val removing: Boolean = false,
    val removed: Boolean = false,
    /** The account has money on other mints (on the home's page: "Move all money here"). */
    val moneyElsewhere: Boolean = false,
    /** The mint the page is about, known before it is asked anything: the page's title from the start. */
    val host: String? = null,
    /** [host] is the active account's home mint: its mark's colours. Read with [host], so the title never changes colour. */
    val home: Boolean = false,
    /** The phone is offline: a mint that couldn't be asked isn't said not to answer. */
    val offline: Boolean = false,
    /** The account can receive here: the brand lets the mint hold money, and its signing key is the one pinned. */
    val canReceive: Boolean = false,
    /** The account mark in the top bar, read with [host] and again with [details]: never one account's mark over another's money. */
    val accounts: AccountChoice? = null,
    /** The user just accepted the mint's new signing key: a short message says so, once. */
    val keyAccepted: Boolean = false,
    /** Accepting the new key didn't go through: the mint didn't answer, or shows yet another key. Said once, like [keyAccepted]. */
    val keyNotAccepted: Boolean = false,
)

/**
 * Whether the page of [details] offers changing the home mint (`mints.changeable`): on the home's, when there is another mint the brand
 * allows (`mints.custom`); on another mint's, to it if the brand allows it and its signing key matches (one that doesn't can't be
 * connected, so it isn't offered until it matches again).
 */
internal fun canChangeHome(defaults: AppDefaults, details: MintDetails): Boolean =
    defaults.allowMintChange &&
        if (details.isHome) defaults.otherMintsAllowed(details.host)
        else defaults.mintAllowed(details.host) && details.reachability != MintDetails.Reachability.KeyChanged

@HiltViewModel
class MintDetailsViewModel
@Inject
constructor(private val wallet: Wallet, savedState: SavedStateHandle, private val defaults: AppDefaults) : ViewModel() {
    /** The mint to show: the route's `host`, else the active account's home mint. */
    private val requestedHost: String? = savedState.get<String>("host")
    // The host comes with whether it is home (a moment, from the phone), so the title is drawn once.
    private val _state = MutableStateFlow(MintDetailsState())
    val state: StateFlow<MintDetailsState> = _state

    init {
        refresh()
        // After a change of the home mint (see onChangeHome), back here: this page may be the old or the new home.
        viewModelScope.launch { wallet.homeHost.drop(1).collect { refresh() } }
        // Another account in use (its switcher is in the top bar): its money here, and its home.
        viewModelScope.launch { wallet.activeAccount.map { it?.account?.id }.distinctUntilChanged().drop(1).collect { refresh() } }
        viewModelScope.launch { wallet.collectAtHome.collect { c -> _state.update { it.copy(collects = c) } } }
        viewModelScope.launch {
            wallet.followOffline { offline, back ->
                _state.update { it.copy(offline = offline) }
                // Back online: the mint that couldn't be asked is asked now.
                val unasked = _state.value.details?.reachability == MintDetails.Reachability.Unreachable
                if (back && unasked && !_state.value.loading) refresh()
            }
        }
        viewModelScope.launch {
            wallet.accountMints.collect { mints ->
                _state.update { it.copy(moneyElsewhere = mints.any { m -> !m.isHome && m.balanceMsat > 0 }) }
            }
        }
        viewModelScope.launch {
            wallet.mints.collect { mints ->
                _state.update { s -> s.copy(mint = mints.firstOrNull { it.host == (s.details?.host ?: requestedHost) }) }
            }
        }
    }

    fun refresh() {
        // Removing it clears the home of an account that had it: nothing to show again, the page closes.
        if (_state.value.removing || _state.value.removed) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val home = wallet.homeHost.first()
            val accounts = wallet.accountChoice.first()
            val host = requestedHost ?: home ?: return@launch _state.update { it.copy(loading = false, accounts = accounts) }
            // The mark comes with the title the first time; after a switch, only with the new account's money below.
            _state.update { it.copy(host = host, home = host == home, accounts = it.accounts ?: accounts) }
            val details = wallet.mintDetails(host)
            val mint = wallet.mints.first().firstOrNull { it.host == details.host }
            // Read here too, so the page shows with them: the collectors above may not have had their first value yet.
            val collects = wallet.collectAtHome.first()
            val moneyElsewhere = wallet.accountMints.first().any { !it.isHome && it.balanceMsat > 0 }
            val canChangeHome = canChangeHome(defaults, details)
            val canReceive =
                defaults.mintAllowed(details.host) &&
                    details.mint?.mintPubkey != null &&
                    details.reachability != MintDetails.Reachability.KeyChanged
            _state.update {
                it.copy(
                    details = details,
                    loading = false,
                    mint = mint,
                    canChangeHome = canChangeHome,
                    canReceive = canReceive,
                    collects = collects,
                    moneyElsewhere = moneyElsewhere,
                    accounts = accounts,
                )
            }
        }
    }

    /** Forgets this mint (see [Wallet.removeMint]); the page closes once it's gone. */
    fun remove() {
        val host = _state.value.details?.host ?: return
        if (_state.value.removing) return
        _state.update { it.copy(removing = true, removeRefused = null) }
        viewModelScope.launch {
            when (val r = wallet.removeMint(host)) {
                RemoveMintResult.Removed -> _state.update { it.copy(removed = true) }
                is RemoveMintResult.InUse -> _state.update { it.copy(removing = false, removeRefused = r.reason) }
            }
        }
    }

    /**
     * Trusts or stops trusting the mint, with the signing key the wallet pinned. Not without a key, nor while the mint presents another
     * one: trust is in the key, and a key that doesn't match is decided on first (accept it, or not).
     */
    fun setTrusted(trusted: Boolean) {
        val s = _state.value
        val mint = s.mint ?: return
        if (mint.pubkey == null || s.details?.reachability == MintDetails.Reachability.KeyChanged) return
        viewModelScope.launch {
            if (mint.host in wallet.mintsWithChangedKey.first()) return@launch
            wallet.setMintTrusted(mint.host, trusted)
        }
    }

    /** Makes [accountId] the account in use; the page then shows its money here. */
    fun switchAccount(accountId: String) {
        viewModelScope.launch { wallet.switchAccount(accountId) }
    }

    /** Keeps using the mint with the [key] it now presents and says so; if it presents yet another, the refresh shows that instead. */
    fun acceptKey(key: String) {
        val host = _state.value.details?.host ?: return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val accepted = wallet.acceptMintKey(host, key)
            _state.update { it.copy(keyAccepted = accepted, keyNotAccepted = !accepted) }
            refresh()
        }
    }

    fun keyAcceptedShown() = _state.update { it.copy(keyAccepted = false, keyNotAccepted = false) }
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
    /** Opens Send, paying from [host]. */
    onSend: (host: String) -> Unit = {},
    /** Opens Receive, on [host]. */
    onReceive: (host: String) -> Unit = {},
    /** The account switcher's way to the wallets and accounts, and to moving funds. */
    onManageAccounts: () -> Unit = {},
    onMoveFunds: () -> Unit = {},
    viewModel: MintDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.removed) { if (state.removed) onBack() }
    MintDetailsScreen(
        state,
        onBack,
        onRefresh = viewModel::refresh,
        onAcceptKey = viewModel::acceptKey,
        onKeyAcceptedShown = viewModel::keyAcceptedShown,
        onChangeHome = onChangeHome,
        onMoveMoney = onMoveMoney,
        onMoveAllHere = onMoveAllHere,
        onRemove = viewModel::remove,
        onSend = onSend,
        onReceive = onReceive,
        onTrusted = viewModel::setTrusted,
        accountActions = AccountActions(viewModel::switchAccount, onManageAccounts, onMoveFunds),
    )
}

/**
 * One mint, the same page wherever it is opened (brief §6): whether it answers, its description, what the account in use has and can do
 * there, whether the user trusts it, fees and limits, the signing key pinned on first contact (and, if it changed, both keys and a way to
 * use the new one), its website, and removing it. The account mark in the top bar switches account, as on the Mints page.
 */
@Composable
fun MintDetailsScreen(
    state: MintDetailsState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAcceptKey: (String) -> Unit = {},
    onKeyAcceptedShown: () -> Unit = {},
    onChangeHome: (host: String?) -> Unit = {},
    onMoveMoney: (host: String) -> Unit = {},
    onMoveAllHere: () -> Unit = {},
    onRemove: () -> Unit = {},
    onSend: (host: String) -> Unit = {},
    onReceive: (host: String) -> Unit = {},
    onTrusted: (Boolean) -> Unit = {},
    accountActions: AccountActions = AccountActions({}, {}, {}),
) {
    val details = state.details
    val snackbars = remember { SnackbarHostState() }
    var showAccounts by remember { mutableStateOf(false) }
    // The user stays on the page, which shows the mint as it is now: a short message, the same words as where a mint is added.
    if (state.keyAccepted || state.keyNotAccepted) {
        val host = details?.host ?: state.host.orEmpty()
        val message =
            if (state.keyAccepted) stringResource(DesignR.string.mint_key_accepted, host)
            else stringResource(DesignR.string.mint_key_not_accepted, host)
        LaunchedEffect(Unit) {
            snackbars.showSnackbar(message, duration = SnackbarDuration.Long)
            onKeyAcceptedShown()
        }
    }
    // Which mint it is (the account's home, read from the phone) isn't known yet (a moment): nothing, not a title that changes.
    if (!screenLoaded(details != null || state.host != null || !state.loading)) return
    Scaffold(
        topBar = {
            val host = details?.host ?: state.host
            if (host == null) WalletTopBar(stringResource(R.string.mint_title), onBack)
            else
                WalletTopBar(
                    title = {
                        // The mark beside the name, as in the lists that open this page.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            WalletMintMark(host, state.home)
                            HostText(host, Modifier.semantics { heading() })
                        }
                    },
                    onBack = onBack,
                    actions = { state.accounts?.let { WalletAccountButton(it) { showAccounts = true } } },
                )
        },
        snackbarHost = { WalletSnackbarHost(snackbars) },
    ) { padding ->
        // The mint is asked what it is: nothing of it before it answers.
        if (!pageLoaded(details != null || !state.loading, Modifier.padding(padding))) return@Scaffold
        details ?: return@Scaffold
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            if (details.reachability == MintDetails.Reachability.KeyChanged) {
                KeyChangedCard(
                    details.host,
                    details.mint?.mintPubkey,
                    details.presentedKey,
                    onAcceptKey.takeUnless { state.loading },
                    Modifier.padding(16.dp),
                )
            }
            WalletListItem(
                headlineContent = { Text(stringResource(reachabilityText(details.reachability, state.offline))) },
                supportingContent = details.description?.let { { Text(it) } },
                trailingContent = {
                    if (showsWorking(state.loading)) WalletCircularProgress()
                    else
                        WalletButton(kind = ButtonKind.Text, onClick = onRefresh, enabled = !state.loading) {
                            Text(stringResource(DesignR.string.check_again))
                        }
                },
            )
            AccountSection(details, state, onChangeHome, onMoveMoney, onMoveAllHere, onSend, onReceive)
            state.mint?.let { TrustSection(it, details.reachability == MintDetails.Reachability.KeyChanged, onTrusted) }
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
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.mint_limits_value,
                                formatAmount(mint.minSendableMsat),
                                formatAmount(mint.maxSendableMsat),
                            )
                        )
                    },
                )
                WalletListItem(
                    headlineContent = { Text(stringResource(R.string.mint_signing_key)) },
                    supportingContent = {
                        Column {
                            Text(
                                mint.mintPubkey?.chunked(4)?.joinToString(" ") ?: stringResource(R.string.mint_signing_key_unknown),
                                fontFamily = FontFamily.Monospace,
                            )
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
                trailingContent = {
                    WalletButton(kind = ButtonKind.Text, onClick = { runCatching { uri.openUri(website) } }) {
                        Text(stringResource(R.string.mint_open))
                    }
                },
            )
            state.mint?.let { RemoveSection(it, state.removeRefused, state.removing, onRemove) }
        }
    }
    val accounts = state.accounts
    if (showAccounts && accounts != null) {
        AccountSwitcher(
            accounts = accounts.accounts,
            activeId = accounts.activeId,
            walletNames = accounts.walletNames,
            onSelect = {
                showAccounts = false
                accountActions.onSwitch(it)
            },
            onManage = {
                showAccounts = false
                accountActions.onManage()
            },
            onMove = {
                showAccounts = false
                accountActions.onMove()
            },
            onDismiss = { showAccounts = false },
        )
    }
}

/**
 * Whether the user trusts this mint (notes from it carry a check), with what that means and why it is trusted by default. Trust is in the
 * pinned signing key: not without one, nor while the mint presents another ([keyChanged]): then it isn't trusted, as in the lists, until
 * the new key is accepted, and no default is claimed.
 */
@Composable
private fun TrustSection(mint: MintInfo, keyChanged: Boolean, onTrusted: (Boolean) -> Unit) {
    val hint = MaterialTheme.typography.bodySmall
    val trusted = mint.trusted && !keyChanged
    WalletListItem(
        headlineContent = { Text(stringResource(DesignR.string.notes_trusted)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.mint_trusted_hint))
                mint.trustedByDefault
                    ?.takeUnless { keyChanged }
                    ?.let { default ->
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
                checked = trusted,
                onCheckedChange = onTrusted,
                enabled = mint.pubkey != null && !keyChanged,
                modifier = Modifier,
            )
        },
    )
}

/** What the active account has and can do on this mint: change or make home, its money there, sending and receiving it, moving it. */
@Composable
private fun AccountSection(
    details: MintDetails,
    state: MintDetailsState,
    onChangeHome: (host: String?) -> Unit,
    onMoveMoney: (host: String) -> Unit,
    onMoveAllHere: () -> Unit,
    onSend: (host: String) -> Unit,
    onReceive: (host: String) -> Unit,
) {
    if (details.isHome) {
        WalletListItem(
            headlineContent = { Text(stringResource(R.string.settings_home_mint)) },
            supportingContent = { Text(stringResource(R.string.mint_home_hint)) },
            trailingContent =
                state.canChangeHome
                    .takeIf { it }
                    ?.let {
                        {
                            WalletButton(kind = ButtonKind.Text, onClick = { onChangeHome(null) }) {
                                Text(stringResource(R.string.mint_change_home))
                            }
                        }
                    },
        )
    } else if (state.canChangeHome) {
        WalletButton(
            kind = ButtonKind.Secondary,
            onClick = { onChangeHome(details.host) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.mint_make_home))
        }
    }
    WalletListItem(
        headlineContent = { Text(stringResource(R.string.mint_your_money)) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.mint_balance, details.noteCount, formatAmount(details.balanceMsat), details.noteCount))
        },
        trailingContent =
            (details.balanceMsat > 0 && !(details.isHome && state.collects))
                .takeIf { it }
                ?.let {
                    {
                        WalletButton(kind = ButtonKind.Text, onClick = { onMoveMoney(details.host) }) {
                            Text(stringResource(R.string.mint_move_money))
                        }
                    }
                },
    )
    // Paying from this mint, or receiving on it, rather than where the wallet would. Not while it presents another key: nothing goes out
    // then.
    val canSend = details.balanceMsat > 0 && details.reachability != MintDetails.Reachability.KeyChanged
    if (canSend || state.canReceive) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canSend) {
                WalletButton(kind = ButtonKind.Secondary, onClick = { onSend(details.host) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.mint_send))
                }
            }
            if (state.canReceive) {
                WalletButton(kind = ButtonKind.Secondary, onClick = { onReceive(details.host) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.mint_receive))
                }
            }
        }
    }
    if (details.isHome && state.moneyElsewhere) {
        WalletButton(
            kind = ButtonKind.Secondary,
            onClick = onMoveAllHere,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.mint_move_all_here))
        }
    }
}

/**
 * Removing the mint from this wallet: only when nothing ties an account to it, after a confirmation. The question stays while it is
 * [removing] (the page closes once it's gone); a refusal closes it.
 */
@Composable
private fun RemoveSection(mint: MintInfo, refused: MintUse?, removing: Boolean, onRemove: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(removing) { if (!removing) confirming = false }
    HorizontalDivider()
    if (mint.removable) {
        WalletButton(
            kind = ButtonKind.Text,
            onClick = { confirming = true },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.mints_remove_mint), color = MaterialTheme.colorScheme.error)
        }
    } else {
        WalletListItem(
            headlineContent = { Text(stringResource(R.string.mints_remove_mint)) },
            supportingContent = { Text(stringResource(R.string.mints_remove_in_use)) },
        )
    }
    // Once it isn't removable, the row above says why; the refusal only does for what the row doesn't know (an unfinished payment).
    refused
        ?.takeIf { mint.removable }
        ?.let {
            Text(
                stringResource(R.string.mints_remove_in_use),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    if (confirming) {
        WalletDialog(
            onDismissRequest = { if (!removing) confirming = false },
            title = { Text(stringResource(R.string.mints_remove_title, mint.host)) },
            text = { Text(stringResource(R.string.mints_remove_body)) },
            confirmButton = {
                WalletButton(kind = ButtonKind.Text, onClick = onRemove, busy = removing) {
                    Text(stringResource(R.string.mints_remove_confirm))
                }
            },
            dismissButton = {
                WalletButton(kind = ButtonKind.Text, onClick = { confirming = false }, enabled = !removing) {
                    Text(stringResource(DesignR.string.cancel))
                }
            },
        )
    }
}

/** What [reachability] says; no answer while the phone is [offline] says nothing about the mint. */
private fun reachabilityText(reachability: MintDetails.Reachability, offline: Boolean): Int =
    when (reachability) {
        MintDetails.Reachability.Reachable -> R.string.mint_reachable
        MintDetails.Reachability.Unreachable -> if (offline) R.string.mint_offline else R.string.mint_unreachable
        MintDetails.Reachability.Invalid -> R.string.mint_invalid
        MintDetails.Reachability.KeyChanged -> DesignR.string.key_mismatch_short
    }
