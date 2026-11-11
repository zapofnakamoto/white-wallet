package whitewallet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.launch
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.Tone
import whitewallet.core.designsystem.ChevronIcon
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.WalletAccountMark
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletListIcon
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletTextField
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.WalletGroup
import whitewallet.core.wallet.AccountRemoval
import whitewallet.core.wallet.AccountUse
import whitewallet.core.wallet.RemoveAccountResult
import whitewallet.core.wallet.Wallet

/** Every wallet on the phone with its accounts, and which account is in use. */
data class WalletsState(
    val wallets: List<WalletGroup> = emptyList(),
    val activeAccountId: String? = null,
    /** Each account's place among all accounts, oldest first: its mark's colour. */
    val places: Map<String, Int> = emptyMap(),
    /** The app may hold another wallet, and the active wallet another account (the brand's limits). */
    val canAddWallet: Boolean = false,
    val canAddAccount: Boolean = false,
    /** False until the wallets were read: a page of a wallet that isn't there closes only once this is true. */
    val loaded: Boolean = true,
) {
    val activeWalletId: String?
        get() = wallets.firstOrNull { group -> group.accounts.any { it.account.id == activeAccountId } }?.wallet?.id

    fun wallet(id: String): WalletGroup? = wallets.firstOrNull { it.wallet.id == id }

    fun account(id: String): AccountSummary? = wallets.firstNotNullOfOrNull { group -> group.accounts.firstOrNull { it.account.id == id } }
}

@HiltViewModel
class WalletsViewModel @Inject constructor(private val wallet: Wallet) : ViewModel() {
    private val limits = combine(wallet.canAddWallet, wallet.canAddAccount, ::Pair)

    val state: StateFlow<WalletsState> =
        combine(wallet.walletGroups, wallet.accounts, wallet.activeAccount, limits) { groups, accounts, active, (addWallet, addAccount) ->
                WalletsState(
                    wallets = groups.filter { it.accounts.isNotEmpty() },
                    activeAccountId = active?.account?.id,
                    places = accounts.mapIndexed { place, summary -> summary.account.id to place }.toMap(),
                    canAddWallet = addWallet,
                    canAddAccount = addAccount,
                    loaded = true,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletsState(loaded = false))

    /** Makes [accountId] the account in use: from then on its pages show everything of it. */
    fun use(accountId: String) {
        viewModelScope.launch { wallet.switchAccount(accountId) }
    }

    fun renameWallet(name: String) {
        viewModelScope.launch { wallet.renameWallet(name) }
    }

    fun renameAccount(name: String) {
        viewModelScope.launch { wallet.renameAccount(name) }
    }

    private val _removal = MutableStateFlow<AccountRemoval?>(null)

    /** What removing the account in use would meet, while the user is asked about it. */
    val removal: StateFlow<AccountRemoval?> = _removal

    fun askRemoval() {
        viewModelScope.launch { _removal.value = wallet.accountRemoval() }
    }

    fun dismissRemoval() {
        _removal.value = null
    }

    /** Removes the account in use; if something stands against it by now, the question shows that instead. */
    fun removeAccount() {
        viewModelScope.launch {
            val result = wallet.removeAccount()
            _removal.value = if (result is RemoveAccountResult.Removed) null else wallet.accountRemoval()
        }
    }
}

/** A wallet's name: the user's, or its number among the wallets. */
@Composable internal fun walletName(name: String?, number: Int): String = name ?: stringResource(R.string.wallet_numbered, number)

/** Settings → Wallets. With a single wallet its page shows at once, "Add wallet" on it: a list of one is a tap for nothing. */
@Composable
fun WalletsRoute(
    onBack: () -> Unit,
    onOpen: (walletId: String) -> Unit,
    onAdd: () -> Unit,
    onNavigate: (SettingsDestination) -> Unit,
    onAddAccount: () -> Unit,
    viewModel: WalletsViewModel = hiltViewModel(),
    settings: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val active by settings.state.collectAsStateWithLifecycle()
    val only = state.wallets.singleOrNull()
    when {
        // Both are read first (a moment): a page drawn from one of them alone would change under the eye.
        !state.loaded || !active.loaded -> Unit
        only != null ->
            WalletScreen(
                only,
                state,
                active,
                onBack,
                onNavigate,
                onAddAccount,
                viewModel::renameWallet,
                onAdd.takeIf { state.canAddWallet },
            )
        else -> WalletsScreen(state, onBack, onOpen, onAdd)
    }
}

/** Settings → Wallets: every wallet (recovery phrase) on the phone, the one in use marked, and the way to add one. */
@Composable
fun WalletsScreen(state: WalletsState, onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.settings_wallets), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.wallets_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            state.wallets.forEach { group ->
                WalletListItem(
                    leadingContent = { WalletListIcon(DesignR.drawable.ic_wallet) },
                    headlineContent = { Text(walletName(group.wallet.name, group.number)) },
                    supportingContent = {
                        Column {
                            Text(pluralStringResource(R.plurals.wallets_accounts, group.accounts.size, group.accounts.size))
                            if (group.wallet.id == state.activeWalletId) InUse()
                        }
                    },
                    trailingContent = { AmountAndChevron(group.balanceMsat) },
                    modifier = Modifier.clickable { onOpen(group.wallet.id) },
                )
                HorizontalDivider()
            }
            if (state.canAddWallet) AddButton(R.string.wallets_add, onAdd)
        }
    }
}

@Composable
fun WalletRoute(
    walletId: String,
    onBack: () -> Unit,
    onNavigate: (SettingsDestination) -> Unit,
    onAddAccount: () -> Unit,
    viewModel: WalletsViewModel = hiltViewModel(),
    settings: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val active by settings.state.collectAsStateWithLifecycle()
    val group = state.wallet(walletId)
    // Deleted meanwhile: nothing to show.
    LaunchedEffect(state.loaded, group == null) { if (state.loaded && group == null) onBack() }
    if (group != null && active.loaded) WalletScreen(group, state, active, onBack, onNavigate, onAddAccount, viewModel::renameWallet)
}

/**
 * A wallet's page: its accounts, and, while it is the wallet in use, what belongs to the recovery phrase (its name, the phrase, its mints,
 * adding an account, deleting it). A wallet not in use only lists its accounts: only the active wallet is ever used. [onAddWallet]: it is
 * the only wallet, shown in place of the list, and another may be added.
 */
@Composable
fun WalletScreen(
    group: WalletGroup,
    state: WalletsState,
    active: SettingsState,
    onBack: () -> Unit,
    onNavigate: (SettingsDestination) -> Unit,
    onAddAccount: () -> Unit,
    onRename: (String) -> Unit,
    onAddWallet: (() -> Unit)? = null,
) {
    val inUse = group.wallet.id == state.activeWalletId
    val name = walletName(group.wallet.name, group.number)
    val open = { target: SettingsTarget -> onNavigate(SettingsDestination.Page(target)) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { WalletTopBar(name, onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            if (inUse) {
                SummaryRow(pageIcon(DesignR.drawable.ic_wallet), stringResource(R.string.wallet_name), name) { renaming = true }
                SummaryRow(
                    pageIcon(SettingsTarget.Backup, if (active.backupVerified) Tone.Neutral else Tone.Danger),
                    stringResource(R.string.settings_recovery_phrase),
                    stringResource(if (active.backupVerified) R.string.settings_backed_up else R.string.settings_not_backed_up),
                    if (active.backupVerified) Color.Unspecified else MaterialTheme.colorScheme.error,
                ) {
                    open(SettingsTarget.Backup)
                }
                if (SettingsTarget.Mints !in active.hidden) {
                    SummaryRow(
                        pageIcon(SettingsTarget.Mints),
                        stringResource(R.string.settings_mints),
                        pluralStringResource(R.plurals.settings_mints_summary, active.mintCount, active.mintCount, active.trustedCount),
                    ) {
                        open(SettingsTarget.Mints)
                    }
                }
                val unscanned = active.unscannedCount
                SummaryRow(
                    pageIcon(SettingsTarget.Scan, if (unscanned == 0) Tone.Neutral else Tone.Danger),
                    stringResource(R.string.settings_scan),
                    stringResource(if (unscanned == 0) R.string.settings_scan_done else R.string.settings_scan_unfinished),
                    if (unscanned == 0) Color.Unspecified else MaterialTheme.colorScheme.error,
                ) {
                    open(SettingsTarget.Scan)
                }
                HorizontalDivider()
            } else {
                Text(
                    stringResource(R.string.wallet_not_in_use),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Text(
                stringResource(R.string.wallet_accounts),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            group.accounts.forEach { summary ->
                val account = summary.account
                val current = account.id == state.activeAccountId
                WalletListItem(
                    leadingContent = { WalletAccountMark(account.name, state.places[account.id] ?: 0, active = current) },
                    headlineContent = { Text(account.name) },
                    supportingContent =
                        if (current) {
                            {
                                Column {
                                    InUse()
                                    active.address?.let { Text(it) }
                                }
                            }
                        } else {
                            null
                        },
                    trailingContent = { AmountAndChevron(summary.balanceMsat) },
                    modifier = Modifier.clickable { onNavigate(SettingsDestination.Account(account.id)) },
                )
            }
            if (inUse && state.canAddAccount) AddButton(R.string.wallet_add_account, onAddAccount)
            if (inUse || onAddWallet != null) HorizontalDivider()
            if (onAddWallet != null) {
                SummaryRow(
                    pageIcon(SettingsTarget.Wallets),
                    stringResource(R.string.wallets_add),
                    stringResource(R.string.wallets_add_hint),
                    onClick = onAddWallet,
                )
            }
            if (inUse) {
                SummaryRow(
                    pageIcon(SettingsTarget.Delete, Tone.Danger),
                    stringResource(R.string.settings_delete),
                    stringResource(R.string.settings_delete_hint),
                    titleColor = MaterialTheme.colorScheme.error,
                ) {
                    open(SettingsTarget.Delete)
                }
            }
        }
    }
    if (renaming) {
        RenameDialog(
            title = stringResource(R.string.wallet_rename_title),
            hint = stringResource(R.string.wallet_rename_hint),
            current = group.wallet.name.orEmpty(),
            allowEmpty = true,
            onDismiss = { renaming = false },
        ) {
            renaming = false
            onRename(it)
        }
    }
}

@Composable
fun AccountSettingsRoute(
    accountId: String,
    onBack: () -> Unit,
    onOpen: (SettingsTarget) -> Unit,
    viewModel: WalletsViewModel = hiltViewModel(),
    settings: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val active by settings.state.collectAsStateWithLifecycle()
    val removal by viewModel.removal.collectAsStateWithLifecycle()
    val summary = state.account(accountId)
    // Removed meanwhile: nothing to show.
    LaunchedEffect(state.loaded, summary == null) { if (state.loaded && summary == null) onBack() }
    when {
        summary == null || !active.loaded -> Unit
        accountId == state.activeAccountId -> {
            // A wallet's only account goes with the wallet, on the wallet's page.
            val siblings = state.wallet(summary.account.walletId)?.accounts?.size ?: 1
            AccountSettingsScreen(active, onBack, onOpen, viewModel::renameAccount, viewModel::askRemoval.takeIf { siblings > 1 })
            removal?.let { AccountRemovalDialog(summary.account.name, it, viewModel::dismissRemoval, viewModel::removeAccount) }
        }
        else -> AccountNotInUseScreen(summary, state.places[accountId] ?: 0, onBack) { viewModel.use(accountId) }
    }
}

/**
 * Everything that belongs to the account in use: its name, addresses, mints (its home among them), notes and note settings, and, with
 * [onRemove], the way to take it off this phone.
 */
@Composable
fun AccountSettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    open: (SettingsTarget) -> Unit,
    onRename: (String) -> Unit = {},
    onRemove: (() -> Unit)? = null,
) {
    val name = state.accountName.orEmpty()
    var renaming by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { WalletTopBar(name, onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SummaryRow({ WalletAccountMark(name, state.accountPlace) }, stringResource(R.string.wallet_name), name) { renaming = true }
            val address = state.address
            if (address != null) {
                val (status, color) = addressSummary(state.addressStatus)
                val summary =
                    if (state.otherAddresses > 0) {
                        pluralStringResource(R.plurals.settings_address_with_more, state.otherAddresses, address, state.otherAddresses)
                    } else {
                        address
                    }
                SummaryRow(
                    leading = pageIcon(SettingsTarget.Addresses),
                    title = stringResource(R.string.addresses_title),
                    summary,
                    extra = status,
                    extraColor = color,
                ) {
                    open(SettingsTarget.Addresses)
                }
            } else {
                SummaryRow(
                    pageIcon(SettingsTarget.Addresses),
                    stringResource(R.string.addresses_title),
                    stringResource(R.string.settings_no_address),
                ) {
                    open(SettingsTarget.Addresses)
                }
            }
            state.homeHost
                ?.takeIf { SettingsTarget.AccountMints !in state.hidden }
                ?.let { host ->
                    SummaryRow(
                        pageIcon(SettingsTarget.AccountMints),
                        stringResource(R.string.settings_account_mints),
                        stringResource(R.string.addresses_home_mint, host),
                    ) {
                        open(SettingsTarget.AccountMints)
                    }
                }
            if (SettingsTarget.NoteList !in state.hidden) {
                SummaryRow(
                    pageIcon(SettingsTarget.NoteList),
                    stringResource(R.string.settings_note_list),
                    stringResource(R.string.settings_note_list_hint),
                ) {
                    open(SettingsTarget.NoteList)
                }
            }
            if (SettingsTarget.Notes !in state.hidden) {
                SummaryRow(
                    leading = pageIcon(SettingsTarget.Notes),
                    title = stringResource(R.string.settings_notes),
                    summary = notesSummary(state),
                    extra = state.lastPolicyRun?.stoppedEarly?.let { stringResource(R.string.settings_policy_stopped, it) },
                    extraColor = MaterialTheme.colorScheme.error,
                ) {
                    open(SettingsTarget.Notes)
                }
            }
            // The keys are this account's: the developer page sits with it, while developer mode is on.
            if (state.developerMode && SettingsTarget.Developer !in state.hidden) {
                SummaryRow(
                    pageIcon(SettingsTarget.Keys),
                    stringResource(R.string.keys_title),
                    stringResource(R.string.keys_description),
                ) {
                    open(SettingsTarget.Keys)
                }
            }
            if (onRemove != null) {
                HorizontalDivider()
                SummaryRow(
                    pageIcon(SettingsTarget.Delete, Tone.Danger),
                    stringResource(R.string.account_remove),
                    stringResource(R.string.account_remove_hint),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = onRemove,
                )
            }
        }
    }
    if (renaming) {
        RenameDialog(
            title = stringResource(R.string.account_rename_title),
            hint = stringResource(R.string.account_rename_hint),
            current = name,
            allowEmpty = false,
            onDismiss = { renaming = false },
        ) {
            renaming = false
            onRename(it)
        }
    }
}

/**
 * Asked before the account [name] is removed: what goes and which addresses it leaves behind, or, if it still holds something, why it can't
 * go yet.
 */
@Composable
internal fun AccountRemovalDialog(name: String, removal: AccountRemoval, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val blocked = removal.blocked
    if (blocked != null) {
        val holdsMoney = (blocked as? RemoveAccountResult.InUse)?.use == AccountUse.Money && removal.heldMsat > 0
        WalletDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                WalletButton(kind = ButtonKind.Text, onClick = onDismiss) { Text(stringResource(R.string.account_remove_ok)) }
            },
            title = { Text(stringResource(R.string.account_remove_blocked_title)) },
            text = {
                Text(
                    when {
                        blocked is RemoveAccountResult.LastAccount -> stringResource(R.string.account_remove_blocked_last)
                        holdsMoney -> stringResource(R.string.account_remove_blocked_money, formatAmount(removal.heldMsat))
                        else -> stringResource(R.string.account_remove_blocked_open)
                    }
                )
            },
        )
        return
    }
    WalletDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            WalletButton(kind = ButtonKind.Text, onClick = onConfirm) {
                Text(stringResource(R.string.account_remove_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { WalletButton(kind = ButtonKind.Text, onClick = onDismiss) { Text(stringResource(R.string.rename_cancel)) } },
        title = { Text(stringResource(R.string.account_remove_title, name)) },
        text = {
            Column {
                Text(stringResource(R.string.account_remove_body))
                if (removal.addresses.isNotEmpty()) {
                    val count = removal.addresses.size
                    Text(
                        pluralStringResource(R.plurals.account_remove_addresses, count, removal.addresses.joinToString(", ")),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
    )
}

/** An account that isn't in use: what the list knows of it, and the way to use it. Its pages work on the account in use only. */
@Composable
fun AccountNotInUseScreen(summary: AccountSummary, place: Int, onBack: () -> Unit, onUse: () -> Unit) {
    val name = summary.account.name
    Scaffold(topBar = { WalletTopBar(name, onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            WalletListItem(
                leadingContent = { WalletAccountMark(name, place) },
                headlineContent = { Text(name) },
                trailingContent = { Text(formatAmount(summary.balanceMsat)) },
            )
            Text(
                stringResource(R.string.account_not_in_use),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            WalletButton(onClick = onUse, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.account_use))
            }
        }
    }
}

@Composable
private fun InUse() {
    Text(stringResource(R.string.wallet_in_use), color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun AmountAndChevron(msat: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatAmount(msat))
        ChevronIcon()
    }
}

@Composable
private fun AddButton(label: Int, onClick: () -> Unit) {
    WalletButton(kind = ButtonKind.Secondary, onClick = onClick, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(label))
    }
}

/** Asks for a name, starting from [current]; an empty one only with [allowEmpty]. */
@Composable
private fun RenameDialog(
    title: String,
    hint: String,
    current: String,
    allowEmpty: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(current) }
    WalletDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            WalletButton(kind = ButtonKind.Text, onClick = { onSave(name) }, enabled = allowEmpty || name.isNotBlank()) {
                Text(stringResource(R.string.rename_save))
            }
        },
        dismissButton = { WalletButton(kind = ButtonKind.Text, onClick = onDismiss) { Text(stringResource(R.string.rename_cancel)) } },
        title = { Text(title) },
        text = {
            Column {
                WalletTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.wallet_name)) },
                    singleLine = true,
                    focusSelected = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
    )
}
