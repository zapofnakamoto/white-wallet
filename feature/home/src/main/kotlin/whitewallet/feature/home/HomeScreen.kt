package whitewallet.feature.home

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import whitewallet.brand.MaintenanceStep
import whitewallet.brand.WalletStatus
import whitewallet.brand.WalletTask
import whitewallet.brand.ui.ActivityType
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.MoneyDirection
import whitewallet.brand.ui.NavigationItem
import whitewallet.brand.ui.Tone
import whitewallet.brand.ui.walletColors
import whitewallet.core.designsystem.AmountText
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.core.designsystem.BrandTitle
import whitewallet.core.designsystem.LoadingIndicator
import whitewallet.core.designsystem.LoadingPhase
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.ReceivedNoteRow
import whitewallet.core.designsystem.ReleaseNotesSheet
import whitewallet.core.designsystem.WalletAccountMark
import whitewallet.core.designsystem.WalletActivityIcon
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCard
import whitewallet.core.designsystem.WalletCircularProgress
import whitewallet.core.designsystem.WalletFilterChip
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletNavigationBar
import whitewallet.core.designsystem.WalletSheet
import whitewallet.core.designsystem.WalletSnackbarHost
import whitewallet.core.designsystem.WalletStatusScope
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.WithReleaseBadge
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.loadingPhase
import whitewallet.core.designsystem.recentTime
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.wallet.AccountMint
import whitewallet.core.wallet.HistoryFilterOption
import whitewallet.core.wallet.MaintenanceNotice
import whitewallet.core.wallet.sentNoteId

@Composable
fun HomeRoute(
    appName: String,
    onReceive: () -> Unit,
    onSettings: () -> Unit,
    onBackup: () -> Unit,
    onOpenAddresses: () -> Unit,
    /** Opens Settings → Wallets, where wallets and accounts are added, renamed and removed. */
    onManageWallets: () -> Unit,
    onMoveFunds: () -> Unit,
    /** Null until sending exists; the Send button shows disabled. */
    onSend: (() -> Unit)? = null,
    /** Opens a sent note (its QR code while nobody has claimed it). */
    onOpenNote: (String) -> Unit = {},
    /** Opens a mint's details (from the changed-signing-key warning). */
    onOpenMint: (String) -> Unit = {},
    /** The Notes tab, if the brand shows one. */
    notesTab: NotesTabSlot? = null,
    /** The brand's own pages, in the top bar's menu. */
    menu: List<HomeMenuItem> = emptyList(),
    /** Scan any QR code: a note to claim or something to pay. Null hides the button. */
    onScan: (() -> Unit)? = null,
    /** Opens where a mint is added, for a wallet without one. */
    onAddMint: () -> Unit = {},
    /** Opens the scan of the wallet's mints, started, while one wasn't looked through. */
    onScanMints: () -> Unit = {},
    /** Opens the account's mints (Account → Your mints). */
    onOpenMints: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    releaseNotes: ReleaseNotesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unseenNotes by releaseNotes.unseen.collectAsStateWithLifecycle()
    if (unseenNotes.isNotEmpty()) ReleaseNotesSheet(unseenNotes, onDone = releaseNotes::seen, onDismiss = releaseNotes::dismissed)
    LifecycleResumeEffect(viewModel) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }
    HomeScreen(
        appName,
        state,
        onSend,
        onReceive,
        onRefresh = viewModel::refresh,
        onSettings,
        onBackup,
        onDismissBackup = viewModel::dismissBackupPrompt,
        onOpenAddresses,
        accountActions = AccountActions(viewModel::switchAccount, onManageWallets, onMoveFunds),
        onOpenNote,
        onToggleBalance = viewModel::toggleBalanceHidden,
        onOpenMint,
        notesTab,
        menu,
        onNoticeShown = viewModel::noticeShown,
        onRecheckUnknown = viewModel::recheckUnknown,
        onUnknownCheckShown = viewModel::unknownCheckShown,
        onRemoveReceived = viewModel::removeReceived,
        onArchiveReceived = viewModel::archiveReceived,
        onScan,
        onAddMint,
        onScanMints,
        onOpenMints,
    )
}

/** An entry of the top bar's menu: a page of the brand's own. */
class HomeMenuItem(@StringRes val title: Int, val onClick: () -> Unit)

/**
 * The Notes tab, supplied by the app (the notes view lives in its own module): its content, the actions it adds to the top bar, and its own
 * refresh (asking the mints about every note).
 */
class NotesTabSlot(
    val refreshing: Boolean,
    val onRefresh: () -> Unit,
    val actions: @Composable () -> Unit,
    val content: @Composable () -> Unit,
)

private enum class HomeTab {
    Wallet,
    Notes,
    History,
}

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
    onOpenAddresses: () -> Unit,
    accountActions: AccountActions,
    onOpenNote: (String) -> Unit = {},
    onToggleBalance: () -> Unit = {},
    onOpenMint: (String) -> Unit = {},
    notesTab: NotesTabSlot? = null,
    menu: List<HomeMenuItem> = emptyList(),
    onNoticeShown: () -> Unit = {},
    onRecheckUnknown: () -> Unit = {},
    onUnknownCheckShown: () -> Unit = {},
    onRemoveReceived: (String) -> Unit = {},
    onArchiveReceived: (String) -> Unit = {},
    onScan: (() -> Unit)? = null,
    onAddMint: () -> Unit = {},
    onScanMints: () -> Unit = {},
    onOpenMints: () -> Unit = {},
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
    state.unknownCheck?.let { check ->
        val message = unknownCheckMessage(check, state.mintsVisible)
        LaunchedEffect(check) {
            snackbars.showSnackbar(message, duration = SnackbarDuration.Long)
            onUnknownCheckShown()
        }
    }
    // Back from History returns to the wallet before leaving the app.
    BackHandler(enabled = tab != HomeTab.Wallet) { saved = HomeTab.Wallet }
    Scaffold(
        snackbarHost = { WalletSnackbarHost(snackbars) },
        topBar = {
            val title =
                when (tab) {
                    HomeTab.Wallet -> appName
                    HomeTab.Notes -> stringResource(R.string.home_tab_notes)
                    HomeTab.History -> stringResource(R.string.home_tab_history)
                }
            val actions: @Composable () -> Unit = {
                if (tab == HomeTab.Notes) notesTab?.actions?.invoke()
                // Scanning is a step towards Send or Claim: a quiet icon on the wallet tab, so Send and Receive stand alone below.
                if (tab == HomeTab.Wallet && onScan != null) {
                    IconButton(onClick = onScan) { Icon(painterResource(DesignR.drawable.ic_qr_scan), stringResource(R.string.home_scan)) }
                }
                // The account in use, on every tab: its mark opens the switcher. Where it shows, it has the Refresh button's place, and
                // the page is pulled to refresh.
                val account = state.activeAccount?.takeIf { state.showAccounts }
                if (account == null) {
                    IconButton(onClick = if (tab == HomeTab.Notes) notesTab!!.onRefresh else onRefresh) {
                        Icon(painterResource(DesignR.drawable.ic_refresh), stringResource(DesignR.string.refresh))
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(painterResource(DesignR.drawable.ic_settings), stringResource(DesignR.string.settings))
                }
                account?.let { AccountButton(state, it) { showAccounts = true } }
                if (menu.isNotEmpty()) MenuButton(menu)
            }
            if (tab == HomeTab.Wallet) {
                // The wallet tab's title is the app's name, which a brand may draw as its wordmark; an alpha or beta
                // carries its badge.
                WalletTopBar(
                    title = { WithReleaseBadge(centered = false) { BrandTitle(Anchor.HomeTitle, title) { Text(title) } } },
                    onBack = null,
                    actions = actions,
                )
            } else {
                WalletTopBar(title, actions = actions)
            }
        },
        bottomBar = {
            WalletNavigationBar(
                listOfNotNull(
                    tabItem(tab == HomeTab.Wallet, DesignR.drawable.ic_wallet, R.string.home_tab_wallet) { saved = HomeTab.Wallet },
                    if (notesTab != null)
                        tabItem(tab == HomeTab.Notes, DesignR.drawable.ic_notes, R.string.home_tab_notes) { saved = HomeTab.Notes }
                    else null,
                    tabItem(tab == HomeTab.History, DesignR.drawable.ic_history, R.string.home_tab_history) { saved = HomeTab.History },
                )
            )
        },
    ) { padding ->
        val notes = notesTab?.takeIf { tab == HomeTab.Notes }
        val cover = figuresCover(state.loaded, state.task, state.refreshing)
        val refresh = notes?.onRefresh ?: onRefresh
        val refreshLabel = stringResource(DesignR.string.refresh)
        PullToRefreshBox(
            isRefreshing = cover == null && (notes?.refreshing ?: state.refreshing),
            onRefresh = refresh,
            // Who can't pull (a screen reader, a switch) refreshes by this action.
            modifier =
                Modifier.padding(padding).fillMaxSize().semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(refreshLabel) {
                                refresh()
                                true
                            }
                        )
                },
        ) {
            when {
                cover is Cover.Working -> LoadingScreen(cover.task, state)
                cover == Cover.Loading -> LoadingIndicator(Modifier.fillMaxSize())
                cover == Cover.Blank -> Box(Modifier.fillMaxSize())
                else ->
                    when (tab) {
                        HomeTab.Wallet ->
                            WalletTab(
                                state,
                                onSend,
                                onReceive,
                                onAccounts = { showAccounts = true },
                                onBackup,
                                onDismissBackup,
                                onOpenAddresses,
                                onToggleBalance,
                                onOpenMint,
                                onRecheckUnknown,
                                onRemoveReceived,
                                onArchiveReceived,
                                onOpenIncoming = if (notesTab != null) ({ saved = HomeTab.Notes }) else null,
                                onOpenShared = { saved = if (notesTab != null) HomeTab.Notes else HomeTab.History },
                                onOpenMints,
                                onAddMint,
                                onScanMints,
                            )
                        HomeTab.Notes -> notesTab?.content?.invoke()
                        HomeTab.History ->
                            HistoryTab(
                                state.activity,
                                option = state.historyFilter,
                                selected = historyFilters,
                                onSelect = { historyFilters = it },
                                onOpenNote,
                            )
                    }
            }
        }
    }
    if (showAccounts) {
        AccountSwitcher(
            accounts = state.accounts,
            activeId = state.activeAccountId,
            walletNames = state.walletNames,
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

@Composable
private fun MenuButton(items: List<HomeMenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(painterResource(DesignR.drawable.ic_more), stringResource(DesignR.string.more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.title)) },
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

private fun tabItem(selected: Boolean, @DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) =
    NavigationItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(painterResource(icon), contentDescription = null) },
        label = { Text(stringResource(label)) },
    )

/** What Home shows over its tabs instead of their figures; null: the figures. */
private sealed interface Cover {
    /** Not read yet, or about to change before they were ever shown: nothing, for a moment. */
    data object Blank : Cover

    /** Still not read after a while: the loading indicator of every page. */
    data object Loading : Cover

    /** The wallet has worked on [task] for a while: the loading screen. */
    data class Working(val task: WalletTask) : Cover
}

/**
 * Whether Home shows its figures, by the rule of every page that loads ([loadingPhase]). Work that takes a while ([task]) covers them with
 * the loading screen, since they are about to change, and that stays for its shortest time even when the work ends sooner; quick work only
 * spins the pull-to-refresh indicator. Figures never shown yet aren't shown stale in the meantime. A cover that is up stays up through a
 * refresh that is already running ([refreshing]): opening the app slowly is one wait, not the figures between two.
 */
@Composable
private fun figuresCover(loaded: Boolean, task: WalletTask?, refreshing: Boolean): Cover? {
    var shown by remember { mutableStateOf(false) }
    var covered by remember { mutableStateOf(false) }
    // The work the loading screen is about while it stays up after the work ended.
    val last = remember { Ref<WalletTask>() }
    if (task != null) last.value = task
    val cover =
        when (loadingPhase(loading = !loaded || task != null || (covered && refreshing))) {
            LoadingPhase.Indicator -> (task ?: last.value)?.let(Cover::Working) ?: Cover.Loading
            LoadingPhase.Blank -> if (loaded && shown) null else Cover.Blank
            LoadingPhase.Content -> null
        }
    SideEffect { covered = cover is Cover.Working || cover == Cover.Loading }
    if (cover == null) {
        SideEffect {
            shown = true
            if (task == null) last.value = null
        }
    }
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
                    WalletCircularProgress(
                        Modifier.size(56.dp),
                        progress = checking?.let { { it.checked.toFloat() / it.total.coerceAtLeast(1) } },
                    )
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
    state.strategyText
        ?.takeIf { maintaining != null && state.notesVisible }
        ?.let {
            return stringResource(it)
        }
    val words =
        when {
            task is WalletTask.CheckingNotes ->
                return pluralStringResource(R.plurals.home_working_checking_notes, task.total, task.checked + 1, task.total)
            maintaining == null || !state.notesVisible -> R.string.home_working_updating
            else ->
                when (maintaining.step) {
                    MaintenanceStep.Combining -> R.string.home_working_combining
                    MaintenanceStep.Splitting -> R.string.home_working_splitting
                    MaintenanceStep.Renewing -> R.string.home_working_renewing
                    MaintenanceStep.MovingHome ->
                        if (state.mintsVisible) R.string.home_working_moving_home else R.string.home_working_updating
                }
        }
    return stringResource(words)
}

/** The active account's wallet and name, as one line: the wallet by the name the user gave it, else its number. */
@Composable
private fun accountInWallet(state: HomeState, active: AccountSummary): String {
    val wallet = state.walletNames[active.account.walletId] ?: stringResource(R.string.accounts_wallet, state.activeWalletNumber)
    return stringResource(R.string.home_account_in_wallet, wallet, active.account.name)
}

/** The account in use as its mark, in the top bar: opens the switcher. Read out by its name (and wallet, with several). */
@Composable
private fun AccountButton(state: HomeState, active: AccountSummary, onClick: () -> Unit) {
    val name = if (state.walletCount > 1) accountInWallet(state, active) else active.account.name
    Box(
        Modifier.size(48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = stringResource(R.string.home_switch_account), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        WalletAccountMark(active.account.name, state.accounts.indexOf(active), size = 32.dp)
    }
}

/** Balance and the two actions, centred; notices only when something needs the user. */
@Composable
private fun WalletTab(
    state: HomeState,
    onSend: (() -> Unit)?,
    onReceive: () -> Unit,
    onAccounts: () -> Unit,
    onBackup: () -> Unit,
    onDismissBackup: () -> Unit,
    onOpenAddresses: () -> Unit,
    onToggleBalance: () -> Unit,
    onOpenMint: (String) -> Unit,
    onRecheckUnknown: () -> Unit,
    onRemoveReceived: (String) -> Unit,
    onArchiveReceived: (String) -> Unit,
    /** Opens the notes view, where received notes and those a mint doesn't know are listed too; null without one. */
    onOpenIncoming: (() -> Unit)?,
    /** Opens where the notes handed out are listed, each leading to its QR code and to taking it back. */
    onOpenShared: () -> Unit,
    /** Opens the account's mints, all of them, for those without a row here. */
    onOpenMints: () -> Unit,
    onAddMint: () -> Unit,
    onScanMints: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Alerts are pinned to the top, one at a time, the most important; the others are counted, never hidden without a word.
            // The balance stays in the middle of the screen below them: as much room under it as the alerts take above, while it fits.
            val alerts = state.alerts
            var otherAlerts by rememberSaveable { mutableStateOf(false) }
            var alertsHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            @Composable
            fun Alert(alert: HomeAlert) =
                when (alert) {
                    is HomeAlert.KeyChanged -> KeyChanged(alert.host) { onOpenMint(alert.host) }
                    is HomeAlert.UnknownNotes ->
                        UnknownNotesCard(
                            mint = alert.mint,
                            state.mintsVisible,
                            state.balanceHidden,
                            checking = state.checkingUnknown,
                            onRecheck = onRecheckUnknown,
                            onShowNotes = onOpenIncoming,
                        )
                    is HomeAlert.AddressInactive -> AddressInactive(alert.address, onOpenAddresses)
                    HomeAlert.AddMint -> AddMintPrompt(onAddMint)
                    HomeAlert.Backup -> BackupPrompt(onBackup, onDismissBackup)
                    HomeAlert.Scan -> ScanPrompt(onScanMints)
                }
            Column(
                Modifier.fillMaxWidth().onSizeChanged { alertsHeight = with(density) { it.height.toDp() } },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                alerts.firstOrNull()?.let { Alert(it) }
                if (alerts.size > 1) {
                    Text(
                        pluralStringResource(R.plurals.home_alerts_more, alerts.size - 1, alerts.size - 1),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { otherAlerts = true }.padding(8.dp),
                    )
                }
            }
            // The others open in a sheet over Home: the balance stays where it is. It closes by itself once they're dealt with.
            val hasOthers = alerts.size > 1
            LaunchedEffect(hasOthers) { if (!hasOthers) otherAlerts = false }
            if (otherAlerts && hasOthers) {
                WalletSheet(onDismissRequest = { otherAlerts = false }) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        alerts.drop(1).forEach { Alert(it) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            WalletStatusScope(state.status) { BrandDecoration(Anchor.HomeHeader) }
            // With several wallets, an account's name alone doesn't say whose money this is: the wallet is named too.
            if (state.showAccounts && state.walletCount > 1) {
                state.activeAccount?.let {
                    Text(
                        accountInWallet(state, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clickable(onClick = onAccounts),
                    )
                }
            }
            AmountText(state.balanceMsat, hidden = state.balanceHidden, onToggleHidden = onToggleBalance)
            // Received but not claimed yet: next to the balance, never in it.
            if (state.received.any { it.isPending }) {
                Text(
                    if (state.balanceHidden) stringResource(DesignR.string.received_title)
                    else stringResource(DesignR.string.received_total, formatAmount(state.incomingMsat)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = onOpenIncoming?.let { Modifier.clickable(onClick = it) } ?: Modifier,
                )
            }
            if (state.mintRows.isNotEmpty()) BalanceByMint(state.mintRows, state.moreMints, onOpenMint, onOpenMints)
            // Handed out as links and not claimed yet: out of the balance, and the user's until someone claims them.
            if (state.sharedMsat > 0) {
                Text(
                    if (state.balanceHidden) stringResource(R.string.home_shared_title)
                    else stringResource(R.string.home_shared_total, formatAmount(state.sharedMsat)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable(onClick = onOpenShared),
                )
            }
            if (state.received.isNotEmpty()) ReceivedCard(state, onRemoveReceived, onArchiveReceived)
            // Nothing in the wallet yet: say how money gets in. Not while the balance is hidden, which would give it away.
            if (state.status == WalletStatus.Empty && !state.balanceHidden) {
                Text(stringResource(R.string.home_wallet_empty), textAlign = TextAlign.Center)
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
            state.error?.let {
                Text(
                    stringResource(R.string.home_refresh_failed, it),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            if (state.unresolved > 0) {
                Text(
                    pluralStringResource(R.plurals.home_unresolved, state.unresolved, state.unresolved),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(alertsHeight))
        }
    }
}

/** What [notice] says, in the user's language: what the wallet did, and what that did to the balance. */
@Composable
private fun noticeMessage(notice: MaintenanceNotice): String {
    val what =
        when (notice) {
            is MaintenanceNotice.MovedHome -> stringResource(R.string.notice_moved_home, formatAmount(notice.arrivedMsat))
            is MaintenanceNotice.Combined ->
                if (notice.into == 1) {
                    pluralStringResource(R.plurals.notice_combined_into_one, notice.notes, notice.notes, formatAmount(notice.amountMsat))
                } else {
                    pluralStringResource(R.plurals.notice_combined, notice.notes, notice.notes, notice.into)
                }
            is MaintenanceNotice.Split -> pluralStringResource(R.plurals.notice_split, notice.notes, notice.notes, notice.into)
            is MaintenanceNotice.Rearranged -> pluralStringResource(R.plurals.notice_rearranged, notice.notes, notice.notes, notice.into)
            is MaintenanceNotice.Renewed -> pluralStringResource(R.plurals.notice_renewed, notice.notes, notice.notes)
            is MaintenanceNotice.BalanceChanged -> null
        }
    val balance =
        notice.balanceChangeMsat?.let { change ->
            if (change > 0) stringResource(R.string.notice_fees_back, formatAmount(change))
            else stringResource(R.string.notice_fees_paid, formatAmount(-change))
        }
    return when {
        what == null -> balance.orEmpty()
        balance == null -> what
        else -> stringResource(R.string.notice_with_balance, what, balance)
    }
}

/** What asking again about notes a mint doesn't know found, as the short message says it. */
@Composable
private fun unknownCheckMessage(check: UnknownCheck, mintsVisible: Boolean): String =
    when (check) {
        is UnknownCheck.Back -> pluralStringResource(R.plurals.notice_unknown_back, check.notes, check.notes)
        is UnknownCheck.Still -> pluralStringResource(R.plurals.notice_unknown_still, check.notes)
        is UnknownCheck.Unreachable ->
            check.host?.takeIf { mintsVisible }?.let { stringResource(R.string.notice_unknown_unreachable, it) }
                ?: stringResource(R.string.notice_unknown_unreachable_unnamed)
    }

/** The kinds of History entries a user can narrow the list to. */
private enum class HistoryFilter(val label: Int, val icon: Int) {
    Received(R.string.history_filter_received, DesignR.drawable.ic_activity_received),
    Sent(R.string.history_filter_sent, DesignR.drawable.ic_activity_sent),
    Moved(R.string.history_filter_moved, DesignR.drawable.ic_activity_moved),
    Maintenance(R.string.history_filter_maintenance, DesignR.drawable.ic_activity_combined);

    companion object {
        fun of(kind: ActivityKind): HistoryFilter =
            when (kind) {
                ActivityKind.Received,
                ActivityKind.Claimed -> Received
                ActivityKind.Sent,
                ActivityKind.SentNote -> Sent
                ActivityKind.MovedIn,
                ActivityKind.MovedOut,
                ActivityKind.MovedMint -> Moved
                ActivityKind.Split,
                ActivityKind.Combined,
                ActivityKind.Rotated -> Maintenance
            }
    }
}

/**
 * What happened to the account's money, one row per event. Note maintenance (merges, rotations) is logged too but hidden unless the user
 * asks for it, since it changes the balance by fees at most. Depending on the brand ([option]), the user narrows the list to kinds of
 * entries ([selected]; none selected: everything but maintenance), only shows or hides maintenance, or has no filter (but a message's
 * Details shows maintenance until the user turns it off).
 */
@Composable
private fun HistoryTab(
    activity: List<Activity>,
    option: HistoryFilterOption,
    selected: Set<HistoryFilter>,
    onSelect: (Set<HistoryFilter>) -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val available =
        when (option) {
            HistoryFilterOption.Types -> HistoryFilter.entries.filter { f -> activity.any { HistoryFilter.of(it.kind) == f } }
            HistoryFilterOption.Maintenance -> listOf(HistoryFilter.Maintenance).filter { activity.any { it.kind.isMaintenance } }
            // No filters, but a message's Details shows note maintenance, until the user turns it off again.
            HistoryFilterOption.None ->
                listOf(HistoryFilter.Maintenance).filter { it in selected && activity.any { a -> a.kind.isMaintenance } }
        }
    // A filter whose entries are gone (or that the brand doesn't offer) doesn't hide anything.
    val active = selected.filter { it in available }.toSet()
    // Kinds to choose from: more than one, or the one maintenance switch; and always once one is on, to turn it off.
    val chips = available.takeIf { it.size > 1 || option == HistoryFilterOption.Maintenance || active.isNotEmpty() }.orEmpty()
    val visible =
        when {
            option != HistoryFilterOption.Types && HistoryFilter.Maintenance in active -> activity
            active.isNotEmpty() -> activity.filter { HistoryFilter.of(it.kind) in active }
            else -> activity.filterNot { it.kind.isMaintenance }
        }
    LazyColumn(Modifier.fillMaxSize()) {
        if (chips.isNotEmpty()) {
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chips.forEach { filter ->
                        WalletFilterChip(
                            selected = filter in active,
                            onClick = { onSelect(if (filter in active) active - filter else active + filter) },
                            label = { Text(stringResource(filter.label)) },
                            leadingIcon =
                                if (option == HistoryFilterOption.Types) {
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
    val title =
        when (activity.kind) {
            ActivityKind.Received -> stringResource(R.string.activity_received)
            ActivityKind.Claimed -> stringResource(R.string.activity_claimed)
            ActivityKind.MovedOut ->
                activity.counterparty?.let { stringResource(R.string.activity_moved_out, it) }
                    ?: stringResource(R.string.activity_moved_out_other)
            ActivityKind.MovedIn ->
                activity.counterparty?.let { stringResource(R.string.activity_moved_in, it) }
                    ?: stringResource(R.string.activity_moved_in_other)
            ActivityKind.Combined -> (activity.noteCount ?: 0).let { pluralStringResource(R.plurals.activity_combined, it, it) }
            ActivityKind.Rotated -> stringResource(R.string.activity_renewed)
            ActivityKind.Sent ->
                activity.counterparty?.let { stringResource(R.string.activity_sent_to, it) } ?: stringResource(R.string.activity_sent)
            ActivityKind.Split -> stringResource(R.string.activity_split)
            ActivityKind.SentNote -> stringResource(R.string.activity_sent_note)
            ActivityKind.MovedMint ->
                activity.counterparty?.let { stringResource(R.string.activity_moved_mint, it) }
                    ?: stringResource(R.string.activity_moved_mint_other)
        }
    val (type, direction) =
        when (activity.kind) {
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
    val amount =
        when {
            maintenance || activity.kind == ActivityKind.MovedMint || activity.status == ActivityStatus.Cancelled -> sats
            outgoing -> stringResource(R.string.activity_amount_out, sats)
            else -> stringResource(R.string.activity_amount_in, sats)
        }
    val amountColor =
        when {
            maintenance || activity.status == ActivityStatus.Failed || activity.status == ActivityStatus.Cancelled -> quiet
            activity.kind == ActivityKind.MovedMint -> MaterialTheme.colorScheme.onSurface
            outgoing -> MaterialTheme.walletColors.outgoing
            else -> MaterialTheme.walletColors.incoming
        }
    // Who did it, next to the title: the wallet by itself, or (for note maintenance, which is mostly automatic) the user.
    val by =
        when {
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
            ActivityStatus.Done -> if (sentNote) add(stringResource(R.string.activity_note_spent))
        }
        if (activity.certified == false) add(stringResource(R.string.activity_uncertified))
        activity.feeMsat?.let { fee ->
            add(
                if (fee < 0) stringResource(R.string.activity_fee_refunded, formatAmount(-fee))
                else stringResource(R.string.activity_fee, formatAmount(fee))
            )
        }
    }
        .joinToString(stringResource(R.string.activity_detail_separator))
    val settled =
        activity.status == ActivityStatus.Done || (activity.kind == ActivityKind.SentNote && activity.status == ActivityStatus.Pending)
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

/**
 * A mint says it doesn't know some of the account's notes: they're out of the balance, so say why, that the wallet keeps them and asks
 * again (and when it last did), and that there is nothing to do but wait or ask the mint's operator. The amount stays hidden with the
 * balance. Asking now is offered, not urged; [onShowNotes] leads to the notes themselves, where the app has a notes view.
 */
@Composable
private fun UnknownNotesCard(
    mint: AccountMint,
    mintsVisible: Boolean,
    balanceHidden: Boolean,
    checking: Boolean,
    onRecheck: () -> Unit,
    onShowNotes: (() -> Unit)?,
) {
    val n = mint.unknownCount
    WalletCard(tone = Tone.Warning, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (mintsVisible) pluralStringResource(R.plurals.home_unknown_title, n, n, mint.host)
                else pluralStringResource(R.plurals.home_unknown_title_unnamed, n, n),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (balanceHidden) pluralStringResource(R.plurals.home_unknown_body_hidden, n)
                else pluralStringResource(R.plurals.home_unknown_body, n, formatAmount(mint.unknownMsat)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.home_unknown_help), style = MaterialTheme.typography.bodyMedium)
            if (mint.unknownAskedAtMillis > 0) Text(lastAsked(mint.unknownAskedAtMillis), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WalletButton(kind = ButtonKind.Secondary, onClick = onRecheck, enabled = !checking) {
                    Text(stringResource(R.string.home_unknown_action))
                }
                onShowNotes?.let {
                    WalletButton(kind = ButtonKind.Text, onClick = it) { Text(pluralStringResource(R.plurals.home_unknown_show, n)) }
                }
            }
        }
    }
}

/** "Last asked: 5 minutes ago", in the phone's words for the time, kept current while it shows. */
@Composable
private fun lastAsked(atMillis: Long): String {
    val now by
        produceState(System.currentTimeMillis(), atMillis) {
            while (true) {
                value = System.currentTimeMillis()
                delay(ASKED_TICK_MILLIS)
            }
        }
    return if (now - atMillis < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.home_unknown_asked_now)
    else
        stringResource(
            R.string.home_unknown_asked,
            DateUtils.getRelativeTimeSpanString(atMillis, now, DateUtils.MINUTE_IN_MILLIS).toString(),
        )
}

/** How often [lastAsked] looks at the clock. */
private const val ASKED_TICK_MILLIS = 20_000L

/**
 * What of the balance is on which mint, a row each, leading to the mint's page. The mints [more] share a last row, with what they hold
 * together, leading to the account's mints.
 */
@Composable
private fun BalanceByMint(mints: List<AccountMint>, more: List<AccountMint>, onOpenMint: (String) -> Unit, onOpenMints: () -> Unit) {
    WalletCard(modifier = Modifier.fillMaxWidth()) {
        mints.forEach { mint ->
            WalletListItem(
                headlineContent = { Text(mint.host) },
                supportingContent = if (mint.isHome) ({ Text(stringResource(R.string.home_mint_is_home)) }) else null,
                trailingContent = { Text(formatAmount(mint.balanceMsat), style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.clickable { onOpenMint(mint.host) },
            )
        }
        if (more.isNotEmpty()) {
            WalletListItem(
                headlineContent = { Text(pluralStringResource(R.plurals.home_more_mints, more.size, more.size)) },
                trailingContent = { Text(formatAmount(more.sumOf { it.balanceMsat }), style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.clickable(onClick = onOpenMints),
            )
        }
    }
}

/**
 * Notes received as links, not claimed yet or no longer claimable: each with where it stands, and what that means. Amounts stay hidden with
 * the balance.
 */
@Composable
private fun ReceivedCard(state: HomeState, onRemove: (String) -> Unit, onArchive: (String) -> Unit) {
    WalletCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(DesignR.string.received_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            state.received.forEach {
                ReceivedNoteRow(
                    it,
                    showMint = state.mintsVisible,
                    amountHidden = state.balanceHidden,
                    onRemove = onRemove,
                    onArchive = onArchive,
                )
            }
            if (state.received.any { it.isPending }) {
                Text(
                    stringResource(DesignR.string.received_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun AddressInactive(address: String, onOpenAddresses: () -> Unit) {
    WalletCard(tone = Tone.Warning, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_address_inactive_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_address_inactive_body, address), style = MaterialTheme.typography.bodyMedium)
            WalletButton(onClick = onOpenAddresses) { Text(stringResource(R.string.home_address_inactive_action)) }
        }
    }
}

/** A mint's keys weren't looked through to the end, so the wallet may hold more than it shows: stays until a scan is through. */
@Composable
private fun ScanPrompt(onScan: () -> Unit) {
    WalletCard(tone = Tone.Warning, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_scan_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_scan_body), style = MaterialTheme.typography.bodyMedium)
            WalletButton(onClick = onScan) { Text(stringResource(R.string.home_scan_action)) }
        }
    }
}

/** A wallet without a mint can't receive: stays until one is added. */
@Composable
private fun AddMintPrompt(onAddMint: () -> Unit) {
    WalletCard(tone = Tone.Warning, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_add_mint_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_add_mint_body), style = MaterialTheme.typography.bodyMedium)
            WalletButton(onClick = onAddMint) { Text(stringResource(R.string.home_add_mint_action)) }
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
