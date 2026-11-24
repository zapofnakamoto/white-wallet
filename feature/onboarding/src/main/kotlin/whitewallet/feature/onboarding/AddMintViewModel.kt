package whitewallet.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
import whitewallet.core.wallet.MintPreview
import whitewallet.core.wallet.MintRefusal
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

    /** No answer from it: the address may be wrong, or the mint down. (Offline, the screen says that instead.) */
    data object Unreachable : AddMintError

    /** It can't be used ([refusal]): not a host, not a mint the wallet can read, or one that refused ([mintReason]: its words, if any). */
    data class Invalid(val refusal: MintRefusal, val mintReason: String? = null) : AddMintError

    /** It answers, but showed no signing key; [asked] false: it couldn't be asked for it. Its page says more, and asks again. */
    data class WithoutKey(val host: String, val asked: Boolean) : AddMintError
}

/** What the wallet learned about a mint of the list when the user picked it or went on with it: its row says so until it answers well. */
enum class MintProblem {
    NotAnswering,
    NoSigningKey,
}

/** An account holding money on the added mint, by its name. */
data class FoundAccount(val name: String, val amountMsat: Long)

/** Where the flow goes once it is over. */
sealed interface AddMintFinish {
    /** The wallet's setup goes on with an address on [host]. */
    data class Address(val host: String) : AddMintFinish

    /** Done: the mint is added, or the setup ends here (without an address step, or without a mint). */
    data object Done : AddMintFinish

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
    /** The mint added becomes the active account's home: in a setup, or while it has none. Its mark is in the home mint's colours. */
    val becomesHome: Boolean = false,
    /** The mints of the list a problem is known of, by host. None is asked for its row: only one the user acted on. */
    val problems: Map<String, MintProblem> = emptyMap(),
    val busy: Boolean = false,
    val error: AddMintError? = null,
    val preview: MintPreview.Ready? = null,
    /** How far the scan has looked; null while it hasn't (a wallet made here has nothing on any mint yet, so it isn't scanned). */
    val progress: ScanProgress? = null,
    val found: List<FoundAccount> = emptyList(),
    /** Why adding stopped; null once it went through. */
    val failed: OnboardResult? = null,
    /** The mint was added all the same and only its scan stopped: it can be scanned again right here, or later. */
    val kept: Boolean = false,
    val keyChanged: Boolean = false,
    /** It publishes no signing key: the wallet uses no such mint. */
    val withoutSigningKey: Boolean = false,
    /** The user accepted the mint's new signing key here: the result says so, and nothing was scanned. */
    val keyAccepted: Boolean = false,
    /** Asking whether to give the setup up (back on the first step). */
    val confirmAbandon: Boolean = false,
    val finish: AddMintFinish? = null,
    /** The mints to pick from are known: until then the screen draws nothing, not one mint first and a list of them after. */
    val loaded: Boolean = true,
    /** The phone is offline: no mint can be asked what it is, and the screen says so before the user tries. */
    val offline: Boolean = false,
) {
    val showMintPicker: Boolean
        get() = mintOptions.hasChoice()

    /** Next is offered. At work ([busy]) it still is: the button locks itself then and keeps its look. */
    val canNext: Boolean
        get() = !offline && host.isNotBlank()

    val foundMsat: Long
        get() = found.sumOf { it.amountMsat }
}

/**
 * The wallet has this mint, and it now presents another signing key (or none) than the pinned one: the user decides about it first. A mint
 * that couldn't be asked for its key was compared with nothing.
 */
val MintPreview.Ready.keyDiffers: Boolean
    get() = knownKey != null && keyAnswered && signingKey != knownKey

@HiltViewModel
class AddMintViewModel
@Inject
constructor(private val wallet: Wallet, private val defaults: AppDefaults, private val preferences: AppPreferences) : ViewModel() {
    private val _state = MutableStateFlow(AddMintState(loaded = false))
    val state: StateFlow<AddMintState> = _state
    private var initialized = false

    /** The mint the user picked from the list, and what it answered when asked at once: going on uses that answer. */
    private var picked: Pair<String, Deferred<MintPreview>>? = null

    /** The mint the user picked from the list; none for the one preselected, which is only asked once the user goes on. */
    private var pickedHost: String? = null

    /** Call once from the screen; [setup]: the wallet's setup step. */
    fun init(setup: Boolean) {
        if (initialized) return
        initialized = true
        _state.update { it.copy(setup = setup, canSkip = setup && defaults.mintOptional) }
        viewModelScope.launch { followConnection() }
        viewModelScope.launch {
            val known = wallet.mints.first().map { it.host }
            val noHome = wallet.homeHost.first() == null
            _state.update { it.copy(known = known.toSet(), becomesHome = setup || noHome) }
            var first = true
            // The dropdown follows developer mode (the local mints appear while it is on).
            preferences.developerMode.collect { developerMode ->
                _state.update { s ->
                    val options = mintOptions(defaults, developerMode, known.filter(defaults::mintAllowed))
                    val selected =
                        if (first) firstNewMint(options, known.toSet()) else keepSelection(options, s.mintOptions, s.selectedMint, s.host)
                    s.copy(
                        mintOptions = options,
                        selectedMint = selected,
                        host = selected.host ?: s.host,
                        customHost = if (selected == MintOption.Custom) s.host else s.customHost,
                        loaded = true,
                    )
                }
                // A brand with a single mint has nothing to pick: straight to what it is.
                if (first && !_state.value.showMintPicker && _state.value.host.isNotBlank()) next()
                first = false
            }
        }
    }

    /**
     * Says at once when the phone goes offline; no answer from a mint then is no news. Back online, where there is nothing to pick (a brand
     * with a single mint), the screen goes on to what it is, as it would have at the start.
     */
    private suspend fun followConnection() = wallet.followOffline { offline, back ->
        _state.update { it.copy(offline = offline, error = it.error.takeUnless { offline && it == AddMintError.Unreachable }) }
        val s = _state.value
        if (back && s.loaded && s.step == AddMintStep.Pick && !s.showMintPicker && s.host.isNotBlank()) next()
        // A mint picked while offline couldn't be asked then.
        if (back && s.step == AddMintStep.Pick && !s.busy && picked == null && s.host == pickedHost) askPicked(s.host)
    }

    /** Picking a mint from the list is acting on it: it is asked at once what it is, so its problem shows before going on. */
    fun onMintSelected(option: MintOption) {
        _state.update { it.copy(selectedMint = option, host = option.host ?: it.customHost, error = null) }
        pickedHost = option.host
        askPicked(option.host)
    }

    /** An address being typed is asked only once the user goes on. */
    fun onCustomHostChange(value: String) {
        askPicked(null)
        pickedHost = null
        _state.update { it.copy(customHost = value, host = value, error = null) }
    }

    /**
     * Asks [host], just picked, what it is, in place of an earlier pick's question; nothing is saved, and the page doesn't wait for it. Its
     * problem shows if it is still the mint picked when the answer comes; one that is fine shows nothing.
     */
    private fun askPicked(host: String?) {
        picked?.second?.cancel()
        picked = null
        if (host == null || _state.value.offline || !defaults.mintAllowed(host)) return
        val answer = viewModelScope.async { wallet.previewMint(host) }
        picked = host to answer
        viewModelScope.launch {
            val preview = answer.await()
            learned(host, preview)
            _state.update {
                if (it.step == AddMintStep.Pick && !it.busy && it.host == host) it.copy(error = problemOf(host, preview)) else it
            }
        }
    }

    /** Why [host], which answered with [preview], can't be added as it is, as the pick says it; null if it can. */
    private fun problemOf(host: String, preview: MintPreview): AddMintError? =
        when (preview) {
            is MintPreview.Unreachable -> AddMintError.Unreachable
            is MintPreview.Invalid -> AddMintError.Invalid(preview.refusal, preview.mintReason)
            is MintPreview.Ready ->
                if (preview.signingKey == null && !preview.keyDiffers) AddMintError.WithoutKey(host, preview.keyAnswered) else null
        }

    /** Keeps for the list what [host] answered: its problem, or that it has none any more. */
    private fun learned(host: String, preview: MintPreview) {
        val problem =
            when (preview) {
                is MintPreview.Unreachable,
                is MintPreview.Invalid -> MintProblem.NotAnswering
                is MintPreview.Ready ->
                    when {
                        preview.signingKey != null || preview.keyDiffers -> null
                        preview.keyAnswered -> MintProblem.NoSigningKey
                        else -> MintProblem.NotAnswering
                    }
            }
        _state.update { it.copy(problems = if (problem == null) it.problems - host else it.problems + (host to problem)) }
    }

    /** Asks the picked mint what it is; nothing is saved yet. */
    fun next() {
        val s = _state.value
        if (!s.canNext || s.busy) return
        if (!defaults.mintAllowed(s.host)) return _state.update { it.copy(error = AddMintError.NotAllowed) }
        _state.update { it.copy(busy = true, error = null) }
        val answer = picked?.takeIf { it.first == s.host }?.second
        picked = null
        viewModelScope.launch {
            // The answer the pick got, awaited if it is still on its way; a mint that didn't answer then is asked again.
            val known = answer?.await() as? MintPreview.Ready
            show(known ?: wallet.previewMint(s.host))
        }
    }

    /** What [host] is now: its info, or back to the pick saying why not. */
    private suspend fun preview(host: String) = show(wallet.previewMint(host))

    /** Goes on with what the mint picked answered. */
    private fun show(preview: MintPreview) {
        learned(_state.value.host, preview)
        when (preview) {
            is MintPreview.Ready -> _state.update { it.copy(busy = false, step = AddMintStep.Info, preview = preview) }
            is MintPreview.Unreachable ->
                _state.update { it.copy(busy = false, step = AddMintStep.Pick, preview = null, error = AddMintError.Unreachable) }
            is MintPreview.Invalid ->
                _state.update {
                    it.copy(
                        busy = false,
                        step = AddMintStep.Pick,
                        preview = null,
                        error = AddMintError.Invalid(preview.refusal, preview.mintReason),
                    )
                }
        }
    }

    /**
     * Uses the mint shown with the new [key] it presents, as its page does ([Wallet.acceptMintKey]); nothing is scanned, the key was all
     * there was to decide. If it presents yet another key by now, the info shows that one.
     */
    fun acceptKey(key: String) {
        val preview = _state.value.preview ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            if (wallet.acceptMintKey(preview.host, key))
                _state.update { it.copy(busy = false, step = AddMintStep.Result, failed = null, keyAccepted = true) }
            else preview(preview.host)
        }
    }

    /** Asks the mint shown again what it is: its signing key may be there by now. */
    fun checkAgain() {
        val preview = _state.value.preview ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch { preview(preview.host) }
    }

    /** After a failure: a mint whose key changed meanwhile is shown again as it is now; anything else is tried again. */
    fun tryAgain() {
        val s = _state.value
        if (s.keyChanged) {
            _state.update { it.copy(busy = true, keyChanged = false, failed = null) }
            viewModelScope.launch { preview(s.preview?.host ?: s.host) }
        } else add()
    }

    /** Adds the mint shown, with the key it showed, and finds what the wallet holds there. */
    fun add() {
        val preview = _state.value.preview ?: return
        _state.update {
            it.copy(
                step = AddMintStep.Adding,
                progress = null,
                failed = null,
                kept = false,
                keyChanged = false,
                withoutSigningKey = false,
                keyAccepted = false,
            )
        }
        viewModelScope.launch {
            val result = wallet.addMint(preview.host, preview.signingKey) { progress -> _state.update { it.copy(progress = progress) } }
            val found = wallet.heldAt(preview.host).map { (account, msat) -> FoundAccount(account.name, msat) }
            val refusal = ((result as? ConnectResult.Failed)?.result as? OnboardResult.MintInvalid)?.refusal
            val keyChanged = refusal == MintRefusal.KeyChanged
            val kept = !keyChanged && wallet.unscannedMints.first().any { it.host == preview.host }
            _state.update {
                when (result) {
                    is ConnectResult.Connected -> it.copy(step = AddMintStep.Result, found = found)
                    is ConnectResult.Failed ->
                        it.copy(
                            step = AddMintStep.Result,
                            found = found,
                            failed = result.result,
                            kept = kept,
                            keyChanged = keyChanged,
                            withoutSigningKey = refusal == MintRefusal.WithoutSigningKey,
                        )
                }
            }
        }
    }

    /**
     * After the result (a mint that wasn't added: back to the pick): in a setup, on to the address (where there is one to pick) or done;
     * otherwise back where it was opened.
     */
    fun done() {
        val s = _state.value
        if (s.failed != null && !s.kept)
            return _state.update {
                it.copy(step = AddMintStep.Pick, preview = null, progress = null, found = emptyList(), failed = null, keyChanged = false)
            }
        viewModelScope.launch {
            if (!s.setup) return@launch _state.update { it.copy(finish = AddMintFinish.Done) }
            val host = s.preview?.host ?: return@launch
            val first = wallet.activeAccount.first()?.account?.index == 0L
            val asks = defaults.address != AddressPolicy.None && first && wallet.addresses.first().none { it.address.host == host }
            wallet.setSetupStep(if (asks) SetupStep.Address else null)
            _state.update { it.copy(finish = if (asks) AddMintFinish.Address(host) else AddMintFinish.Done) }
        }
    }

    /** Leaves a failed add outside a setup, where the user came from; in a setup, back to the pick (which can give the setup up). */
    fun cancel() {
        if (_state.value.setup) done() else _state.update { it.copy(finish = AddMintFinish.Done) }
    }

    /** In a setup where the brand allows it: on without a mint; the main screen asks for one. */
    fun skip() {
        if (!_state.value.canSkip || _state.value.busy) return
        viewModelScope.launch {
            wallet.setSetupStep(null)
            _state.update { it.copy(finish = AddMintFinish.Done) }
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
            else -> _state.update { it.copy(finish = AddMintFinish.Done) }
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
                        AddMintFinish.Done
                    }
                }
            _state.update { it.copy(busy = false, finish = finish) }
        }
    }
}
