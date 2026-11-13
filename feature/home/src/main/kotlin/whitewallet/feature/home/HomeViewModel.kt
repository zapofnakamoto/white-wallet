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
import whitewallet.core.wallet.NotesCheck
import whitewallet.core.wallet.NotesView
import whitewallet.core.wallet.PolicyRun
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.RevealsNoteSecret

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
    /** What asking again found, to tell the user in a short message; see [HomeViewModel.unknownCheckShown]. */
    val unknownCheck: UnknownCheck? = null,
    /** Notes received as links and not claimed yet, or no longer claimable: kept apart from the balance. */
    val received: List<ReceivedNote> = emptyList(),
    /** What the notes handed out as links and not claimed yet are worth: out of [balanceMsat], and still the user's to take back. */
    val sharedMsat: Long = 0,
    /** The active account's home mint, whatever it holds, then its other mints that hold money of the balance, the largest first. */
    val mints: List<AccountMint> = emptyList(),
) {
    /**
     * The balance per mint, shown under it once some of it is outside the home mint (or, without a home mint, on more than one): a payment
     * is made from one mint's money, so the total alone promises more than can be sent at once. The home mint has its row even when empty.
     * Not where the brand hides mints, nor while the balance is hidden.
     */
    val balanceByMint: List<AccountMint>
        get() = mints.takeIf { mintsVisible && !balanceHidden && it.size > 1 && it.any { m -> !m.isHome } }.orEmpty()

    /** The rows Home has room for: all of [balanceByMint] up to [MINT_ROWS] and one, else the first [MINT_ROWS]. */
    val mintRows: List<AccountMint>
        get() = balanceByMint.let { if (it.size > MINT_ROWS + 1) it.take(MINT_ROWS) else it }

    /** The mints of [balanceByMint] without a row of their own: told as one row, with what they hold together. */
    val moreMints: List<AccountMint>
        get() = balanceByMint.drop(mintRows.size)

    val activeAccount: AccountSummary?
        get() = accounts.firstOrNull { it.account.id == activeAccountId }

    /** How many wallets (recovery phrases) the accounts belong to. */
    val walletCount: Int
        get() = accounts.distinctBy { it.account.walletId }.size

    /** The active account's wallet as the how-manieth, from 1, in the accounts' order: what an unnamed wallet is called by. */
    val activeWalletNumber: Int
        get() = accounts.map { it.account.walletId }.distinct().indexOf(activeAccount?.account?.walletId) + 1

    /** What [received] notes not claimed yet are worth, as far as known: not in [balanceMsat]. */
    val incomingMsat: Long
        get() = received.filter { it.isPending }.sumOf { it.amountMsat ?: it.declaredMsat ?: 0 }

    /** The mint whose changed signing key the user should look at: one the last refresh found, or a received note's. */
    val keyChangedMint: String?
        get() = keyChangedHost ?: received.firstOrNull { it.state == ReceivedState.KeyChanged }?.host

    /**
     * What Home warns about, the most important first: the notes, then what keeps money from coming in, then the reminders. Home shows the
     * first and says how many more there are.
     */
    val alerts: List<HomeAlert>
        get() = buildList {
            keyChangedMint?.let { add(HomeAlert.KeyChanged(it)) }
            unknownNotes.forEach { add(HomeAlert.UnknownNotes(it)) }
            inactiveAddress?.let { add(HomeAlert.AddressInactive(it)) }
            if (showAddMintPrompt) add(HomeAlert.AddMint)
            if (showBackupPrompt) add(HomeAlert.Backup)
            if (showScanPrompt) add(HomeAlert.Scan)
        }

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

/** A card Home warns with, above the balance; see [HomeState.alerts]. */
sealed interface HomeAlert {
    data class KeyChanged(val host: String) : HomeAlert

    data class UnknownNotes(val mint: AccountMint) : HomeAlert

    data class AddressInactive(val address: String) : HomeAlert

    data object AddMint : HomeAlert

    data object Backup : HomeAlert

    data object Scan : HomeAlert
}

/** What asking the mints again about the notes they don't know found ([HomeViewModel.recheckUnknown]). */
sealed interface UnknownCheck {
    /** The mint knows [notes] of them again: they're back in the balance. */
    data class Back(val notes: Int) : UnknownCheck

    /** The mint answered, and still doesn't know the [notes] asked about. */
    data class Still(val notes: Int) : UnknownCheck

    /** No answer, from [host] if it is known which mint: nothing was learnt. */
    data class Unreachable(val host: String?) : UnknownCheck

    companion object {
        /** The one thing to say about [check]: good news first, then a mint that didn't answer; null if there was nothing to ask. */
        fun of(check: NotesCheck): UnknownCheck? =
            when {
                check.checked > 0 -> Back(check.checked)
                check.unreachable.isNotEmpty() -> Unreachable(check.unreachable.first())
                check.unknown > 0 -> Still(check.unknown)
                else -> null
            }
    }
}

/** How many mints have a row of their own under the balance; one more is shown rather than counted as "1 more". */
private const val MINT_ROWS = 3

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
            wallet.accountMints.collect { mints ->
                status.update {
                    it.copy(
                        unknownNotes = mints.filter { m -> m.unknownCount > 0 },
                        mints =
                            mints
                                .filter { m -> m.isHome || m.balanceMsat > 0 }
                                .sortedWith(compareBy({ m -> !m.isHome }, { m -> -m.balanceMsat }, { m -> m.host })),
                    )
                }
            }
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

    /** Forgets a received note someone else spent. */
    fun removeReceived(id: String) {
        viewModelScope.launch { wallet.removeReceivedNote(id) }
    }

    /** Puts a kept received note aside, into the archive (in the notes view). */
    fun archiveReceived(id: String) {
        viewModelScope.launch { wallet.archiveReceivedNote(id) }
    }

    /** Opens a received note that only Lightning can bring in, in Claim ([open] gets its link). */
    fun receiveReceived(id: String, open: (String) -> Unit) {
        viewModelScope.launch { @OptIn(RevealsNoteSecret::class) wallet.receivedNoteLink(id)?.let(open) }
    }

    /**
     * Asks the mints now about the notes they said they don't know; those they know again are back in the balance. What it found is told
     * ([HomeState.unknownCheck]), also when nothing changed: a tap always has an answer.
     */
    fun recheckUnknown() {
        if (status.value.checkingUnknown) return
        status.update { it.copy(checkingUnknown = true, unknownCheck = null) }
        viewModelScope.launch {
            val found =
                try {
                    UnknownCheck.of(wallet.recheckUnknownNotes())
                } catch (e: IOException) {
                    // Still unknown; the wallet asks again on a later refresh.
                    UnknownCheck.Unreachable(null)
                }
            status.update { it.copy(checkingUnknown = false, unknownCheck = found) }
        }
    }

    /** The message about [HomeState.unknownCheck] was shown. */
    fun unknownCheckShown() = status.update { it.copy(unknownCheck = null) }

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
