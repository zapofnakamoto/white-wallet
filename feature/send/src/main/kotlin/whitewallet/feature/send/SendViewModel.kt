package whitewallet.feature.send

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.wallet.FaucetState
import whitewallet.core.wallet.PayPreview
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.PayQuote
import whitewallet.core.wallet.PayResult
import whitewallet.core.wallet.RegtestFaucet
import whitewallet.core.wallet.Wallet

/** What the regtest faucet's invoice asks for. */
internal const val FAUCET_SATS = 1_000L

data class SendState(
    val input: String = "",
    val scanning: Boolean = false,
    val checking: Boolean = false,
    /** Why the input can't be paid. */
    val problem: PayProblem? = null,
    /** Set once the input is understood: the review step. */
    val preview: PayPreview.Ready? = null,
    /** Whole sats, as typed; ignored when the invoice fixes the amount. */
    val amountText: String = "",
    val comment: String = "",
    val quote: PayQuote? = null,
    val quoting: Boolean = false,
    val paying: Boolean = false,
    val result: PayResult? = null,
    /** Developer: whether the regtest faucet can make an invoice to pay, and its last attempt. */
    val faucet: FaucetState? = null,
) {
    /** The amount to pay in msat, or null while none is entered. */
    val amountMsat: Long? get() = preview?.fixedAmountMsat ?: amountText.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
    val canPay: Boolean get() = !paying && !quoting && quote is PayQuote.Ok && quote.amountMsat == amountMsat
}

@HiltViewModel
class SendViewModel @Inject constructor(
    private val wallet: Wallet,
    private val faucet: RegtestFaucet,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(SendState())
    val state: StateFlow<SendState> = _state
    private var quoteJob: Job? = null

    init {
        viewModelScope.launch {
            faucet.available.collect { available ->
                _state.update { it.copy(faucet = if (available) it.faucet ?: FaucetState() else null) }
            }
        }
        // Opened from a link, shared text or NFC tag: check it right away.
        savedState.get<String>("input")?.takeIf { it.isNotBlank() }?.let(::onScanned)
    }

    fun onInputChange(value: String) = _state.update { it.copy(input = value, problem = null) }

    fun startScan() = _state.update { it.copy(scanning = true, problem = null) }
    fun stopScan() = _state.update { it.copy(scanning = false) }

    /** A scanned or pasted destination is checked right away. */
    fun onScanned(text: String) {
        _state.update { it.copy(input = text.trim(), scanning = false, problem = null) }
        check()
    }

    /**
     * Developer: has the regtest faucet make an invoice for [FAUCET_SATS] that this account's mint can pay, and checks
     * it as if it was pasted, so the rest is the usual flow.
     */
    fun invoiceFromFaucet() {
        if (_state.value.faucet?.busy != false) return
        _state.update { it.copy(faucet = FaucetState(busy = true)) }
        viewModelScope.launch {
            val invoice = runCatching { faucet.invoice(FAUCET_SATS, wallet.homeHost.first() ?: error("no account")) }
            _state.update { it.copy(faucet = FaucetState(error = invoice.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName })) }
            invoice.getOrNull()?.let(::onScanned)
        }
    }

    fun check() {
        val input = _state.value.input
        if (input.isBlank() || _state.value.checking) return
        _state.update { it.copy(checking = true, problem = null) }
        viewModelScope.launch {
            when (val preview = wallet.previewPayment(input)) {
                is PayPreview.Ready -> {
                    _state.update { it.copy(checking = false, preview = preview, amountText = "", comment = "", quote = null, result = null) }
                    if (preview.fixedAmountMsat != null) requote(immediately = true)
                }
                is PayPreview.Invalid -> _state.update { it.copy(checking = false, problem = preview.problem) }
            }
        }
    }

    fun onAmountChange(value: String) {
        _state.update { it.copy(amountText = value.filter(Char::isDigit).take(12)) }
        requote()
    }

    fun onCommentChange(value: String) {
        val max = _state.value.preview?.commentMaxLength ?: 0
        _state.update { it.copy(comment = value.take(max)) }
    }

    /** Back from the review to the input. */
    fun edit() {
        quoteJob?.cancel()
        _state.update { it.copy(preview = null, quote = null, quoting = false, result = null) }
    }

    fun pay() {
        val s = _state.value
        val preview = s.preview ?: return
        val amount = s.amountMsat ?: return
        if (!s.canPay) return
        _state.update { it.copy(paying = true) }
        viewModelScope.launch {
            val result = wallet.pay(preview, amount, s.comment.takeIf { preview.commentMaxLength > 0 })
            _state.update { it.copy(paying = false, result = result) }
        }
    }

    private fun requote(immediately: Boolean = false) {
        quoteJob?.cancel()
        val s = _state.value
        val preview = s.preview ?: return
        val amount = s.amountMsat
        if (amount == null) {
            _state.update { it.copy(quote = null, quoting = false) }
            return
        }
        _state.update { it.copy(quoting = true) }
        quoteJob = viewModelScope.launch {
            if (!immediately) delay(QUOTE_DEBOUNCE_MILLIS)
            val quote = wallet.quotePayment(preview, amount)
            _state.update { it.copy(quote = quote, quoting = false) }
        }
    }

    private companion object {
        const val QUOTE_DEBOUNCE_MILLIS = 300L
    }
}
