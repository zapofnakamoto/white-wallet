package whitewallet.feature.home

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
import whitewallet.core.model.AccountSummary
import whitewallet.core.wallet.MoveResult
import whitewallet.core.wallet.Wallet
import java.io.IOException
import whitewallet.core.wallet.MintKeyChangedException
import whitewallet.core.wallet.MoveFailure

data class MoveFundsState(
    val from: AccountSummary? = null,
    val targets: List<AccountSummary> = emptyList(),
    val targetId: String? = null,
    /** Whole sats as typed; ignored when [everything] is set. */
    val amountSats: String = "",
    val everything: Boolean = true,
    val busy: Boolean = false,
    val result: MoveResult? = null,
) {
    val amountMsat: Long? get() = if (everything) null else amountSats.toLongOrNull()?.times(1000)
    val canMove: Boolean get() = !busy && targetId != null && (from?.balanceMsat ?: 0) > 0 &&
        (everything || (amountMsat ?: 0) in 1..(from?.balanceMsat ?: 0))
}

private data class Input(val targetId: String? = null, val amountSats: String = "", val everything: Boolean = true, val busy: Boolean = false, val result: MoveResult? = null)

@HiltViewModel
class MoveFundsViewModel @Inject constructor(private val wallet: Wallet) : ViewModel() {
    private val input = MutableStateFlow(Input())

    val state: StateFlow<MoveFundsState> = combine(wallet.accounts, wallet.activeAccount, input) { accounts, active, i ->
        val targets = accounts.filter { it.account.id != active?.account?.id }
        MoveFundsState(active, targets, i.targetId ?: targets.singleOrNull()?.account?.id, i.amountSats, i.everything, i.busy, i.result)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoveFundsState())

    fun onTarget(id: String) = input.update { it.copy(targetId = id) }
    fun onEverything(value: Boolean) = input.update { it.copy(everything = value) }
    fun onAmount(value: String) = input.update { it.copy(amountSats = value.filter(Char::isDigit).take(12)) }

    fun move() {
        val s = state.value
        val target = s.targetId ?: return
        if (!s.canMove) return
        input.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                wallet.moveFunds(target, s.amountMsat)
            } catch (e: IOException) {
                MoveResult.Failed(MoveFailure.Refused, e.message)
            } catch (e: MintKeyChangedException) {
                MoveResult.Failed(MoveFailure.Refused, e.message)
            }
            input.update { it.copy(busy = false, result = result) }
        }
    }
}
