package whitewallet.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletStatus
import whitewallet.brand.WalletTask
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.isShared
import whitewallet.core.wallet.AccountMint
import whitewallet.core.wallet.AddressStatus
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.HistoryFilterOption
import whitewallet.core.wallet.MaintenanceNotice
import whitewallet.core.wallet.MintKeyChangedException
import whitewallet.core.wallet.NotesView
import whitewallet.core.wallet.PolicyRun
import whitewallet.core.wallet.Wallet

data class HomeState(
    /** The wallet's figures have been read; until then Home shows none (rather than a balance of nothing). */
    val loaded: Boolean = true,
    /** What the wallet works on that changes the figures, if anything; Home shows it once it takes a while. */
    val task: WalletTask? = null,
    /** The note strategy's own words for its steps ([whitewallet.brand.NoteStrategy.workingText]), while they run. */
    val strategyText: Int? = null,
    /** The brand shows notes, and mints; without, maintenance that names them is told as updating the balance. */
    val notesVisible: Boolean = true,
    val mintsVisible: Boolean = true,
    val accounts: List<AccountSummary> = emptyList(),
    val activeAccountId: String? = null,
    val balanceMsat: Long = 0,
    /** The active account's history, newest first, maintenance included (History filters it). */
    val activity: List<Activity> = emptyList(),
    val refreshing: Boolean = false,
    val unresolved: Int = 0,
    val error: String? = null,
    /** A mint that now presents another signing key than the pinned one: the wallet stops working with it. */
    val keyChangedHost: String? = null,
    /** Funds are at risk until the recovery phrase is backed up. */
    val showBackupPrompt: Boolean = false,
    /** The active wallet has no mint yet: nothing can be received until one is added. */
    val showAddMintPrompt: Boolean = false,
    /** A mint's keys weren't looked through to the end: the wallet may hold more than it shows, until a scan is through. */
    val showScanPrompt: Boolean = false,
    /** An address of the active account that its mint no longer routes to this wallet (checked on refresh); null if none. */
    val inactiveAddress: String? = null,
    val backupPromptDismissed: Boolean = false,
    /** The balance shows as dots until tapped (kept across launches). */
    val balanceHidden: Boolean = false,
    /** The names the user gave wallets, by wallet id. */
    val walletNames: Map<String, String> = emptyMap(),
    /** Accounts are a thing in this app: hidden when the brand allows one and there is one. */
    val showAccounts: Boolean = true,
    /** Which filters History offers (brand `history.filter`). */
    val historyFilter: HistoryFilterOption = HistoryFilterOption.Types,
    /** What the wallet did to the notes by itself, to tell the user in a short message; see [HomeViewModel.noticeShown]. */
    val notice: MaintenanceNotice? = null,
    /** The active account's mints that say they don't know some of its notes (set aside, out of the balance). */
    val unknownNotes: List<AccountMint> = emptyList(),
    /** The mints are being asked about those notes again ([HomeViewModel.recheckUnknown]). */
    val checkingUnknown: Boolean = false,
    /** Notes received as links and not claimed yet, or no longer claimable: kept apart from the balance. */
    val received: List<ReceivedNote> = emptyList(),
    /** What the notes handed out as links and not claimed yet are worth: out of [balanceMsat], and still the user's to take back. */
    val sharedMsat: Long = 0,
) {
    /** What [received] notes not claimed yet are worth, as far as known: not in [balanceMsat]. */
    val incomingMsat: Long
        get() = received.filter { it.isPending }.sumOf { it.amountMsat ?: it.declaredMsat ?: 0 }

    /** The mint whose changed signing key the user should look at: one the last refresh found, or a received note's. */
    val keyChangedMint: String?
        get() = keyChangedHost ?: received.firstOrNull { it.state == ReceivedState.KeyChanged }?.host

    /** What the wallet tab is about, for the brand's design: a failed refresh, money, or none yet. */
    val status: WalletStatus
        get() =
            when {
                activeAccountId == null -> WalletStatus.None
                error != null -> WalletStatus.Problem(ProblemKind.Offline)
                balanceMsat > 0 || sharedMsat > 0 || received.any { it.isPending } -> WalletStatus.Idle
                else -> WalletStatus.Empty
            }
}

@HiltViewModel
class HomeViewModel(
    private val wallet: Wallet,
    private val preferences: AppPreferences,
    private val defaults: AppDefaults,
    private val clock: () -> Long,
) : ViewModel() {
    @Inject
    constructor(
        wallet: Wallet,
        preferences: AppPreferences,
        defaults: AppDefaults,
    ) : this(wallet, preferences, defaults, System::currentTimeMillis)

    private val status =
        MutableStateFlow(
            HomeState(
                historyFilter = defaults.historyFilter,
                notesVisible = defaults.notesView != NotesView.Hidden,
                mintsVisible = defaults.showMints,
            )
        )

    /** Whether Home is on screen, as the screen says ([setVisible]). */
    private var visible = false

    private val inactiveAddress =
        wallet.addresses.map { entries ->
            entries.firstOrNull { it.status == AddressStatus.Taken || it.status == AddressStatus.Available }?.address?.address
        }
    private val walletFlags =
        combine(
            wallet.backupVerified,
            inactiveAddress,
            preferences.balanceHidden,
            wallet.hasMint,
            wallet.unscannedMints.map { it.isNotEmpty() },
            ::WalletFlags,
        )
    private val work =
        combine(wallet.task, wallet.noteStrategy) { task, strategy ->
            task to strategy.workingText?.takeIf { task is WalletTask.Maintaining && task.strategyId == strategy.id }
        }
    private val walletNames =
        wallet.walletGroups.map { groups -> groups.mapNotNull { g -> g.wallet.name?.let { g.wallet.id to it } }.toMap() }
    private val accountInfo = combine(wallet.accounts, wallet.activeAccount, work, walletNames, ::AccountInfo)

    val state: StateFlow<HomeState> =
        combine(accountInfo, wallet.activity, walletFlags, status) {
                (accounts, active, work, names),
                activity,
                (backedUp, inactive, hidden, hasMint, unscanned),
                s ->
                val balance = active?.balanceMsat ?: 0
                s.copy(
                    task = work.first,
                    strategyText = work.second,
                    accounts = accounts,
                    activeAccountId = active?.account?.id,
                    inactiveAddress = inactive,
                    balanceMsat = balance,
                    activity = activity,
                    showBackupPrompt = defaults.remindBackup(balance) && !backedUp && !s.backupPromptDismissed,
                    showAddMintPrompt = active != null && !hasMint,
                    showScanPrompt = active != null && unscanned,
                    balanceHidden = hidden,
                    walletNames = names,
                    showAccounts = wallet.maxAccounts > 1 || wallet.maxWallets > 1 || accounts.size > 1,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState(loaded = false))

    fun switchAccount(id: String) {
        viewModelScope.launch {
            wallet.switchAccount(id)
            // The backup prompt is per account; a dismissal applies to the one it was shown for.
            status.update { it.copy(backupPromptDismissed = false) }
            refresh()
        }
    }

    fun toggleBalanceHidden() {
        viewModelScope.launch { preferences.setBalanceHidden(!state.value.balanceHidden) }
    }

    /** Hides the backup prompt until the app is restarted. */
    fun dismissBackupPrompt() = status.update { it.copy(backupPromptDismissed = true) }

    init {
        refresh()
        viewModelScope.launch { collectNotices() }
        viewModelScope.launch {
            wallet.accountMints.collect { mints -> status.update { it.copy(unknownNotes = mints.filter { m -> m.unknownCount > 0 }) } }
        }
        viewModelScope.launch {
            wallet.notes.collect { notes ->
                status.update { it.copy(sharedMsat = notes.filter { n -> n.state.isShared }.sumOf { n -> n.amountMsat }) }
            }
        }
        viewModelScope.launch {
            wallet.receivedNotes.collect { notes -> status.update { it.copy(received = ReceivedNote.shown(notes)) } }
        }
    }

    /** Forgets a received note that can no longer be claimed. */
    fun dismissReceived(id: String) {
        viewModelScope.launch { wallet.dismissReceivedNote(id) }
    }

    /** Asks the mints now about the notes they said they don't know; those they know again are back in the balance. */
    fun recheckUnknown() {
        if (status.value.checkingUnknown) return
        status.update { it.copy(checkingUnknown = true) }
        viewModelScope.launch {
            try {
                wallet.recheckUnknownNotes()
            } catch (e: IOException) {
                // Still unknown; the wallet asks again on a later refresh.
            } finally {
                status.update { it.copy(checkingUnknown = false) }
            }
        }
    }

    /**
     * Turns the note policies' runs into messages for the user, as the brand and the user want them. A run while Home is on screen is told;
     * one while another screen is up (claiming a note, receiving a payment, which say what happened themselves) only if it changed the
     * balance, then once Home is back. Runs from before this screen, or of another account than the one switched to, are no news.
     */
    private suspend fun collectNotices() {
        val notesVisible = defaults.notesView != NotesView.Hidden
        var account: String? = null
        var since = 0L
        var last: PolicyRun? = null
        combine(wallet.activeAccount.map { it?.account?.id }.distinctUntilChanged(), wallet.lastPolicyRun, ::Pair).collect { (id, run) ->
            if (id != account) {
                account = id
                since = clock()
            }
            if (run == null || run == last || run.atMillis < since) return@collect
            last = run
            if (!defaults.maintenanceNotices.active(wallet.maintenanceNoticesChoice.first())) return@collect
            if (!notesVisible && !defaults.maintenanceNotices.whenNotesHidden) return@collect
            val notice = MaintenanceNotice.of(run, notesVisible, defaults.showMints) ?: return@collect
            if (visible || notice.balanceChangeMsat != null) status.update { it.copy(notice = notice) }
        }
    }

    /** Home came on screen or left it. A message that waited while it was away shows now. */
    fun setVisible(onScreen: Boolean) {
        visible = onScreen
    }

    /** The message was shown (or the user dismissed it). */
    fun noticeShown() = status.update { it.copy(notice = null) }

    fun refresh() {
        if (status.value.refreshing) return
        status.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            var keyChanged: String? = null
            val error =
                try {
                    wallet.refresh()
                    null
                } catch (e: IOException) {
                    e.message ?: "network error"
                } catch (e: MintKeyChangedException) {
                    keyChanged = e.host
                    null
                }
            status.update {
                it.copy(refreshing = false, error = error, keyChangedHost = keyChanged, unresolved = wallet.unresolvedOperations())
            }
        }
    }
}

private data class WalletFlags(
    val backedUp: Boolean,
    val inactiveAddress: String?,
    val hidden: Boolean,
    val hasMint: Boolean,
    val unscanned: Boolean,
)

private data class AccountInfo(
    val accounts: List<AccountSummary>,
    val active: AccountSummary?,
    val work: Pair<WalletTask?, Int?>,
    val walletNames: Map<String, String>,
)
