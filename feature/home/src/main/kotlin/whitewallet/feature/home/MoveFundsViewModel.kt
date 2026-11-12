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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.model.AccountSummary
import whitewallet.core.wallet.MintKeyChangedException
import whitewallet.core.wallet.MoveFailure
import whitewallet.core.wallet.MoveResult
import whitewallet.core.wallet.Wallet

data class MoveFundsState(
    val from: AccountSummary? = null,
    val targets: List<AccountSummary> = emptyList(),
    /** Each account's place among all accounts, oldest first: its mark's colour. */
    val places: Map<String, Int> = emptyMap(),
    val targetId: String? = null,
    /** Whole sats as typed; ignored when [everything] is set. */
    val amountSats: String = "",
    val everything: Boolean = true,
    val busy: Boolean = false,
    val result: MoveResult? = null,
    /** Each wallet's number among the phone's wallets, oldest first, from 1, by wallet id. */
    val walletNumbers: Map<String, Int> = emptyMap(),
    /** The names the user gave wallets, by wallet id; a wallet without one shows its number. */
    val walletNames: Map<String, String> = emptyMap(),
    /** The mints this move would add to the list of the target's wallet: another wallet that doesn't have them yet. */
    val addsMints: List<String> = emptyList(),
) {
    /** With several wallets, an account's name alone doesn't say whose money this is: each is shown under its wallet. */
    val showWallets: Boolean
        get() = walletNumbers.size > 1

    /** The accounts of other wallets are folded away until asked for, unless the wallet the money leaves has no other account. */
    val foldOthers: Boolean
        get() =
            targets.any { it.account.walletId == from?.account?.walletId } && targets.any { it.account.walletId != from?.account?.walletId }

    /** The targets by wallet id, the wallet the money leaves first, then oldest first. */
    val targetsByWallet: List<Pair<String, List<AccountSummary>>>
        get() =
            targets
                .groupBy { it.account.walletId }
                .toList()
                .sortedBy { (walletId, _) -> if (walletId == from?.account?.walletId) 0 else walletNumbers[walletId] ?: Int.MAX_VALUE }

    val amountMsat: Long?
        get() = if (everything) null else amountSats.toLongOrNull()?.times(1000)

    val canMove: Boolean
        get() =
            !busy && targetId != null && (from?.balanceMsat ?: 0) > 0 && (everything || (amountMsat ?: 0) in 1..(from?.balanceMsat ?: 0))
}

private data class Input(
    val targetId: String? = null,
    val amountSats: String = "",
    val everything: Boolean = true,
    val busy: Boolean = false,
    val result: MoveResult? = null,
)

@HiltViewModel
class MoveFundsViewModel @Inject constructor(private val wallet: Wallet) : ViewModel() {
    private val input = MutableStateFlow(Input())

    val state: StateFlow<MoveFundsState> =
        combine(wallet.accounts, wallet.activeAccount, wallet.walletGroups, input) { accounts, active, groups, i ->
                val targets = accounts.filter { it.account.id != active?.account?.id }
                val places = accounts.withIndex().associate { (place, it) -> it.account.id to place }
                MoveFundsState(
                    active,
                    targets,
                    places,
                    i.targetId ?: targets.singleOrNull()?.account?.id,
                    i.amountSats,
                    i.everything,
                    i.busy,
                    i.result,
                    groups.associate { it.wallet.id to it.number },
                    groups.mapNotNull { g -> g.wallet.name?.let { g.wallet.id to it } }.toMap(),
                )
            }
            .mapLatest { s -> s.copy(addsMints = s.targetId?.let { wallet.moveAddsMints(it, s.amountMsat) }.orEmpty()) }
            // no-flash: without the account it moves from (null here), the screen draws nothing.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoveFundsState())

    fun onTarget(id: String) = input.update { it.copy(targetId = id) }

    fun onEverything(value: Boolean) = input.update { it.copy(everything = value) }

    fun onAmount(value: String) = input.update { it.copy(amountSats = value.filter(Char::isDigit).take(12)) }

    fun move() {
        val s = state.value
        val target = s.targetId ?: return
        if (!s.canMove) return
        input.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result =
                try {
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
