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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletStatus
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.Profile
import whitewallet.core.wallet.AddressStatus
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.HistoryFilterOption
import whitewallet.core.wallet.MintKeyChangedException
import whitewallet.core.wallet.Wallet

data class HomeState(
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
class HomeViewModel @Inject constructor(private val wallet: Wallet, private val preferences: AppPreferences, defaults: AppDefaults) : ViewModel() {
    private val status = MutableStateFlow(HomeState(historyFilter = defaults.historyFilter))

    private val walletFlags = combine(wallet.backupVerified, wallet.addressStatus, preferences.balanceHidden, ::Triple)
    private val accountInfo = combine(wallet.accounts, wallet.activeAccount, ::Pair)

    val state: StateFlow<HomeState> = combine(accountInfo, wallet.profile, wallet.activity, walletFlags, status) { (accounts, active), profile, activity, (backedUp, address, hidden), s ->
        val balance = active?.balanceMsat ?: 0
        s.copy(
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

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
    }

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
