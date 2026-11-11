package whitewallet.feature.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.FaucetState
import whitewallet.core.wallet.InvoiceRequest
import whitewallet.core.wallet.InvoiceStatus
import whitewallet.core.wallet.RegtestFaucet
import whitewallet.core.wallet.Wallet

/** Why no invoice could be made; each maps to a full string. */
sealed interface InvoiceProblem {
    data class OutOfRange(val minMsat: Long, val maxMsat: Long) : InvoiceProblem

    data object Unreachable : InvoiceProblem

    data object Refused : InvoiceProblem
}

/** Why the account can't receive yet: its wallet has no home mint. */
enum class NoHome {
    /** Its wallet has no mint at all: add one. */
    NoMint,

    /** Its wallet has mints, but its home mint was removed: pick one of them. */
    PickHome,
}

/** An invoice on screen and where it stands. */
data class ShownInvoice(val request: InvoiceRequest.Ready, val status: InvoiceStatus = InvoiceStatus.Waiting)

data class ReceiveState(
    /**
     * The Lightning address shown: the one the user picked, else the primary one, else one on the home mint; null for none (then it
     * receives by invoice, made at the home mint).
     */
    val address: String? = null,
    /** How many Lightning addresses the account has to show; more than the one shown can be switched through. */
    val addressCount: Int = 0,
    val receivedMsat: Long = 0,
    /** The user asked for an invoice instead of the address. */
    val requestingAmount: Boolean = false,
    val amountText: String = "",
    val creating: Boolean = false,
    val problem: InvoiceProblem? = null,
    val invoice: ShownInvoice? = null,
    /** Developer: whether the regtest faucet can pay what is shown, and its last attempt. */
    val faucet: FaucetState? = null,
    /** Set while the account has no home mint: nothing can be received until it has one. */
    val noHome: NoHome? = null,
    /** The account's addresses and home mint were read: until then nothing is drawn, not the amount form first and the address after. */
    val loaded: Boolean = true,
) {
    /** The amount form shows: always without an address, else once asked for; never without a home mint. */
    val showAmountForm: Boolean
        get() = noHome == null && invoice == null && (address == null || requestingAmount)

    /** What arrived, once something did: the paid invoice, or what came to the address while the screen is open. */
    val arrivedMsat: Long?
        get() =
            when (val status = invoice?.status) {
                is InvoiceStatus.Paid -> status.receivedMsat
                null -> receivedMsat.takeIf { it > 0 }
                else -> null
            }

    val amountMsat: Long?
        get() = amountText.trim().toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000 }
}

/** What the regtest faucet sends to the address. */
internal const val FAUCET_SATS = 1_000L

private data class ReceiveUi(
    val requestingAmount: Boolean = false,
    val amountText: String = "",
    val creating: Boolean = false,
    val problem: InvoiceProblem? = null,
    val invoice: ShownInvoice? = null,
    val faucet: FaucetState = FaucetState(),
    /** The address the user switched to, if any. */
    val chosen: String? = null,
)

/** The active account's registered addresses, the one Receive offers first leading; that one is null if none is offered first. */
private data class AddressChoice(val first: String?, val all: List<String>)

/**
 * Shows the Lightning address and checks for incoming payments while the screen is open; or, without an address (or on request), an invoice
 * for an amount that mints to this account (see [Wallet.requestInvoice]).
 */
@HiltViewModel
class ReceiveViewModel @Inject constructor(private val wallet: Wallet, private val faucet: RegtestFaucet, defaults: AppDefaults) :
    ViewModel() {
    private val timings = defaults.timings
    private val received = MutableStateFlow(0L)
    private val ui = MutableStateFlow(ReceiveUi())
    private var watch: Job? = null

    val state: StateFlow<ReceiveState> =
        combine(addressChoice(), received, ui, faucet.available, noHome()) { choice, msat, u, canFaucet, noHome ->
                ReceiveState(
                    u.chosen?.takeIf { it in choice.all } ?: choice.first,
                    choice.all.size,
                    receivedMsat = msat,
                    u.requestingAmount,
                    u.amountText,
                    u.creating,
                    u.problem,
                    u.invoice,
                    faucet = u.faucet.takeIf { canFaucet },
                    noHome = noHome,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiveState(loaded = false))

    /** The primary address first, else one on the home mint; addresses still held for their fee aren't offered. */
    private fun addressChoice() =
        combine(wallet.primaryAddress, wallet.addresses, wallet.homeHost) { primary, entries, home ->
            val registered = entries.map { it.address }.filter { it.feeInvoice == null }
            val first = primary?.takeIf { it.feeInvoice == null } ?: registered.firstOrNull { it.host == home }
            AddressChoice(first?.address, (listOfNotNull(first) + registered).distinct().map { it.address })
        }

    private fun noHome() =
        combine(wallet.activeAccount, wallet.homeHost, wallet.hasMint) { active, home, hasMint ->
            when {
                active == null || home != null -> null
                hasMint -> NoHome.PickHome
                else -> NoHome.NoMint
            }
        }

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(timings.receivePollMillis)
                val result = runCatching { wallet.refresh() }.getOrNull()
                if (result != null && result.receivedMsat > 0) received.value += result.receivedMsat
            }
        }
    }

    fun requestAmount() = ui.update { it.copy(requestingAmount = true) }

    /** Shows the account's next Lightning address (the first, from the amount form). */
    fun otherAddress() {
        viewModelScope.launch {
            val all = addressChoice().first().all
            if (all.isEmpty()) return@launch
            val shown = state.value.address
            val next = if (shown == null || ui.value.requestingAmount) all.first() else all[(all.indexOf(shown) + 1) % all.size]
            ui.update { it.copy(chosen = next, requestingAmount = false, problem = null) }
        }
    }

    fun onAmountChange(value: String) = ui.update { it.copy(amountText = value.filter(Char::isDigit), problem = null) }

    fun createInvoice() {
        val amount = state.value.amountMsat ?: return
        if (ui.value.creating) return
        ui.update { it.copy(creating = true, problem = null) }
        viewModelScope.launch {
            val problem =
                when (val result = wallet.requestInvoice(amount)) {
                    is InvoiceRequest.Ready -> {
                        ui.update { it.copy(creating = false, invoice = ShownInvoice(result)) }
                        watch(result.id)
                        return@launch
                    }
                    is InvoiceRequest.OutOfRange -> InvoiceProblem.OutOfRange(result.minMsat, result.maxMsat)
                    InvoiceRequest.Unreachable -> InvoiceProblem.Unreachable
                    InvoiceRequest.Refused -> InvoiceProblem.Refused
                }
            ui.update { it.copy(creating = false, problem = problem) }
        }
    }

    /** Leaves the success screen: back to the address (or the amount form), counting from zero again. */
    fun receiveMore() {
        received.value = 0
        newInvoice()
    }

    /** Back to the amount form, for another invoice. */
    fun newInvoice() {
        watch?.cancel()
        ui.update { it.copy(invoice = null) }
    }

    /**
     * Developer: has the regtest faucet pay the invoice on screen, or [FAUCET_SATS] to the address. A payment to the address is then looked
     * for at once rather than at the next poll; the invoice's own watch notices it.
     */
    fun payFromFaucet() {
        val s = state.value
        val target = s.invoice?.takeIf { it.status == InvoiceStatus.Waiting }?.request?.invoice ?: s.address ?: return
        if (ui.value.faucet.busy) return
        ui.update { it.copy(faucet = FaucetState(busy = true)) }
        viewModelScope.launch {
            val error = runCatching { faucet.pay(target, FAUCET_SATS) }.exceptionOrNull()
            ui.update { it.copy(faucet = FaucetState(error = error?.let { e -> e.message ?: e.javaClass.simpleName })) }
            if (error == null && s.invoice == null) {
                val result = runCatching { wallet.refresh() }.getOrNull()
                if (result != null && result.receivedMsat > 0) received.value += result.receivedMsat
            }
        }
    }

    /** Asks the mint until the invoice is paid or expired, while the screen is open. */
    private fun watch(id: String) {
        watch?.cancel()
        watch = viewModelScope.launch {
            while (isActive) {
                delay(timings.invoicePollMillis)
                val status = runCatching { wallet.invoiceStatus(id) }.getOrNull() ?: continue
                if (status == InvoiceStatus.Waiting) continue
                ui.update { u -> u.copy(invoice = u.invoice?.takeIf { it.request.id == id }?.copy(status = status) ?: u.invoice) }
                break
            }
        }
    }
}
