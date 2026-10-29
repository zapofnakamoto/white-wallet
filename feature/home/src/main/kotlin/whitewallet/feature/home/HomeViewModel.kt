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
import whitewallet.core.model.Profile
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
    val profile: Profile? = null,
    /** The active account's history, newest first, maintenance included (History filters it). */
    val activity: List<Activity> = emptyList(),
    val refreshing: Boolean = false,
    val unresolved: Int = 0,
    val error: String? = null,
    /** A mint that now presents another signing key than the pinned one: the wallet stops working with it. */
    val keyChangedHost: String? = null,
    /** Funds are at risk until the recovery phrase is backed up. */
    val showBackupPrompt: Boolean = false,
    /** The mint no longer routes the profile's address to this wallet (checked on refresh). */
    val addressInactive: Boolean = false,
    val backupPromptDismissed: Boolean = false,
    /** The balance shows as dots until tapped (kept across launches). */
    val balanceHidden: Boolean = false,
    /** Another account may be created or restored (brand `accounts.max`). */
    val canAddAccount: Boolean = true,
    /** Accounts are a thing in this app: hidden when the brand allows one and there is one. */
    val showAccounts: Boolean = true,
    /** Which filters History offers (brand `history.filter`). */
    val historyFilter: HistoryFilterOption = HistoryFilterOption.Types,
    /** What the wallet did to the notes by itself, to tell the user in a short message; see [HomeViewModel.noticeShown]. */
    val notice: MaintenanceNotice? = null,
) {
    /** What the wallet tab is about, for the brand's design: a failed refresh, money, or none yet. */
    val status: WalletStatus
        get() = when {
            activeAccountId == null -> WalletStatus.None
            error != null -> WalletStatus.Problem(ProblemKind.Offline)
            balanceMsat > 0 -> WalletStatus.Idle
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
    @Inject constructor(wallet: Wallet, preferences: AppPreferences, defaults: AppDefaults) : this(wallet, preferences, defaults, System::currentTimeMillis)

    private val status = MutableStateFlow(HomeState(historyFilter = defaults.historyFilter, notesVisible = defaults.notesView != NotesView.Hidden, mintsVisible = defaults.showMints))

    /** Whether Home is on screen, as the screen says ([setVisible]). */
    private var visible = false

    private val walletFlags = combine(wallet.backupVerified, wallet.addressStatus, preferences.balanceHidden, ::Triple)
    private val work = combine(wallet.task, wallet.noteStrategy) { task, strategy ->
        task to strategy.workingText?.takeIf { task is WalletTask.Maintaining && task.strategyId == strategy.id }
    }
    private val accountInfo = combine(wallet.accounts, wallet.activeAccount, work, ::Triple)

    val state: StateFlow<HomeState> = combine(accountInfo, wallet.profile, wallet.activity, walletFlags, status) { (accounts, active, work), profile, activity, (backedUp, address, hidden), s ->
        val balance = active?.balanceMsat ?: 0
        s.copy(
            task = work.first,
            strategyText = work.second,
            accounts = accounts,
            activeAccountId = active?.account?.id,
            addressInactive = address == AddressStatus.Taken || address == AddressStatus.Available,
            balanceMsat = balance,
            profile = profile,
            activity = activity,
            showBackupPrompt = balance > 0 && !backedUp && !s.backupPromptDismissed,
            balanceHidden = hidden,
            canAddAccount = accounts.size < wallet.maxAccounts,
            showAccounts = wallet.maxAccounts > 1 || accounts.size > 1,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState(loaded = false))

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
    }

    /**
     * Turns the note policies' runs into messages for the user, as the brand and the user want them. A run while
     * Home is on screen is told; one while another screen is up (claiming a note, receiving a payment, which say what
     * happened themselves) only if it changed the balance, then once Home is back. Runs from before this screen, or
     * of another account than the one switched to, are no news.
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
            val error = try {
                wallet.refresh()
                null
            } catch (e: IOException) {
                e.message ?: "network error"
            } catch (e: MintKeyChangedException) {
                keyChanged = e.host
                null
            }
            status.update { it.copy(refreshing = false, error = error, keyChangedHost = keyChanged, unresolved = wallet.unresolvedOperations()) }
        }
    }
}
