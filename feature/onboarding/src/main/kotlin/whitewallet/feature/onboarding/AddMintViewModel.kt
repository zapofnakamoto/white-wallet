package whitewallet.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.model.SetupStep
import whitewallet.core.wallet.AbandonResult
import whitewallet.core.wallet.AddressPolicy
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.ConnectResult
import whitewallet.core.wallet.MINT_KEY_CHANGED
import whitewallet.core.wallet.MintPreview
import whitewallet.core.wallet.OnboardResult
import whitewallet.core.wallet.ScanProgress
import whitewallet.core.wallet.Wallet

/** The steps of adding a mint: pick it, see what it is, add it (and find what the wallet holds there), see what was found. */
enum class AddMintStep {
    Pick,
    Info,
    Adding,
    Result,
}

/** Why the picked mint can't be added; each maps to a full string resource. */
sealed interface AddMintError {
    data object NotAllowed : AddMintError

    data class Unreachable(val detail: String) : AddMintError

    data class Invalid(val detail: String) : AddMintError
}

/** An account holding money on the added mint, by its name. */
data class FoundAccount(val name: String, val amountMsat: Long)

/** Where the flow goes once it is over. */
sealed interface AddMintFinish {
    /** The wallet's setup goes on with an address on [host]. [backedUp]: its phrase is written down (a restore). */
    data class Address(val host: String, val backedUp: Boolean) : AddMintFinish

    /** Done: the mint is added, or the setup goes on without one. [backedUp] as above; null outside a setup. */
    data class Done(val backedUp: Boolean?) : AddMintFinish

    /** The setup was given up; [remains]: another account is active now. */
    data class Abandoned(val remains: Boolean) : AddMintFinish
}

data class AddMintState(
    val step: AddMintStep = AddMintStep.Pick,
    /** Part of a wallet's setup ([SetupStep.Mint]): back gives the setup up, and the mint may be skipped where the brand allows. */
    val setup: Boolean = false,
    val canSkip: Boolean = false,
    val mintOptions: List<MintOption> = emptyList(),
    val selectedMint: MintOption = MintOption.Custom,
    val customHost: String = "",
    /** The mint actually picked: the selected option's host, or [customHost]. */
    val host: String = "",
    /** The wallet's mints already: picking one again looks through it again. */
    val known: Set<String> = emptySet(),
    val busy: Boolean = false,
    val error: AddMintError? = null,
    val preview: MintPreview.Ready? = null,
    /** How far the scan has looked; null while it hasn't (a wallet made here has nothing on any mint yet, so it isn't scanned). */
    val progress: ScanProgress? = null,
    val found: List<FoundAccount> = emptyList(),
    /** Why adding stopped; null once it went through. */
    val failed: String? = null,
    /** The mint was added all the same and only its scan stopped: it can be scanned again right here, or later. */
    val kept: Boolean = false,
    val keyChanged: Boolean = false,
    /** Asking whether to give the setup up (back on the first step). */
    val confirmAbandon: Boolean = false,
    val finish: AddMintFinish? = null,
) {
    val showMintPicker: Boolean
        get() = mintOptions.hasChoice()

    val canNext: Boolean
        get() = !busy && host.isNotBlank()

    val foundMsat: Long
        get() = found.sumOf { it.amountMsat }
}

@HiltViewModel
class AddMintViewModel
@Inject
constructor(private val wallet: Wallet, private val defaults: AppDefaults, private val preferences: AppPreferences) : ViewModel() {
    private val _state = MutableStateFlow(AddMintState(host = defaults.defaultMintHost))
    val state: StateFlow<AddMintState> = _state
    private var initialized = false

    /** Call once from the screen; [setup]: the wallet's setup step. */
    fun init(setup: Boolean) {
        if (initialized) return
        initialized = true
        _state.update { it.copy(setup = setup, canSkip = setup && defaults.mintOptional) }
        viewModelScope.launch {
            val known = wallet.mints.first().map { it.host }
            _state.update { it.copy(known = known.toSet()) }
            var first = true
            // The dropdown follows developer mode (the local mints appear while it is on).
            preferences.developerMode.collect { developerMode ->
                _state.update { s ->
                    val options = mintOptions(defaults, developerMode, known.filter(defaults::mintAllowed))
                    val selected = keepSelection(options, s.mintOptions, s.selectedMint, s.host)
                    s.copy(
                        mintOptions = options,
                        selectedMint = selected,
                        host = selected.host ?: s.host,
                        customHost = if (selected == MintOption.Custom) s.host else s.customHost,
                    )
                }
                // A brand with a single mint has nothing to pick: straight to what it is.
                if (first && !_state.value.showMintPicker && _state.value.host.isNotBlank()) next()
                first = false
            }
        }
    }

    fun onMintSelected(option: MintOption) = _state.update {
        it.copy(selectedMint = option, host = option.host ?: it.customHost, error = null)
    }

    fun onCustomHostChange(value: String) = _state.update { it.copy(customHost = value, host = value, error = null) }

    /** Asks the picked mint what it is; nothing is saved yet. */
    fun next() {
        val s = _state.value
        if (!s.canNext) return
        if (!defaults.mintAllowed(s.host)) return _state.update { it.copy(error = AddMintError.NotAllowed) }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val preview = wallet.previewMint(s.host)) {
                is MintPreview.Ready -> _state.update { it.copy(busy = false, step = AddMintStep.Info, preview = preview) }
                is MintPreview.Unreachable -> _state.update { it.copy(busy = false, error = AddMintError.Unreachable(preview.reason)) }
                is MintPreview.Invalid -> _state.update { it.copy(busy = false, error = AddMintError.Invalid(preview.reason)) }
            }
        }
    }

    /** Adds the mint shown, with the key it showed, and finds what the wallet holds there. */
    fun add() {
        val preview = _state.value.preview ?: return
        _state.update { it.copy(step = AddMintStep.Adding, progress = null, failed = null, kept = false, keyChanged = false) }
        viewModelScope.launch {
            val result = wallet.addMint(preview.host, preview.signingKey) { progress -> _state.update { it.copy(progress = progress) } }
            val found = wallet.heldAt(preview.host).map { (account, msat) -> FoundAccount(account.name, msat) }
            val keyChanged = ((result as? ConnectResult.Failed)?.result as? OnboardResult.MintInvalid)?.reason == MINT_KEY_CHANGED
            val kept = !keyChanged && wallet.unscannedMints.first().any { it.host == preview.host }
            _state.update {
                when (result) {
                    is ConnectResult.Connected -> it.copy(step = AddMintStep.Result, found = found)
                    is ConnectResult.Failed ->
                        it.copy(
                            step = AddMintStep.Result,
                            found = found,
                            failed = failure(result.result),
                            kept = kept,
                            keyChanged = keyChanged,
                        )
                }
            }
        }
    }

    private fun failure(result: OnboardResult): String =
        when (result) {
            is OnboardResult.MintInvalid -> result.reason
            is OnboardResult.NetworkError -> result.reason
            else -> result.toString()
        }

    /**
     * After the result (a mint that wasn't added: back to the pick): in a setup, on to the address (where there is one to pick) or done;
     * otherwise back where it was opened.
     */
    fun done() {
        val s = _state.value
        if (s.failed != null && !s.kept)
            return _state.update { it.copy(step = AddMintStep.Pick, preview = null, progress = null, found = emptyList()) }
        viewModelScope.launch {
            if (!s.setup) return@launch _state.update { it.copy(finish = AddMintFinish.Done(null)) }
            val host = s.preview?.host ?: return@launch
            val backedUp = wallet.backupVerified.first()
            val first = wallet.activeAccount.first()?.account?.index == 0L
            val asks = defaults.address != AddressPolicy.None && first && wallet.addresses.first().none { it.address.host == host }
            wallet.setSetupStep(if (asks) SetupStep.Address else null)
            _state.update { it.copy(finish = if (asks) AddMintFinish.Address(host, backedUp) else AddMintFinish.Done(backedUp)) }
        }
    }

    /** In a setup where the brand allows it: on without a mint; the main screen asks for one. */
    fun skip() {
        if (!_state.value.canSkip || _state.value.busy) return
        viewModelScope.launch {
            val backedUp = wallet.backupVerified.first()
            wallet.setSetupStep(null)
            _state.update { it.copy(finish = AddMintFinish.Done(backedUp)) }
        }
    }

    /** Back: from what the mint is to the pick; on the pick in a setup, asks whether to give the setup up. */
    fun back() {
        val s = _state.value
        when {
            s.busy || s.step == AddMintStep.Adding -> Unit
            s.step == AddMintStep.Info -> _state.update { it.copy(step = AddMintStep.Pick, preview = null) }
            s.step == AddMintStep.Result -> done()
            s.setup -> _state.update { it.copy(confirmAbandon = true) }
            else -> _state.update { it.copy(finish = AddMintFinish.Done(null)) }
        }
    }

    fun keepSetup() = _state.update { it.copy(confirmAbandon = false) }

    /** Gives the setup up (see [Wallet.abandonSetup]); a wallet that must stay opens as it is. */
    fun abandon() {
        _state.update { it.copy(confirmAbandon = false, busy = true) }
        viewModelScope.launch {
            val finish =
                when (val result = wallet.abandonSetup()) {
                    is AbandonResult.Abandoned -> AddMintFinish.Abandoned(result.remains)
                    AbandonResult.Refused -> {
                        wallet.setSetupStep(null)
                        AddMintFinish.Done(backedUp = false)
                    }
                }
            _state.update { it.copy(busy = false, finish = finish) }
        }
    }
}
