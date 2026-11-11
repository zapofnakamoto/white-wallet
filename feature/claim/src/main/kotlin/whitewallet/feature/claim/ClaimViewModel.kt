package whitewallet.feature.claim

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.ClaimPreview
import whitewallet.core.wallet.ClaimResult
import whitewallet.core.wallet.FaucetState
import whitewallet.core.wallet.IncomingLink
import whitewallet.core.wallet.MintKeyChangedException
import whitewallet.core.wallet.ReclaimResult
import whitewallet.core.wallet.RegtestFaucet
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.protocol.RejectionKind

data class ClaimState(
    val input: String = "",
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val preview: ClaimPreview? = null,
    val result: ClaimResult? = null,
    /** The outcome of taking back the user's own note ([ClaimPreview.OwnShared]). */
    val takenBack: ReclaimResult? = null,
    /** Say which mint the note is on and whether it's trusted (brand `mints.visible`). */
    val showMints: Boolean = true,
    /** An invoice, address or pay link was scanned or pasted here: Send takes it over, so one scanner does for both. */
    val pay: String? = null,
    /** Developer: the local regtest mints the faucet makes a note on, and its last attempt. Empty outside developer mode. */
    val faucetMints: List<String> = emptyList(),
    val faucet: FaucetState = FaucetState(),
    /** [faucetMints] are known: until then nothing is drawn, so their buttons don't come in late. */
    val loaded: Boolean = true,
)

/** What the regtest faucet's note is made for; the mint's fee comes off. */
internal const val FAUCET_SATS = 1_000L

@HiltViewModel
class ClaimViewModel
@Inject
constructor(private val wallet: Wallet, private val faucet: RegtestFaucet, savedState: SavedStateHandle, defaults: AppDefaults) :
    ViewModel() {
    private val _state = MutableStateFlow(ClaimState(showMints = defaults.showMints, loaded = false))
    val state: StateFlow<ClaimState> = _state

    init {
        viewModelScope.launch { faucet.noteMints.collect { mints -> _state.update { it.copy(faucetMints = mints, loaded = true) } } }
        // Opened from a link, shared text or NFC tag: check it right away.
        savedState.get<String>("input")?.takeIf { it.isNotBlank() }?.let(::onScanned)
    }

    fun onInputChange(value: String) = _state.update { fresh(it, input = value) }

    /** The screen as it starts, with [input] and what doesn't depend on the note. */
    private fun fresh(from: ClaimState, input: String = "", pay: String? = null) =
        ClaimState(input = input, showMints = from.showMints, pay = pay, faucetMints = from.faucetMints, loaded = from.loaded)

    /** Developer: has the regtest faucet make a note on the local mint [mint], and checks it as if it was pasted. */
    fun noteFromFaucet(mint: String) {
        if (_state.value.busy || _state.value.faucet.busy) return
        _state.update { it.copy(faucet = FaucetState(busy = true)) }
        viewModelScope.launch {
            val link = runCatching { faucet.note(FAUCET_SATS, mint) }
            link.onSuccess(::onScanned).onFailure { e ->
                _state.update { it.copy(faucet = FaucetState(error = e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun startScan() = _state.update { it.copy(scanning = true) }

    fun stopScan() = _state.update { it.copy(scanning = false) }

    /** A scanned or pasted note is checked right away. */
    fun onScanned(text: String) {
        _state.update { fresh(it, input = text.trim()) }
        check()
    }

    fun check() {
        val input = _state.value.input.trim()
        if (input.isEmpty() || _state.value.busy) return
        (IncomingLink.classify(input) as? IncomingLink.Pay)?.let { link ->
            _state.update { fresh(it, pay = link.text) }
            return
        }
        _state.update { it.copy(busy = true, preview = null) }
        viewModelScope.launch {
            val preview = wallet.previewClaim(input)
            _state.update { it.copy(busy = false, preview = preview) }
        }
    }

    /** Send has the payment now. */
    fun payOpened() = _state.update { it.copy(pay = null) }

    /** Keeps a note that can't be claimed now; the wallet claims it once it can. */
    fun keep() {
        val preview = _state.value.preview as? ClaimPreview.Keepable ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = wallet.keepClaim(preview)
            _state.update { it.copy(busy = false, result = result) }
        }
    }

    /** Takes back the user's own note, as its note screen does. */
    fun takeBack() {
        val preview = _state.value.preview as? ClaimPreview.OwnShared ?: return
        if (_state.value.busy || !preview.reachable) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = wallet.reclaimNote(preview.id)
            _state.update { it.copy(busy = false, takenBack = result) }
        }
    }

    fun claim() {
        val preview = _state.value.preview as? ClaimPreview.Ready ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result =
                try {
                    wallet.claim(preview)
                } catch (e: IOException) {
                    ClaimResult.Failed(RejectionKind.Other, e.message)
                } catch (e: MintKeyChangedException) {
                    ClaimResult.Failed(RejectionKind.Other, e.message)
                }
            _state.update { it.copy(busy = false, result = result) }
        }
    }
}
