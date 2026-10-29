package whitewallet.feature.home

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.core.designsystem.WalletStatusScope
import whitewallet.core.designsystem.BrandTitle
import whitewallet.core.designsystem.WithReleaseBadge
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.brand.ui.Anchor
import whitewallet.brand.MaintenanceStep
import whitewallet.brand.WalletStatus
import whitewallet.brand.WalletTask
import kotlinx.coroutines.delay
import whitewallet.brand.ui.NavigationItem
import whitewallet.core.designsystem.WalletNavigationBar
import whitewallet.brand.ui.walletColors
import whitewallet.brand.ui.Tone
import whitewallet.core.designsystem.AmountText
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.WalletSnackbarHost
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.recentTime
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.wallet.sentNoteId
import whitewallet.core.designsystem.R as DesignR
import whitewallet.brand.ui.ActivityType
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.MoneyDirection
import whitewallet.core.designsystem.WalletAccountMark
import whitewallet.core.designsystem.WalletActivityIcon
import whitewallet.core.wallet.HistoryFilterOption
import whitewallet.core.wallet.MaintenanceNotice
import whitewallet.core.designsystem.WalletAssistChip
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCard
import whitewallet.core.designsystem.WalletCircularProgress
import whitewallet.core.designsystem.WalletFilterChip
import whitewallet.core.designsystem.WalletListItem

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
    /** Opens a mint's details (from the changed-signing-key warning). */
    onOpenMint: (String) -> Unit = {},
    /** The Notes tab, if the brand shows one. */
    notesTab: NotesTabSlot? = null,
    /** The brand's own pages, in the top bar's menu. */
    menu: List<HomeMenuItem> = emptyList(),
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }
    HomeScreen(
        appName, state, onSend, onReceive, viewModel::refresh, onSettings, onBackup, viewModel::dismissBackupPrompt, onChangeAddress,
        AccountActions(viewModel::switchAccount, onAddAccount, onMoveFunds), onOpenNote, viewModel::toggleBalanceHidden, onOpenMint, notesTab, menu,
        viewModel::noticeShown,
    )
}

/** An entry of the top bar's menu: a page of the brand's own. */
class HomeMenuItem(@StringRes val title: Int, val onClick: () -> Unit)

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
    onOpenMint: (String) -> Unit = {},
    notesTab: NotesTabSlot? = null,
    menu: List<HomeMenuItem> = emptyList(),
    onNoticeShown: () -> Unit = {},
) {
    var saved by rememberSaveable { mutableStateOf(HomeTab.Wallet) }
    val tab = saved.takeIf { it != HomeTab.Notes || notesTab != null } ?: HomeTab.Wallet
    var historyFilters by rememberSaveable { mutableStateOf(emptySet<HistoryFilter>()) }
    var showAccounts by remember { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    state.notice?.let { notice ->
        val message = noticeMessage(notice)
        val details = stringResource(R.string.notice_details)
        LaunchedEffect(notice) {
            val result = snackbars.showSnackbar(message, actionLabel = details, withDismissAction = false, duration = SnackbarDuration.Long)
            onNoticeShown()
            // Details: History, with note maintenance shown.
            if (result == SnackbarResult.ActionPerformed) {
                historyFilters = setOf(HistoryFilter.Maintenance)
                saved = HomeTab.History
            }
        }
    }
    // Back from History returns to the wallet before leaving the app.
    BackHandler(enabled = tab != HomeTab.Wallet) { saved = HomeTab.Wallet }
    Scaffold(
        snackbarHost = { WalletSnackbarHost(snackbars) },
        topBar = {
            val title = when (tab) {
                HomeTab.Wallet -> appName
                HomeTab.Notes -> stringResource(R.string.home_tab_notes)
                HomeTab.History -> stringResource(R.string.home_tab_history)
            }
            val actions: @Composable () -> Unit = {
                if (tab == HomeTab.Notes) notesTab?.actions?.invoke()
                IconButton(onClick = if (tab == HomeTab.Notes) notesTab!!.onRefresh else onRefresh) {
                    Icon(painterResource(DesignR.drawable.ic_refresh), stringResource(DesignR.string.refresh))
                }
                IconButton(onClick = onSettings) { Icon(painterResource(DesignR.drawable.ic_settings), stringResource(DesignR.string.settings)) }
                if (menu.isNotEmpty()) MenuButton(menu)
            }
            if (tab == HomeTab.Wallet) {
                // The wallet tab's title is the app's name, which a brand may draw as its wordmark; an alpha or beta
                // carries its badge.
                WalletTopBar(title = { WithReleaseBadge(centered = false) { BrandTitle(Anchor.HomeTitle, title) { Text(title) } } }, onBack = null, actions = actions)
            } else {
                WalletTopBar(title, actions = actions)
            }
        },
        bottomBar = {
            WalletNavigationBar(
                listOfNotNull(
                    tabItem(tab == HomeTab.Wallet, DesignR.drawable.ic_wallet, R.string.home_tab_wallet) { saved = HomeTab.Wallet },
                    if (notesTab != null) tabItem(tab == HomeTab.Notes, DesignR.drawable.ic_notes, R.string.home_tab_notes) { saved = HomeTab.Notes } else null,
                    tabItem(tab == HomeTab.History, DesignR.drawable.ic_history, R.string.home_tab_history) { saved = HomeTab.History },
                ),
            )
        },
    ) { padding ->
        val notes = notesTab?.takeIf { tab == HomeTab.Notes }
        val cover = figuresCover(state.loaded, state.task)
        PullToRefreshBox(
            isRefreshing = cover == null && (notes?.refreshing ?: state.refreshing),
            onRefresh = notes?.onRefresh ?: onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                cover is Cover.Working -> LoadingScreen(cover.task, state)
                cover == Cover.Blank -> Box(Modifier.fillMaxSize())
                else -> when (tab) {
                    HomeTab.Wallet -> WalletTab(state, onSend, onReceive, { showAccounts = true }, onBackup, onDismissBackup, onChangeAddress, onToggleBalance, onOpenMint)
                    HomeTab.Notes -> notesTab?.content?.invoke()
                    HomeTab.History -> HistoryTab(state.activity, state.historyFilter, historyFilters, { historyFilters = it }, onOpenNote)
                }
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
private fun MenuButton(items: List<HomeMenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(painterResource(DesignR.drawable.ic_more), stringResource(DesignR.string.more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(stringResource(item.title)) }, onClick = { open = false; item.onClick() })
            }
        }
    }
}

private fun tabItem(selected: Boolean, @DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) = NavigationItem(
    selected = selected,
    onClick = onClick,
    icon = { Icon(painterResource(icon), contentDescription = null) },
    label = { Text(stringResource(label)) },
)

/** What Home shows over its tabs instead of their figures; null: the figures. */
private sealed interface Cover {
    /** Not read yet, or about to change before they were ever shown: nothing, for a moment. */
    data object Blank : Cover

    /** The wallet has worked on [task] for a while: the loading screen. */
    data class Working(val task: WalletTask) : Cover
}

/** How long work may run before the loading screen comes up: quick work only spins the pull-to-refresh indicator. */
internal const val LOADING_GRACE_MILLIS = 400L

/**
 * Whether Home shows its figures. Work that takes a while ([task]) covers them with the loading screen, since they are
 * about to change; quick work doesn't. Figures never shown yet aren't shown stale in the meantime.
 */
@Composable
private fun figuresCover(loaded: Boolean, task: WalletTask?): Cover? {
    var shown by remember { mutableStateOf(false) }
    var late by remember { mutableStateOf(false) }
    val busy = task != null
    LaunchedEffect(busy) {
        late = false
        if (busy) {
            delay(LOADING_GRACE_MILLIS)
            late = true
        }
    }
    val cover = when {
        !loaded -> Cover.Blank
        task != null && late -> Cover.Working(task)
        task != null && !shown -> Cover.Blank
        else -> null
    }
    if (cover == null) SideEffect { shown = true }
    return cover
}

/** What the wallet works on, in the brand's picture ([Anchor.Loading]) and in words, instead of the figures. */
@Composable
private fun LoadingScreen(task: WalletTask, state: HomeState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            // Scrollable, so the pull-to-refresh box around it gets the gestures it expects.
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            WalletStatusScope(WalletStatus.Working(task)) {
                BrandDecoration(Anchor.Loading) {
                    val checking = task as? WalletTask.CheckingNotes
                    WalletCircularProgress(Modifier.size(56.dp), progress = checking?.let { { it.checked.toFloat() / it.total.coerceAtLeast(1) } })
                }
            }
            Text(
                workingText(task, state),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** What [task] is, in the user's language; maintenance the brand doesn't show (notes, mints) is updating the balance. */
@Composable
private fun workingText(task: WalletTask, state: HomeState): String {
    val maintaining = task as? WalletTask.Maintaining
    state.strategyText?.takeIf { maintaining != null && state.notesVisible }?.let { return stringResource(it) }
    val words = when {
        task is WalletTask.CheckingNotes -> return pluralStringResource(R.plurals.home_working_checking_notes, task.total, task.checked + 1, task.total)
        maintaining == null || !state.notesVisible -> R.string.home_working_updating
        else -> when (maintaining.step) {
            MaintenanceStep.Combining -> R.string.home_working_combining
            MaintenanceStep.Splitting -> R.string.home_working_splitting
            MaintenanceStep.Renewing -> R.string.home_working_renewing
            MaintenanceStep.MovingHome -> if (state.mintsVisible) R.string.home_working_moving_home else R.string.home_working_updating
        }
    }
    return stringResource(words)
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
    onOpenMint: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            state.keyChangedHost?.let { KeyChanged(it) { onOpenMint(it) } }
            if (state.addressInactive) state.profile?.let { AddressInactive(it.address, onChangeAddress) }
            if (state.showBackupPrompt) BackupPrompt(onBackup, onDismissBackup)
            WalletStatusScope(state.status) { BrandDecoration(Anchor.HomeHeader) }
            AmountText(state.balanceMsat, hidden = state.balanceHidden, onToggleHidden = onToggleBalance)
            // Nothing in the wallet yet: say how money gets in. Not while the balance is hidden, which would give it away.
            if (state.status == WalletStatus.Empty && !state.balanceHidden) {
                Text(stringResource(R.string.home_wallet_empty), textAlign = TextAlign.Center)
            }
            val activeIndex = state.accounts.indexOfFirst { it.account.id == state.activeAccountId }
            state.accounts.getOrNull(activeIndex)?.takeIf { state.showAccounts }?.let { active ->
                WalletAssistChip(
                    onClick = onAccounts,
                    label = { Text(active.account.name) },
                    leadingIcon = { WalletAccountMark(active.account.name, activeIndex, size = 24.dp) },
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

/** What [notice] says, in the user's language: what the wallet did, and what that did to the balance. */
@Composable
private fun noticeMessage(notice: MaintenanceNotice): String {
    val what = when (notice) {
        is MaintenanceNotice.MovedHome -> stringResource(R.string.notice_moved_home, formatAmount(notice.arrivedMsat))
        is MaintenanceNotice.Combined -> if (notice.into == 1) {
            pluralStringResource(R.plurals.notice_combined_into_one, notice.notes, notice.notes, formatAmount(notice.amountMsat))
        } else {
            pluralStringResource(R.plurals.notice_combined, notice.notes, notice.notes, notice.into)
        }
        is MaintenanceNotice.Split -> pluralStringResource(R.plurals.notice_split, notice.notes, notice.notes, notice.into)
        is MaintenanceNotice.Rearranged -> pluralStringResource(R.plurals.notice_rearranged, notice.notes, notice.notes, notice.into)
        is MaintenanceNotice.Renewed -> pluralStringResource(R.plurals.notice_renewed, notice.notes, notice.notes)
        is MaintenanceNotice.BalanceChanged -> null
    }
    val balance = notice.balanceChangeMsat?.let { change ->
        if (change > 0) stringResource(R.string.notice_fees_back, formatAmount(change)) else stringResource(R.string.notice_fees_paid, formatAmount(-change))
    }
    return when {
        what == null -> balance.orEmpty()
        balance == null -> what
        else -> stringResource(R.string.notice_with_balance, what, balance)
    }
}

/** The kinds of History entries a user can narrow the list to. */
private enum class HistoryFilter(val label: Int, val icon: Int) {
    Received(R.string.history_filter_received, DesignR.drawable.ic_activity_received),
    Sent(R.string.history_filter_sent, DesignR.drawable.ic_activity_sent),
    Moved(R.string.history_filter_moved, DesignR.drawable.ic_activity_moved),
    Maintenance(R.string.history_filter_maintenance, DesignR.drawable.ic_activity_combined);

    companion object {
        fun of(kind: ActivityKind): HistoryFilter = when (kind) {
            ActivityKind.Received, ActivityKind.Claimed -> Received
            ActivityKind.Sent, ActivityKind.SentNote -> Sent
            ActivityKind.MovedIn, ActivityKind.MovedOut, ActivityKind.MovedMint -> Moved
            ActivityKind.Split, ActivityKind.Combined, ActivityKind.Rotated -> Maintenance
        }
    }
}

/**
 * What happened to the account's money, one row per event. Note maintenance (merges, rotations) is logged too but
 * hidden unless the user asks for it, since it changes the balance by fees at most. Depending on the brand ([option]),
 * the user narrows the list to kinds of entries ([selected]; none selected: everything but maintenance), only shows or
 * hides maintenance, or has no filter (but a message's Details shows maintenance until the user turns it off).
 */
@Composable
private fun HistoryTab(
    activity: List<Activity>,
    option: HistoryFilterOption,
    selected: Set<HistoryFilter>,
    onSelect: (Set<HistoryFilter>) -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val available = when (option) {
        HistoryFilterOption.Types -> HistoryFilter.entries.filter { f -> activity.any { HistoryFilter.of(it.kind) == f } }
        HistoryFilterOption.Maintenance -> listOf(HistoryFilter.Maintenance).filter { activity.any { it.kind.isMaintenance } }
        // No filters, but a message's Details shows note maintenance, until the user turns it off again.
        HistoryFilterOption.None -> listOf(HistoryFilter.Maintenance).filter { it in selected && activity.any { a -> a.kind.isMaintenance } }
    }
    // A filter whose entries are gone (or that the brand doesn't offer) doesn't hide anything.
    val active = selected.filter { it in available }.toSet()
    // Kinds to choose from: more than one, or the one maintenance switch; and always once one is on, to turn it off.
    val chips = available.takeIf { it.size > 1 || option == HistoryFilterOption.Maintenance || active.isNotEmpty() }.orEmpty()
    val visible = when {
        option != HistoryFilterOption.Types && HistoryFilter.Maintenance in active -> activity
        active.isNotEmpty() -> activity.filter { HistoryFilter.of(it.kind) in active }
        else -> activity.filterNot { it.kind.isMaintenance }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (chips.isNotEmpty()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { filter ->
                        WalletFilterChip(
                            selected = filter in active,
                            onClick = { onSelect(if (filter in active) active - filter else active + filter) },
                            label = { Text(stringResource(filter.label)) },
                            leadingIcon = if (option == HistoryFilterOption.Types) {
                                { Icon(painterResource(filter.icon), contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (activity.isEmpty()) {
                        WalletStatusScope(WalletStatus.Empty) { BrandDecoration(Anchor.EmptyHistory) }
                        Text(stringResource(R.string.home_empty), textAlign = TextAlign.Center)
                    } else {
                        Text(stringResource(R.string.history_only_maintenance), textAlign = TextAlign.Center)
                        if (HistoryFilter.Maintenance in available) {
                            WalletButton(kind = ButtonKind.Text, onClick = { onSelect(setOf(HistoryFilter.Maintenance)) }) {
                                Text(stringResource(R.string.history_show_maintenance))
                            }
                        }
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
        ActivityKind.Rotated -> stringResource(R.string.activity_renewed)
        ActivityKind.Sent -> activity.counterparty?.let { stringResource(R.string.activity_sent_to, it) } ?: stringResource(R.string.activity_sent)
        ActivityKind.Split -> stringResource(R.string.activity_split)
        ActivityKind.SentNote -> stringResource(R.string.activity_sent_note)
        ActivityKind.MovedMint -> activity.counterparty?.let { stringResource(R.string.activity_moved_mint, it) }
            ?: stringResource(R.string.activity_moved_mint_other)
    }
    val (type, direction) = when (activity.kind) {
        ActivityKind.Received -> ActivityType.Received to MoneyDirection.In
        ActivityKind.Claimed -> ActivityType.Claimed to MoneyDirection.In
        ActivityKind.MovedIn -> ActivityType.MovedAccounts to MoneyDirection.In
        ActivityKind.MovedOut -> ActivityType.MovedAccounts to MoneyDirection.Out
        ActivityKind.Sent -> ActivityType.Sent to MoneyDirection.Out
        ActivityKind.SentNote -> ActivityType.SentNote to MoneyDirection.Out
        ActivityKind.MovedMint -> ActivityType.MovedMints to MoneyDirection.Within
        ActivityKind.Split -> ActivityType.Split to MoneyDirection.Within
        ActivityKind.Combined -> ActivityType.Combined to MoneyDirection.Within
        ActivityKind.Rotated -> ActivityType.Renewed to MoneyDirection.Within
    }
    val outgoing = direction == MoneyDirection.Out
    val sats = formatAmount(activity.amountMsat)
    // A move between mints keeps the money in the account (less the fee), as does a note taken back: no sign.
    val amount = when {
        maintenance || activity.kind == ActivityKind.MovedMint || activity.status == ActivityStatus.Cancelled -> sats
        outgoing -> stringResource(R.string.activity_amount_out, sats)
        else -> stringResource(R.string.activity_amount_in, sats)
    }
    val amountColor = when {
        maintenance || activity.status == ActivityStatus.Failed || activity.status == ActivityStatus.Cancelled -> quiet
        activity.kind == ActivityKind.MovedMint -> MaterialTheme.colorScheme.onSurface
        outgoing -> MaterialTheme.walletColors.outgoing
        else -> MaterialTheme.walletColors.incoming
    }
    // Who did it, next to the title: the wallet by itself, or (for note maintenance, which is mostly automatic) the user.
    val by = when {
        activity.automatic -> stringResource(R.string.activity_automatic)
        maintenance -> stringResource(R.string.activity_manual)
        else -> null
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
        if (activity.certified == false) add(stringResource(R.string.activity_uncertified))
        activity.feeMsat?.let { fee ->
            add(if (fee < 0) stringResource(R.string.activity_fee_refunded, formatAmount(-fee)) else stringResource(R.string.activity_fee, formatAmount(fee)))
        }
    }.joinToString(stringResource(R.string.activity_detail_separator))
    val settled = activity.status == ActivityStatus.Done || (activity.kind == ActivityKind.SentNote && activity.status == ActivityStatus.Pending)
    WalletListItem(
        headlineContent = { Text(title, color = if (maintenance) quiet else Color.Unspecified) },
        overlineContent = by?.let { { Text(it) } },
        supportingContent = {
            Text(details, color = if (activity.status == ActivityStatus.Failed) MaterialTheme.colorScheme.error else Color.Unspecified)
        },
        leadingContent = { WalletActivityIcon(type, direction, activity.automatic, settled) },
        trailingContent = { Text(amount, style = MaterialTheme.typography.titleMedium, color = amountColor) },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun KeyChanged(host: String, onDetails: () -> Unit) {
    WalletCard(tone = Tone.Warning, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_key_changed_title, host), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_key_changed_body), style = MaterialTheme.typography.bodyMedium)
            WalletButton(onClick = onDetails) { Text(stringResource(R.string.home_key_changed_action)) }
        }
    }
}

@Composable
private fun AddressInactive(address: String, onChangeAddress: () -> Unit) {
    WalletCard(tone = Tone.Warning, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_address_inactive_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_address_inactive_body, address), style = MaterialTheme.typography.bodyMedium)
            WalletButton(onClick = onChangeAddress) { Text(stringResource(R.string.home_address_inactive_action)) }
        }
    }
}

@Composable
private fun BackupPrompt(onBackup: () -> Unit, onLater: () -> Unit) {
    WalletCard(tone = Tone.Warning, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_backup_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_backup_body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WalletButton(onClick = onBackup) { Text(stringResource(R.string.home_backup_action)) }
                WalletButton(kind = ButtonKind.Text, onClick = onLater) { Text(stringResource(R.string.home_backup_later)) }
            }
        }
    }
}
