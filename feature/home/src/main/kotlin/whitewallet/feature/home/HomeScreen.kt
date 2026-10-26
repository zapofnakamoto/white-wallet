package whitewallet.feature.home

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.core.designsystem.AmountText
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.recentTime
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.wallet.sentNoteId
import whitewallet.core.designsystem.R as DesignR

@Composable
fun HomeRoute(
    appName: String,
    onReceive: () -> Unit,
    onSettings: () -> Unit,
    onBackup: () -> Unit,
    onChangeAddress: () -> Unit,
    onAddAccount: () -> Unit,
    onMoveFunds: () -> Unit,
    /** Null until sending exists; the Send button shows disabled. */
    onSend: (() -> Unit)? = null,
    /** Opens a sent note (its QR code while nobody has redeemed it). */
    onOpenNote: (String) -> Unit = {},
    /** Opens a service's details (from the changed-signing-key warning). */
    onOpenService: (String) -> Unit = {},
    /** The Notes tab, if the brand shows one. */
    notesTab: NotesTabSlot? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        appName, state, onSend, onReceive, viewModel::refresh, onSettings, onBackup, viewModel::dismissBackupPrompt, onChangeAddress,
        AccountActions(viewModel::switchAccount, onAddAccount, onMoveFunds), onOpenNote, viewModel::toggleBalanceHidden, onOpenService, notesTab,
    )
}

/**
 * The Notes tab, supplied by the app (the notes view lives in its own module): its content, the actions it adds to
 * the top bar, and its own refresh (asking the mints about every note).
 */
class NotesTabSlot(
    val refreshing: Boolean,
    val onRefresh: () -> Unit,
    val actions: @Composable () -> Unit,
    val content: @Composable () -> Unit,
)

private enum class HomeTab { Wallet, Notes, History }

/** Tabs in a bottom bar: the wallet (balance, Send, Receive), the notes (if the brand shows them) and the history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appName: String,
    state: HomeState,
    onSend: (() -> Unit)?,
    onReceive: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onBackup: () -> Unit,
    onDismissBackup: () -> Unit,
    onChangeAddress: () -> Unit,
    accountActions: AccountActions,
    onOpenNote: (String) -> Unit = {},
    onToggleBalance: () -> Unit = {},
    onOpenService: (String) -> Unit = {},
    notesTab: NotesTabSlot? = null,
) {
    var saved by rememberSaveable { mutableStateOf(HomeTab.Wallet) }
    val tab = saved.takeIf { it != HomeTab.Notes || notesTab != null } ?: HomeTab.Wallet
    var showMaintenance by rememberSaveable { mutableStateOf(false) }
    var showAccounts by remember { mutableStateOf(false) }
    // Back from History returns to the wallet before leaving the app.
    BackHandler(enabled = tab != HomeTab.Wallet) { saved = HomeTab.Wallet }
    Scaffold(
        topBar = {
            val title = when (tab) {
                HomeTab.Wallet -> appName
                HomeTab.Notes -> stringResource(R.string.home_tab_notes)
                HomeTab.History -> stringResource(R.string.home_tab_history)
            }
            WalletTopBar(title, actions = {
                if (tab == HomeTab.Notes) notesTab?.actions?.invoke()
                IconButton(onClick = if (tab == HomeTab.Notes) notesTab!!.onRefresh else onRefresh) {
                    Icon(painterResource(DesignR.drawable.ic_refresh), stringResource(DesignR.string.refresh))
                }
                IconButton(onClick = onSettings) { Icon(painterResource(DesignR.drawable.ic_settings), stringResource(DesignR.string.settings)) }
            })
        },
        bottomBar = {
            NavigationBar {
                TabItem(tab == HomeTab.Wallet, DesignR.drawable.ic_wallet, R.string.home_tab_wallet) { saved = HomeTab.Wallet }
                if (notesTab != null) TabItem(tab == HomeTab.Notes, DesignR.drawable.ic_notes, R.string.home_tab_notes) { saved = HomeTab.Notes }
                TabItem(tab == HomeTab.History, DesignR.drawable.ic_history, R.string.home_tab_history) { saved = HomeTab.History }
            }
        },
    ) { padding ->
        val notes = notesTab?.takeIf { tab == HomeTab.Notes }
        PullToRefreshBox(
            isRefreshing = notes?.refreshing ?: state.refreshing,
            onRefresh = notes?.onRefresh ?: onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when (tab) {
                HomeTab.Wallet -> WalletTab(state, onSend, onReceive, { showAccounts = true }, onBackup, onDismissBackup, onChangeAddress, onToggleBalance, onOpenService)
                HomeTab.Notes -> notesTab?.content?.invoke()
                HomeTab.History -> HistoryTab(state.activity, showMaintenance, { showMaintenance = it }, onOpenNote)
            }
        }
    }
    if (showAccounts) {
        AccountSwitcher(
            accounts = state.accounts,
            activeId = state.activeAccountId,
            canAdd = state.canAddAccount,
            onSelect = { showAccounts = false; accountActions.onSwitch(it) },
            onAdd = { showAccounts = false; accountActions.onAdd() },
            onMove = { showAccounts = false; accountActions.onMove() },
            onDismiss = { showAccounts = false },
        )
    }
}

@Composable
private fun RowScope.TabItem(selected: Boolean, @DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(painterResource(icon), contentDescription = null) },
        label = { Text(stringResource(label)) },
    )
}

/** Balance, account and the two actions, centred; notices only when something needs the user. */
@Composable
private fun WalletTab(
    state: HomeState,
    onSend: (() -> Unit)?,
    onReceive: () -> Unit,
    onAccounts: () -> Unit,
    onBackup: () -> Unit,
    onDismissBackup: () -> Unit,
    onChangeAddress: () -> Unit,
    onToggleBalance: () -> Unit,
    onOpenService: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            state.keyChangedHost?.let { KeyChanged(it) { onOpenService(it) } }
            if (state.addressInactive) state.profile?.let { AddressInactive(it.address, onChangeAddress) }
            if (state.showBackupPrompt) BackupPrompt(onBackup, onDismissBackup)
            AmountText(state.balanceMsat, hidden = state.balanceHidden, onToggleHidden = onToggleBalance)
            state.accounts.firstOrNull { it.account.id == state.activeAccountId }?.takeIf { state.showAccounts }?.let { active ->
                AssistChip(
                    onClick = onAccounts,
                    label = { Text(active.account.name) },
                    trailingIcon = { Icon(painterResource(DesignR.drawable.ic_arrow_drop_down), stringResource(R.string.home_switch_account)) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val soon = stringResource(R.string.home_send_soon)
                PrimaryButton(
                    stringResource(R.string.home_send),
                    onClick = { onSend?.invoke() },
                    enabled = onSend != null,
                    modifier = Modifier.weight(1f).semantics { if (onSend == null) stateDescription = soon },
                )
                PrimaryButton(stringResource(R.string.home_receive), onReceive, Modifier.weight(1f))
            }
            state.error?.let { Text(stringResource(R.string.home_refresh_failed, it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            if (state.unresolved > 0) {
                Text(pluralStringResource(R.plurals.home_unresolved, state.unresolved, state.unresolved), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * What happened to the account's money, one row per event. Note maintenance (merges, rotations) is logged too but
 * hidden unless the filter is on, since it doesn't change the balance.
 */
@Composable
private fun HistoryTab(activity: List<Activity>, showMaintenance: Boolean, onShowMaintenance: (Boolean) -> Unit, onOpenNote: (String) -> Unit) {
    val visible = if (showMaintenance) activity else activity.filterNot { it.kind.isMaintenance }
    LazyColumn(Modifier.fillMaxSize()) {
        if (activity.any { it.kind.isMaintenance }) {
            item {
                FilterChip(
                    selected = showMaintenance,
                    onClick = { onShowMaintenance(!showMaintenance) },
                    label = { Text(stringResource(R.string.history_filter_maintenance)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (visible.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (activity.isEmpty()) {
                        Text(stringResource(R.string.home_empty), textAlign = TextAlign.Center)
                    } else {
                        Text(stringResource(R.string.history_only_maintenance), textAlign = TextAlign.Center)
                        TextButton(onClick = { onShowMaintenance(true) }) { Text(stringResource(R.string.history_show_maintenance)) }
                    }
                }
            }
        }
        items(visible, key = { it.id }) { row -> ActivityRow(row, onClick = row.sentNoteId?.let { id -> { onOpenNote(id) } }) }
    }
}

@Composable
private fun ActivityRow(activity: Activity, onClick: (() -> Unit)?) {
    val maintenance = activity.kind.isMaintenance
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val title = when (activity.kind) {
        ActivityKind.Received -> stringResource(R.string.activity_received)
        ActivityKind.Claimed -> stringResource(R.string.activity_claimed)
        ActivityKind.MovedOut -> activity.counterparty?.let { stringResource(R.string.activity_moved_out, it) } ?: stringResource(R.string.activity_moved_out_other)
        ActivityKind.MovedIn -> activity.counterparty?.let { stringResource(R.string.activity_moved_in, it) } ?: stringResource(R.string.activity_moved_in_other)
        ActivityKind.Combined -> (activity.noteCount ?: 0).let { pluralStringResource(R.plurals.activity_combined, it, it) }
        ActivityKind.Rotated -> stringResource(R.string.activity_rotated)
        ActivityKind.Sent -> activity.counterparty?.let { stringResource(R.string.activity_sent_to, it) } ?: stringResource(R.string.activity_sent)
        ActivityKind.Split -> stringResource(R.string.activity_split)
        ActivityKind.SentNote -> stringResource(R.string.activity_sent_note)
        ActivityKind.MovedService -> activity.counterparty?.let { stringResource(R.string.activity_moved_service, it) }
            ?: stringResource(R.string.activity_moved_service_other)
    }
    val outgoing = activity.kind == ActivityKind.MovedOut || activity.kind == ActivityKind.Sent || activity.kind == ActivityKind.SentNote
    val sats = formatAmount(activity.amountMsat)
    // A move between services keeps the money in the account (less the fee), as does a note taken back: no sign.
    val amount = when {
        maintenance || activity.kind == ActivityKind.MovedService || activity.status == ActivityStatus.Cancelled -> sats
        outgoing -> stringResource(R.string.activity_amount_out, sats)
        else -> stringResource(R.string.activity_amount_in, sats)
    }
    val amountColor = when {
        maintenance || activity.status == ActivityStatus.Failed || activity.status == ActivityStatus.Cancelled -> quiet
        outgoing || activity.kind == ActivityKind.MovedService -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.primary
    }
    val details = buildList {
        add(recentTime(activity.createdAtMillis))
        val sentNote = activity.kind == ActivityKind.SentNote
        when (activity.status) {
            ActivityStatus.Pending -> add(stringResource(if (sentNote) R.string.activity_note_waiting else R.string.activity_pending))
            ActivityStatus.Failed -> add(stringResource(R.string.activity_failed))
            ActivityStatus.Cancelled -> add(stringResource(R.string.activity_note_taken_back))
            ActivityStatus.Done -> if (sentNote) add(stringResource(R.string.activity_note_redeemed))
        }
        if (maintenance) add(stringResource(if (activity.automatic) R.string.activity_automatic else R.string.activity_manual))
        if (activity.certified == false) add(stringResource(R.string.activity_uncertified))
        activity.feeMsat?.let { fee ->
            add(if (fee < 0) stringResource(R.string.activity_fee_refunded, formatAmount(-fee)) else stringResource(R.string.activity_fee, formatAmount(fee)))
        }
    }.joinToString(stringResource(R.string.activity_detail_separator))
    ListItem(
        headlineContent = { Text(title, color = if (maintenance) quiet else Color.Unspecified) },
        supportingContent = {
            Text(details, color = if (activity.status == ActivityStatus.Failed) MaterialTheme.colorScheme.error else Color.Unspecified)
        },
        trailingContent = { Text(amount, style = MaterialTheme.typography.titleMedium, color = amountColor) },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun KeyChanged(host: String, onDetails: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_key_changed_title, host), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_key_changed_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onDetails) { Text(stringResource(R.string.home_key_changed_action)) }
        }
    }
}

@Composable
private fun AddressInactive(address: String, onChangeAddress: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_address_inactive_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_address_inactive_body, address), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onChangeAddress) { Text(stringResource(R.string.home_address_inactive_action)) }
        }
    }
}

@Composable
private fun BackupPrompt(onBackup: () -> Unit, onLater: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_backup_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_backup_body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBackup) { Text(stringResource(R.string.home_backup_action)) }
                TextButton(onClick = onLater) { Text(stringResource(R.string.home_backup_later)) }
            }
        }
    }
}
