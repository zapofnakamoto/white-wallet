package whitewallet.feature.receive

import androidx.lifecycle.SavedStateHandle
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
import whitewallet.core.wallet.Connection
import whitewallet.core.wallet.FaucetState
import whitewallet.core.wallet.InvoiceRequest
import whitewallet.core.wallet.InvoiceStatus
import whitewallet.core.wallet.NoHome
import whitewallet.core.wallet.RefreshResult
import whitewallet.core.wallet.RegtestFaucet
import whitewallet.core.wallet.Wallet
import whitewallet.core.wallet.noHome

/** Why no invoice could be made; each maps to a full string. */
sealed interface InvoiceProblem {
    data class OutOfRange(val minMsat: Long, val maxMsat: Long) : InvoiceProblem

    /** The phone is offline. */
    data object Offline : InvoiceProblem

    /** The mint that makes the invoice didn't answer. */
    data class MintNotAnswering(val host: String) : InvoiceProblem

    /** No answer over a network not known to reach the internet: that says nothing about the mint. */
    data object NoAnswer : InvoiceProblem

    data object Refused : InvoiceProblem

    /** The mint's signing key doesn't match the pinned one: no invoice is made there until it does, or the user accepts the new key. */
    data class KeyChanged(val host: String) : InvoiceProblem
}

/** An invoice on screen, the mint it is on, and where it stands. */
data class ShownInvoice(val request: InvoiceRequest.Ready, val status: InvoiceStatus = InvoiceStatus.Waiting, val host: String? = null)

/**
 * A mint the account can receive on: whether it is home, the Lightning address it has there, if any, and whether its signing key no longer
 * matches (nothing is received there then).
 */
data class ReceiveMint(val host: String, val isHome: Boolean, val address: String?, val keyChanged: Boolean = false)

data class ReceiveState(
    /**
     * The Lightning address shown: the one the user picked, else the primary one, else one on the home mint; null for none (then it
     * receives by invoice, made at the home mint).
     */
    val address: String? = null,
    /** How many Lightning addresses the account has to show; more than the one shown can be switched through. */
    val addressCount: Int = 0,
    /** What came to the address while the screen is open: the total, and how many payments it adds up. */
    val receivedMsat: Long = 0,
    val receivedPayments: Int = 0,
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
    /** The mint of the [address] shown didn't answer the last time the wallet asked: payments to it can't arrive until it does. */
    val addressMintNotAnswering: Boolean = false,
    /** The signing key of the [address]'s mint doesn't match the pinned one: payments to it wait there until it does. */
    val addressKeyChanged: Boolean = false,
    /** The mints whose signing key is being asked for: nothing is made on them until the answer is back. */
    val keyChecking: Set<String> = emptySet(),
    /** The brand names mints (brand `mints.visible`). */
    val mintsVisible: Boolean = true,
    /** The phone is offline: no invoice can be made, and the amount form says so before the user tries. */
    val offline: Boolean = false,
    /** The mint the user picked to receive on; null leaves it to the wallet (the address it offers first, else the home mint). */
    val on: String? = null,
    val homeHost: String? = null,
    /** The mints the account can receive on, home first; one to pick from once there are several (and the brand names mints). */
    val mints: List<ReceiveMint> = emptyList(),
) {
    /** The mint what is shown receives on: the invoice's, the address's, else the one an invoice would be made on. */
    val receivingHost: String?
        get() = invoice?.host ?: addressHost.takeIf { invoice == null && !showAmountForm } ?: on ?: homeHost

    /** The signing key of the mint received on is being asked for: the form says so and waits. */
    val receivingChecked: Boolean
        get() = receivingHost !in keyChecking

    /** The signing key of the mint received on doesn't match the pinned one: the form says so before the user tries, and makes nothing. */
    val receivingKeyChanged: Boolean
        get() = mints.any { it.host == receivingHost && it.keyChanged }

    /** Another mint can be picked: the brand names mints and the account has more than one to receive on. */
    val canPickMint: Boolean
        get() = mintsVisible && mints.size > 1

    /** The mint of the [address] shown. */
    val addressHost: String?
        get() = address?.substringAfterLast('@')

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

    /** How many payments [arrivedMsat] is: a paid invoice is always one. */
    val arrivedPayments: Int
        get() = if (invoice == null) receivedPayments.coerceAtLeast(1) else 1

    val amountMsat: Long?
        get() = amountText.trim().toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000 }
}

/** What arrived at the address while the screen is open: [msat] in all, from [payments] payments. */
internal data class Arrivals(val payments: Int = 0, val msat: Long = 0) {
    /** With what a refresh found (nothing, if it found no money). */
    operator fun plus(result: RefreshResult) =
        if (result.receivedMsat > 0) Arrivals(payments + result.newNotes.coerceAtLeast(1), msat + result.receivedMsat) else this
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
    /** The mint the user picked (see [ReceiveState.on]). */
    val on: String? = null,
)

/** The active account's registered addresses, the one Receive offers first leading; that one is null if none is offered first. */
private data class AddressChoice(val first: String?, val all: List<String>)

/**
 * Shows the Lightning address and checks for incoming payments while the screen is open; or, without an address (or on request), an invoice
 * for an amount that mints to this account (see [Wallet.requestInvoice]).
 */
@HiltViewModel
class ReceiveViewModel
@Inject
constructor(
    private val wallet: Wallet,
    private val faucet: RegtestFaucet,
    private val defaults: AppDefaults,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val timings = defaults.timings
    private val mintsVisible = defaults.showMints
    private val received = MutableStateFlow(Arrivals())
    // Opened from a mint (Home's mint row, its page): received there.
    private val ui = MutableStateFlow(ReceiveUi(on = savedState.get<String>("on")?.takeIf { it.isNotBlank() }))
    private var watch: Job? = null

    /** The mints whose signing key is being asked for; null until it is known which are: nothing is drawn before. */
    private val checking = MutableStateFlow<Set<String>?>(null)

    val state: StateFlow<ReceiveState> =
        combine(addressChoice(), received, ui, faucet.available, wallet.noHome) { choice, arrivals, u, canFaucet, noHome ->
                ReceiveState(
                    u.chosen?.takeIf { it in choice.all }
                        ?: u.on?.let { on -> choice.all.firstOrNull { it.substringAfterLast('@') == on } }
                        ?: choice.first.takeIf { u.on == null },
                    choice.all.size,
                    receivedMsat = arrivals.msat,
                    receivedPayments = arrivals.payments,
                    requestingAmount = u.requestingAmount,
                    amountText = u.amountText,
                    creating = u.creating,
                    problem = u.problem,
                    invoice = u.invoice,
                    faucet = u.faucet.takeIf { canFaucet },
                    // Receiving on a mint picked by hand needs no home.
                    noHome = noHome.takeIf { u.on == null },
                    mintsVisible = mintsVisible,
                    on = u.on,
                )
            }
            .combine(receiveMints()) { state, (home, mints) -> state.copy(homeHost = home, mints = mints) }
            .combine(wallet.mintsNotAnswering) { state, silent -> state.copy(addressMintNotAnswering = state.addressHost in silent) }
            .combine(wallet.mintsWithChangedKey) { state, changed ->
                state.copy(
                    addressKeyChanged = state.addressHost in changed,
                    mints = state.mints.map { it.copy(keyChanged = it.host in changed) },
                )
            }
            .combine(wallet.offline) { state, offline -> state.copy(offline = offline) }
            .combine(checking) { state, asking -> asking?.let { state.copy(keyChecking = it) } ?: state.copy(loaded = false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiveState(loaded = false))

    /** The primary address first, else one on the home mint; addresses still held for their fee aren't offered. */
    private fun addressChoice() =
        combine(wallet.primaryAddress, wallet.addresses, wallet.homeHost) { primary, entries, home ->
            val registered = entries.map { it.address }.filter { it.feeInvoice == null }
            val first = primary?.takeIf { it.feeInvoice == null } ?: registered.firstOrNull { it.host == home }
            AddressChoice(first?.address, (listOfNotNull(first) + registered).distinct().map { it.address })
        }

    /**
     * The home mint, and the mints the account can receive on: the wallet's mints it holds a key of (an invoice is minted to the account
     * there), that the brand lets hold money; home first. Each with the address Receive shows there (the primary one first).
     */
    private fun receiveMints() =
        combine(wallet.mints, addressChoice(), wallet.homeHost) { mints, choice, home ->
            home to
                mints
                    .filter { it.pubkey != null && defaults.mintAllowed(it.host) }
                    .map { mint ->
                        ReceiveMint(mint.host, mint.host == home, choice.all.firstOrNull { it.substringAfterLast('@') == mint.host })
                    }
                    .sortedBy { !it.isHome }
        }

    init {
        // Whether each mint's signing key still matches, asked before the user tries (once per app session): the address and the list say
        // so,
        // and nothing is made on a mint while it is asked.
        viewModelScope.launch {
            val ask = receiveMints().first().second.map { it.host }.filterNot { wallet.mintKeyCompared(it) }
            checking.value = ask.toSet()
            ask.forEach { host ->
                launch {
                    try {
                        wallet.checkMintKey(host)
                    } finally {
                        checking.update { it.orEmpty() - host }
                    }
                }
            }
        }
        viewModelScope.launch {
            // Back online, an invoice that failed for being offline is no news: the form says what it is now.
            wallet.followOffline { _, back ->
                if (back) ui.update { it.copy(problem = it.problem.takeUnless { p -> p == InvoiceProblem.Offline }) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(timings.receivePollMillis)
                // Offline, there's nobody to ask: what arrived meanwhile is found once the phone is back.
                if (wallet.offline.value) continue
                val result = runCatching { wallet.refresh() }.getOrNull()
                if (result != null) received.update { it + result }
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
            // Another mint's address: the screen is on that mint now.
            ui.update {
                it.copy(chosen = next, on = it.on?.let { _ -> next.substringAfterLast('@') }, requestingAmount = false, problem = null)
            }
        }
    }

    /** Receives on [host] from now on (null: as the wallet chooses): its address if the account has one there, else an invoice. */
    fun receiveOn(host: String?) {
        watch?.cancel()
        ui.update { it.copy(on = host, chosen = null, requestingAmount = false, problem = null, invoice = null) }
    }

    fun onAmountChange(value: String) = ui.update { it.copy(amountText = value.filter(Char::isDigit), problem = null) }

    fun createInvoice() {
        val amount = state.value.amountMsat ?: return
        if (ui.value.creating || state.value.offline) return
        ui.update { it.copy(creating = true, problem = null) }
        val on = ui.value.on
        viewModelScope.launch {
            val host = on ?: wallet.homeHost.first()
            val problem =
                when (val result = wallet.requestInvoice(amount, on)) {
                    is InvoiceRequest.Ready -> {
                        ui.update { it.copy(creating = false, invoice = ShownInvoice(result, host = host)) }
                        watch(result.id)
                        return@launch
                    }
                    is InvoiceRequest.OutOfRange -> InvoiceProblem.OutOfRange(result.minMsat, result.maxMsat)
                    InvoiceRequest.Unreachable ->
                        when (wallet.connection.value) {
                            Connection.Offline -> InvoiceProblem.Offline
                            Connection.Unverified -> InvoiceProblem.NoAnswer
                            Connection.Online -> host?.let(InvoiceProblem::MintNotAnswering) ?: InvoiceProblem.NoAnswer
                        }
                    InvoiceRequest.Refused -> InvoiceProblem.Refused
                    InvoiceRequest.KeyChanged -> host?.let(InvoiceProblem::KeyChanged) ?: InvoiceProblem.Refused
                }
            ui.update { it.copy(creating = false, problem = problem) }
        }
    }

    /** Leaves the success screen: back to the address (or the amount form), counting from zero again. */
    fun receiveMore() {
        received.value = Arrivals()
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
                if (result != null) received.update { it + result }
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
