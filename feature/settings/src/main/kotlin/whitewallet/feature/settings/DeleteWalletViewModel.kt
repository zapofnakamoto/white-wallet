package whitewallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.wallet.Wallet

data class DeleteWalletState(
    /** The active account's primary address. */
    val address: RegisteredAddress? = null,
    /** The wallet's home mint, where its funds are. */
    val host: String? = null,
    val balanceMsat: Long = 0,
    val backupVerified: Boolean = false,
    val unresolved: Int = 0,
    val acknowledged: Boolean = false,
    val confirming: Boolean = false,
    val deleting: Boolean = false,
    val deleted: Boolean = false,
    /** After deleting: whether another account took over (otherwise the app starts over). */
    val otherAccountRemains: Boolean = false,
    /** Every account the wallet's recovery phrase holds: all of them go. */
    val accounts: List<String> = emptyList(),
) {
    /** Money would be lost for good: there is a balance and no verified backup. */
    val atRisk: Boolean
        get() = balanceMsat > 0 && !backupVerified

    val canContinue: Boolean
        get() = !deleting && (!atRisk || acknowledged)
}

private data class Local(
    val unresolved: Int = 0,
    val acknowledged: Boolean = false,
    val confirming: Boolean = false,
    val deleting: Boolean = false,
    val deleted: Boolean = false,
    val otherAccountRemains: Boolean = false,
)

@HiltViewModel
class DeleteWalletViewModel @Inject constructor(private val wallet: Wallet) : ViewModel() {
    private val local = MutableStateFlow(Local())

    val state: StateFlow<DeleteWalletState> =
        combine(wallet.primaryAddress, wallet.homeHost, wallet.activeWalletAccounts, wallet.backupVerified, local) {
                address,
                home,
                members,
                backedUp,
                l ->
                DeleteWalletState(
                    address,
                    home,
                    balanceMsat = members.sumOf { it.balanceMsat },
                    backupVerified = backedUp,
                    l.unresolved,
                    l.acknowledged,
                    l.confirming,
                    l.deleting,
                    l.deleted,
                    l.otherAccountRemains,
                    accounts = members.map { it.account.name },
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeleteWalletState())

    init {
        viewModelScope.launch { local.update { it.copy(unresolved = wallet.unresolvedOperations()) } }
    }

    fun onAcknowledge(value: Boolean) = local.update { it.copy(acknowledged = value) }

    /** First step: the explanation screen's Continue opens the final confirmation. */
    fun onContinue() {
        if (state.value.canContinue) local.update { it.copy(confirming = true) }
    }

    fun onDismissConfirm() = local.update { it.copy(confirming = false) }

    /** Called after the final confirmation (and the device lock, when the phone has one). */
    fun delete() {
        if (local.value.deleting) return
        local.update { it.copy(confirming = false, deleting = true) }
        viewModelScope.launch {
            val remains = wallet.deleteActiveAccount()
            local.update { it.copy(deleting = false, deleted = true, otherAccountRemains = remains) }
        }
    }
}
