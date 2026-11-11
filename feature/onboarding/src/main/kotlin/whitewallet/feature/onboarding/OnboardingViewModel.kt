package whitewallet.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.wallet.AbandonResult
import whitewallet.core.wallet.AddressFeeChoice
import whitewallet.core.wallet.AddressStatus
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.MintKeyChangedException
import whitewallet.core.wallet.OnboardResult
import whitewallet.core.wallet.PayPreview
import whitewallet.core.wallet.PayResult
import whitewallet.core.wallet.UsernameSuggestions
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.protocol.ProtocolViolation

/** Live result of checking the typed name on the mint. */
sealed interface Availability {
    data object Idle : Availability

    data object Checking : Availability

    data object Available : Availability

    data object Yours : Availability

    data object Taken : Availability

    data class Invalid(val reason: String) : Availability

    data object Unknown : Availability
}

data class Claimed(val address: String, val confirmed: Boolean)

/** A name the mint holds until its fee is paid. [canPayFromBalance]: the account's notes cover the invoice. */
data class HeldAddress(val address: RegisteredAddress, val amountMsat: Long, val canPayFromBalance: Boolean)

/** What the address form is for. */
enum class OnboardingMode {
    /**
     * The address step of a wallet's setup, after its mint: the first account's address there. One it owns already (seed-derived) comes
     * back by itself; the user may type one they had, pick a new one, or go on without.
     */
    Setup,

    /** Picks a different primary address for the active account, on its current mint. The old one stays registered. */
    ChangeAddress,

    /** Registers another (non-primary) address for the active account, on its current mint. */
    AddAddress,

    /**
     * Registers an address for the active account on another mint: same recovery phrase, that mint's own keys. Never primary; payments to
     * it arrive as notes there.
     */
    AddAddressElsewhere,
}

/** An entry of the mint dropdown. */
sealed interface MintOption {
    /** The host it stands for; null for [Custom], whose host the user types. */
    val host: String?

    data class Suggested(override val host: String) : MintOption

    /** The local regtest mint (dev/regtest); only offered in developer mode in debug builds. */
    data class LocalDev(override val host: String) : MintOption

    data object Custom : MintOption {
        override val host: String? = null
    }
}

data class OnboardingState(
    val username: String,
    /** The mint actually used: the selected option's host, or [customHost] for [MintOption.Custom]. */
    val host: String,
    /** This step lets the user pick the mint; the picker shows when there is a choice (see [showMintPicker]). */
    val canChangeHost: Boolean,
    val mintOptions: List<MintOption> = emptyList(),
    val selectedMint: MintOption = MintOption.Custom,
    val customHost: String = "",
    val mode: OnboardingMode = OnboardingMode.Setup,
    val availability: Availability = Availability.Idle,
    val suggestions: List<String> = emptyList(),
    val busy: Boolean = false,
    val error: OnboardingError? = null,
    val claimed: Claimed? = null,
    /** What [host] charges for an address, shown with the form before anything is asked for; null for none, or while it isn't known. */
    val feeMsat: Long? = null,
    /** The address fee the mint asks, for the user to accept or decline. */
    val feePrompt: Long? = null,
    val held: HeldAddress? = null,
    val done: Boolean = false,
    /** For [OnboardingMode.Setup]: the wallet's phrase is written down (a restore), so no backup comes next. */
    val backedUp: Boolean = false,
    /** Set once the setup was given up: whether another account remains on this device. */
    val abandoned: Boolean? = null,
    /** The user may go on without an address: the setup's address step. */
    val canSkipAddress: Boolean = false,
    /** The mint and the name to start with are known: the form can show. */
    val ready: Boolean = true,
) {
    val inSetup: Boolean
        get() = mode == OnboardingMode.Setup

    val changingAddress: Boolean
        get() = mode == OnboardingMode.ChangeAddress

    val addingAddress: Boolean
        get() = mode == OnboardingMode.AddAddress

    val addingElsewhere: Boolean
        get() = mode == OnboardingMode.AddAddressElsewhere

    /** The mint picker: when this step lets the user pick, and the brand offers more than one mint or lets them type one. */
    val showMintPicker: Boolean
        get() = canChangeHost && mintOptions.hasChoice()

    val canSubmit: Boolean
        get() = !busy && host.isNotBlank() && addressOk

    val canSkip: Boolean
        get() = canSkipAddress && !busy

    /** The fee to say on the form: none for a name that is the wallet's already, which is taken back without registering again. */
    val feeToShow: Long?
        get() = feeMsat.takeIf { availability != Availability.Yours }

    private val addressOk: Boolean
        get() = username.isNotBlank() && availability !is Availability.Taken && availability !is Availability.Invalid
}

sealed interface OnboardingError {
    val detail: String

    /** The mint asks [amountMsat] for the address; at most [maxMsat] is accepted. */
    data class FeeTooHigh(val amountMsat: Long, val maxMsat: Long, override val detail: String = "") : OnboardingError

    /** Paying the fee from the balance failed; the address stays held. */
    data class FeeNotPaid(override val detail: String = "") : OnboardingError

    /** A further account of a wallet can't get an address on a legacy mint. */
    data class MintTooOld(override val detail: String = "") : OnboardingError

    /** The name's hold ran out unpaid. */
    data class HoldExpired(override val detail: String = "") : OnboardingError

    data class Username(override val detail: String) : OnboardingError

    data class Registration(override val detail: String) : OnboardingError

    data class Mint(override val detail: String) : OnboardingError

    data class Network(override val detail: String) : OnboardingError

    data class Limit(val limit: Int) : OnboardingError {
        override val detail: String
            get() = limit.toString()
    }
}

@HiltViewModel
class OnboardingViewModel
@Inject
constructor(
    private val wallet: Wallet,
    private val defaults: AppDefaults,
    private val preferences: AppPreferences,
) : ViewModel() {
    // Nothing is shown until init knows the mint and the name to start with: a form that fills in afterwards flashes other content.
    private val _state = MutableStateFlow(OnboardingState(username = "", host = "", canChangeHost = true, ready = false))
    val state: StateFlow<OnboardingState> = _state
    private var checkJob: Job? = null
    private var initialized = false

    /** Call once from the screen. [presetHost]: the mint of the setup's address step (else the home mint). */
    fun init(mode: OnboardingMode, presetHost: String? = null) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val developerMode = preferences.developerMode.first()
            when (mode) {
                OnboardingMode.Setup -> {
                    val host = presetHost ?: wallet.homeHost.first().orEmpty()
                    val backedUp = wallet.backupVerified.first()
                    // The name derived from the phrase first: a restore finds it again. It is known without asking the mint.
                    val name = wallet.defaultUsername(host) ?: UsernameSuggestions.random()
                    _state.update {
                        it.copy(
                            mode = mode,
                            host = host,
                            canChangeHost = false,
                            canSkipAddress = true,
                            backedUp = backedUp,
                            username = name,
                        )
                    }
                    updateMintOptions(developerMode)
                    // The form stays locked while the mint is asked whether the wallet owns an address there: the answer may move on
                    // by itself, so there is nothing to type into meanwhile.
                    _state.update { it.copy(ready = true, busy = true, availability = Availability.Checking) }
                    loadFee()
                    val owned =
                        try {
                            wallet.recoverableUsername(host)
                        } catch (e: IOException) {
                            null
                        }
                    _state.update { it.copy(busy = false) }
                    // An address the wallet owns there (seed-derived, still registered to it) comes back by itself.
                    if (owned != null) {
                        _state.update { it.copy(username = owned, availability = Availability.Yours) }
                        start()
                        return@launch
                    }
                }
                OnboardingMode.ChangeAddress,
                OnboardingMode.AddAddress -> {
                    val host = wallet.homeHost.first() ?: defaults.defaultMintHost
                    _state.update {
                        it.copy(
                            mode = mode,
                            host = host,
                            canChangeHost = false,
                            canSkipAddress = false,
                            username = UsernameSuggestions.random(),
                        )
                    }
                    updateMintOptions(developerMode)
                }
                OnboardingMode.AddAddressElsewhere -> {
                    homeHost = wallet.homeHost.first()
                    known = wallet.mints.first().map { it.host }.filter(defaults::mintAllowed)
                    val other = known.firstOrNull { it != homeHost }
                    // The same name as the primary address is the natural first try.
                    val name = wallet.primaryAddress.first()?.username.orEmpty()
                    _state.update {
                        it.copy(mode = mode, host = other.orEmpty(), canChangeHost = true, canSkipAddress = false, username = name)
                    }
                    updateMintOptions(developerMode)
                }
            }
            _state.update { it.copy(ready = true) }
            // The dropdown follows developer mode (the local mint appears while it is on).
            launch { preferences.developerMode.collect(::updateMintOptions) }
            if (mode != OnboardingMode.Setup) loadFee()
            scheduleCheck(immediately = true)
        }
    }

    fun onMintSelected(option: MintOption) {
        _state.update { it.copy(selectedMint = option, host = option.host ?: it.customHost, error = null) }
        onHostChanged()
    }

    fun onCustomHostChange(value: String) {
        _state.update { it.copy(customHost = value, host = value, error = null) }
        onHostChanged()
    }

    private fun onHostChanged() {
        loadFee()
        scheduleCheck()
    }

    private var feeJob: Job? = null

    /** Asks the mint what an address costs, so the form says it before "Use this address"; the claim asks again and decides. */
    private fun loadFee() {
        feeJob?.cancel()
        _state.update { it.copy(feeMsat = null) }
        val host = _state.value.host
        if (host.isBlank()) return
        feeJob = viewModelScope.launch {
            val fee =
                try {
                    wallet.addressFeeMsat(host)
                } catch (e: IOException) {
                    null
                } catch (e: ProtocolViolation) {
                    null
                }
            _state.update { if (it.host == host) it.copy(feeMsat = fee) else it }
        }
    }

    /** For [OnboardingMode.AddAddressElsewhere]: the home mint, left out of the choice. */
    private var homeHost: String? = null

    /** For [OnboardingMode.AddAddressElsewhere]: the wallet's mints that the brand allows, the only ones offered. */
    private var known: List<String> = emptyList()

    private fun updateMintOptions(developerMode: Boolean) {
        val before = _state.value.host
        _state.update { s ->
            val options =
                mintOptions(defaults, developerMode, known).filter {
                    if (s.mode == OnboardingMode.AddAddressElsewhere) it.host in known && it.host != homeHost
                    else it.host == null || it.host != homeHost
                }
            val selected = keepSelection(options, s.mintOptions, s.selectedMint, s.host)
            s.copy(
                mintOptions = options,
                selectedMint = selected,
                host = selected.host ?: s.host,
                customHost = if (selected == MintOption.Custom) s.host else s.customHost,
            )
        }
        // A choice no longer offered (the custom mint, a local one) picks another mint: its name and availability follow.
        if (_state.value.host != before) onHostChanged()
    }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value, error = null, suggestions = emptyList()) }
        scheduleCheck()
    }

    fun onSuggestion(name: String) = onUsernameChange(name)

    fun start() {
        val s = _state.value
        if (!s.canSubmit) return
        checkJob?.cancel()
        busy { submit(s) }
    }

    private fun busy(work: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                work()
            } catch (e: IOException) {
                fail(OnboardingError.Network(e.message.orEmpty()))
            } catch (e: MintKeyChangedException) {
                fail(OnboardingError.Mint(e.message.orEmpty()))
            }
        }
    }

    /** Takes the fee the mint asks: the name is held for it. */
    fun acceptFee() {
        val s = _state.value
        _state.update { it.copy(feePrompt = null) }
        busy { claim(s) }
    }

    /** Declines the fee: on without an address where that is allowed, else back to the form. */
    fun declineFee() {
        _state.update { it.copy(feePrompt = null) }
        skipAddress()
    }

    /** Pays the held address's fee from the account's notes. */
    fun payFee() {
        val held = _state.value.held ?: return
        busy {
            if (wallet.payAddressFee(held.address) is PayResult.Paid) checkHeld(held) else fail(OnboardingError.FeeNotPaid())
        }
    }

    /** Whether the name is registered yet, or its hold is over. */
    private suspend fun checkHeld(held: HeldAddress) {
        val now = wallet.checkHeldAddress(held.address)
        when {
            now == null -> _state.update { it.copy(busy = false, held = null, error = OnboardingError.HoldExpired()) }
            now.feeInvoice == null ->
                _state.update {
                    it.copy(busy = false, held = null, claimed = Claimed(now.address, true), availability = Availability.Yours)
                }
            else -> _state.update { it.copy(busy = false) }
        }
    }

    /** The invoice may be paid from another wallet: watches for it while the screen shows it. */
    private fun watchHeld() {
        viewModelScope.launch {
            while (true) {
                delay(HELD_CHECK_MILLIS)
                val held = _state.value.held ?: return@launch
                if (!_state.value.busy) checkHeld(held)
            }
        }
    }

    private suspend fun submit(s: OnboardingState) {
        val fee =
            try {
                wallet.addressFeeMsat(s.host)
            } catch (e: ProtocolViolation) {
                null
            }
        // A name that is the wallet's already (a restore's) is taken back without registering again: there is no fee to ask about.
        val free = fee == null || wallet.checkAddress(s.username, s.host) == AddressStatus.Ours
        // Only the setup follows the brand's choice; from Settings the user always sees the fee first.
        val choice = if (free) AddressFeeChoice.Reserve else if (s.inSetup) defaults.addressFee else AddressFeeChoice.Ask
        when {
            choice == AddressFeeChoice.Reserve -> claim(s)
            choice == AddressFeeChoice.Skip && s.canSkip -> {
                _state.update { it.copy(busy = false) }
                skipAddress()
            }
            else -> _state.update { it.copy(busy = false, feePrompt = fee) }
        }
    }

    private suspend fun claim(s: OnboardingState) {
        val result =
            when (s.mode) {
                OnboardingMode.Setup,
                OnboardingMode.ChangeAddress -> wallet.claimAddress(s.username, s.host)
                OnboardingMode.AddAddress -> wallet.addAddress(s.username, addressLimit())
                OnboardingMode.AddAddressElsewhere -> wallet.addAddress(s.username, addressLimit(), s.host)
            }
        when (result) {
            is OnboardResult.Ready ->
                _state.update {
                    it.copy(busy = false, claimed = Claimed(result.address.address, result.confirmed), availability = Availability.Yours)
                }
            is OnboardResult.AwaitingFee -> {
                val payable = wallet.previewPayment(result.address.feeInvoice.orEmpty()) is PayPreview.Ready
                _state.update { it.copy(busy = false, held = HeldAddress(result.address, result.amountMsat, payable)) }
                watchHeld()
            }
            is OnboardResult.FeeRefused -> fail(OnboardingError.FeeTooHigh(result.amountMsat, result.maxMsat))
            is OnboardResult.UsernameTaken ->
                _state.update {
                    it.copy(busy = false, availability = Availability.Taken, suggestions = result.suggestions)
                }
            is OnboardResult.UsernameInvalid -> fail(OnboardingError.Username(result.reason))
            is OnboardResult.RegistrationFailed -> fail(OnboardingError.Registration(result.reason ?: ""))
            is OnboardResult.MintInvalid -> fail(OnboardingError.Mint(result.reason))
            is OnboardResult.NetworkError -> fail(OnboardingError.Network(result.reason))
            is OnboardResult.LimitReached -> fail(OnboardingError.Limit(result.limit))
            OnboardResult.MintTooOld -> fail(OnboardingError.MintTooOld())
        }
    }

    /** Goes on: the setup is done (the wallet opens next), or back where the address was asked for. */
    fun onContinue() {
        viewModelScope.launch {
            if (_state.value.inSetup) wallet.setSetupStep(null)
            _state.update { it.copy(done = true) }
        }
    }

    /** Goes on without an address: the setup is done; one can be added later in Settings. */
    fun skipAddress() {
        if (!_state.value.canSkip) return
        checkJob?.cancel()
        onContinue()
    }

    /** Gives the setup up (see [Wallet.abandonSetup]); a wallet that must stay opens as it is. */
    fun abandonSetup() {
        val s = _state.value
        if (!s.inSetup || s.busy || s.claimed != null) return
        checkJob?.cancel()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = wallet.abandonSetup()) {
                is AbandonResult.Abandoned -> _state.update { it.copy(busy = false, abandoned = result.remains) }
                AbandonResult.Refused -> {
                    wallet.setSetupStep(null)
                    _state.update { it.copy(busy = false, done = true) }
                }
            }
        }
    }

    /** Addresses per mint: the brand's limit, or the developer setting while developer mode is on. */
    private suspend fun addressLimit(): Int =
        defaults.addressLimit(preferences.developerMode.first(), preferences.maxAddressesPerMint.first())

    private fun fail(error: OnboardingError) = _state.update { it.copy(busy = false, error = error) }

    private fun scheduleCheck(immediately: Boolean = false) {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            if (!immediately) delay(DEBOUNCE_MILLIS)
            val s = _state.value
            if (s.username.isBlank() || s.host.isBlank()) {
                _state.update { it.copy(availability = Availability.Idle, suggestions = emptyList()) }
                return@launch
            }
            _state.update { it.copy(availability = Availability.Checking) }
            val availability =
                when (val status = wallet.checkAddress(s.username, s.host)) {
                    AddressStatus.Available -> Availability.Available
                    AddressStatus.Ours -> Availability.Yours
                    // A name registered without a published owner cannot be shown to be ours, so it is someone else's.
                    AddressStatus.Taken,
                    AddressStatus.Unconfirmed -> Availability.Taken
                    is AddressStatus.Invalid -> Availability.Invalid(status.reason)
                    is AddressStatus.CheckFailed -> Availability.Unknown
                }
            _state.update {
                it.copy(
                    availability = availability,
                    suggestions = if (availability == Availability.Taken) suggestionsFor(s.username) else emptyList(),
                )
            }
        }
    }

    private fun suggestionsFor(name: String): List<String> {
        val base = name.trim().lowercase().filter { it.isLetterOrDigit() || it in "._-" }.take(28).ifEmpty { "user" }
        val random = java.security.SecureRandom()
        return listOf("$base${10 + random.nextInt(90)}", "$base${1000 + random.nextInt(9000)}", UsernameSuggestions.random(random))
            .distinct()
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 500L
        private const val HELD_CHECK_MILLIS = 3_000L
    }
}

/**
 * The mint dropdown: [known] mints first (adding an address elsewhere), then the brand's suggestions, the local regtest mints in developer
 * mode, and "Custom" if the brand lets users type a mint (`mints.custom`). Each mint once.
 */
internal fun mintOptions(defaults: AppDefaults, developerMode: Boolean, known: List<String> = emptyList()): List<MintOption> {
    val hosts = (known + defaults.mintSuggestions).distinct()
    val local = if (developerMode) defaults.localDevMints.filter { it !in hosts }.map(MintOption::LocalDev) else emptyList()
    return hosts.map(MintOption::Suggested) + local + listOfNotNull(MintOption.Custom.takeIf { defaults.customMints })
}

/** Whether the dropdown is worth showing: more than one mint, or only "Custom" (users bring their own). */
internal fun List<MintOption>.hasChoice(): Boolean = size > 1 || singleOrNull() == MintOption.Custom

/** Keeps the current choice if it is still offered; otherwise picks the option for [host], Custom, or the first one. */
internal fun keepSelection(options: List<MintOption>, before: List<MintOption>, selected: MintOption, host: String): MintOption =
    selected.takeIf { it in options && (it != MintOption.Custom || before.isNotEmpty()) }
        ?: options.firstOrNull { it.host == host }
        ?: MintOption.Custom.takeIf { it in options }
        ?: options.firstOrNull()
        ?: MintOption.Custom
