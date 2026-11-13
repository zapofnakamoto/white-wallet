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
import whitewallet.lnurlcash.protocol.NoteLink
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
    /**
     * A link from outside without a note in it is being asked what it is (a withdraw or a pay link): only "Opening link" is drawn, then
     * this screen or Send.
     */
    val opening: Boolean = false,
    /** The account's home mint, to say so where a link is received into it. */
    val home: String? = null,
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
        viewModelScope.launch { wallet.homeHost.collect { home -> _state.update { it.copy(home = home) } } }
        // Opened from a link, shared text or NFC tag: check it right away.
        savedState
            .get<String>("input")
            ?.takeIf { it.isNotBlank() }
            ?.let { input ->
                val link = IncomingLink.classify(input) as? IncomingLink.Claim
                onScanned(input, opening = link != null && NoteLink.parse(link.text) == null)
            }
    }

    fun onInputChange(value: String) = _state.update { fresh(it, input = value) }

    /** The screen as it starts, with [input] and what doesn't depend on the note. */
    private fun fresh(from: ClaimState, input: String = "", pay: String? = null) =
        ClaimState(
            input = input,
            showMints = from.showMints,
            pay = pay,
            faucetMints = from.faucetMints,
            loaded = from.loaded,
            home = from.home,
        )

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
    fun onScanned(text: String) = onScanned(text, opening = false)

    private fun onScanned(text: String, opening: Boolean) {
        _state.update { fresh(it, input = text.trim()).copy(opening = opening) }
        check()
    }

    fun check() {
        val input = _state.value.input.trim()
        if (input.isEmpty() || _state.value.busy) return
        (IncomingLink.classify(input) as? IncomingLink.Pay)?.let { link ->
            _state.update { fresh(it, pay = link.text) }
            return
        }
        _state.update { it.copy(busy = true, preview = null, result = null) }
        viewModelScope.launch { show(wallet.previewClaim(input)) }
    }

    /** A pay link goes to Send; anything else is shown here. */
    private fun show(preview: ClaimPreview) {
        if (preview is ClaimPreview.PayLink) return _state.update { fresh(it, pay = preview.text) }
        _state.update { it.copy(busy = false, preview = preview, opening = false) }
    }

    /** Receives the link over Lightning into the mint the preview names. */
    fun melt() {
        val preview = _state.value.preview as? ClaimPreview.Melt ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result =
                try {
                    wallet.melt(preview)
                } catch (e: IOException) {
                    ClaimResult.Failed(RejectionKind.Other, e.message)
                }
            _state.update { it.copy(busy = false, result = result) }
        }
    }

    /** The user agreed to show the link to its own service, to learn what it pays. */
    fun askLink() = updatePreview { wallet.askLink(it) }

    /** Receives into [host] instead (an account without a home mint). */
    fun meltInto(host: String) = updatePreview { wallet.meltInto(it, host) }

    private fun updatePreview(next: suspend (ClaimPreview.Melt) -> ClaimPreview) {
        val preview = _state.value.preview as? ClaimPreview.Melt ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch { show(next(preview)) }
    }

    /** Keeps a note link to receive over Lightning once the account has a mint. */
    fun keepForLightning() {
        val preview = _state.value.preview as? ClaimPreview.Melt ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = wallet.keepForLightning(preview)
            _state.update { it.copy(busy = false, result = result) }
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
