package whitewallet.feature.claim

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.wallet.ClaimPreview
import whitewallet.core.wallet.ClaimResult
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.Wallet
import java.io.IOException
import whitewallet.core.wallet.MintKeyChangedException
import whitewallet.lnurlcash.protocol.RejectionKind

data class ClaimState(
    val input: String = "",
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val preview: ClaimPreview? = null,
    val result: ClaimResult? = null,
    /** Say which mint the note is on and whether it's trusted (brand `showMints`). */
    val showMints: Boolean = true,
)

@HiltViewModel
class ClaimViewModel @Inject constructor(private val wallet: Wallet, savedState: SavedStateHandle, defaults: AppDefaults) : ViewModel() {
    private val _state = MutableStateFlow(ClaimState(showMints = defaults.showMints))
    val state: StateFlow<ClaimState> = _state

    init {
        // Opened from a link, shared text or NFC tag: check it right away.
        savedState.get<String>("input")?.takeIf { it.isNotBlank() }?.let(::onScanned)
    }

    fun onInputChange(value: String) = _state.update { ClaimState(input = value, showMints = it.showMints) }

    fun startScan() = _state.update { it.copy(scanning = true) }
    fun stopScan() = _state.update { it.copy(scanning = false) }

    /** A scanned or pasted note is checked right away. */
    fun onScanned(text: String) {
        _state.update { ClaimState(input = text.trim(), showMints = it.showMints) }
        check()
    }

    fun check() {
        val input = _state.value.input.trim()
        if (input.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, preview = null) }
        viewModelScope.launch {
            val preview = wallet.previewClaim(input)
            _state.update { it.copy(busy = false, preview = preview) }
        }
    }

    fun claim() {
        val preview = _state.value.preview as? ClaimPreview.Ready ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
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
