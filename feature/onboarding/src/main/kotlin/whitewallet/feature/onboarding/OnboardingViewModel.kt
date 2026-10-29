package whitewallet.feature.onboarding

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
import whitewallet.core.wallet.AddressPolicy
import whitewallet.core.wallet.AddressStatus
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.CreateAccountResult
import whitewallet.core.wallet.OnboardResult
import whitewallet.core.wallet.UsernameSuggestions
import whitewallet.core.wallet.Wallet
import java.io.IOException
import whitewallet.core.wallet.MintKeyChangedException

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

/** What the address form is for. */
enum class OnboardingMode {
    /** First launch: creates the first account. */
    FirstRun,

    /** Adds another account with its own recovery phrase. */
    NewAccount,

    /** Picks a different primary address for the active account, on its current mint. The old one stays registered. */
    ChangeAddress,

    /** Registers another (non-primary) address for the active account, on its current mint. */
    AddAddress,

    /**
     * Registers an address for the active account on another mint: same recovery phrase, that mint's own keys.
     * Never primary; payments to it arrive as notes there.
     */
    AddAddressElsewhere,

    /**
     * Gives a just-restored account (no address yet) its address on the mint it was restored from: one it already
     * owns (taken back without registering again) or a new one.
     */
    RestoreAddress,
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
    val canChangeHost: Boolean,
    val mintOptions: List<MintOption> = emptyList(),
    val selectedMint: MintOption = MintOption.Custom,
    val customHost: String = "",
    val mode: OnboardingMode = OnboardingMode.FirstRun,
    val accountName: String = "",
    val availability: Availability = Availability.Idle,
    val suggestions: List<String> = emptyList(),
    val busy: Boolean = false,
    val error: OnboardingError? = null,
    val claimed: Claimed? = null,
    val done: Boolean = false,
    /** For [OnboardingMode.RestoreAddress]: what the restore found. */
    val restoredMsat: Long = 0,
    /** Set once a restore was cancelled: whether another account remains on this device. */
    val restoreCancelled: Boolean? = null,
    /** False when a new account starts without a Lightning address (brand `addresses.lightning=none`): only its mint is asked for. */
    val withAddress: Boolean = true,
    /** The user may go on without an address (brand `addresses.lightning=optional`): first run, new account, after a restore. */
    val canSkipAddress: Boolean = false,
) {
    val changingAddress: Boolean get() = mode == OnboardingMode.ChangeAddress
    val addingAddress: Boolean get() = mode == OnboardingMode.AddAddress
    val addingElsewhere: Boolean get() = mode == OnboardingMode.AddAddressElsewhere
    val restoringAddress: Boolean get() = mode == OnboardingMode.RestoreAddress

    /** The address belongs to the active account (not to an account being created). */
    val forExistingAccount: Boolean get() = changingAddress || addingAddress || addingElsewhere || restoringAddress
    val canSubmit: Boolean get() = !busy && (if (withAddress) addressOk else host.isNotBlank()) &&
        (mode != OnboardingMode.NewAccount || accountName.isNotBlank()) && (mode != OnboardingMode.AddAddressElsewhere || host.isNotBlank())
    val canSkip: Boolean get() = canSkipAddress && !busy && host.isNotBlank() && (mode != OnboardingMode.NewAccount || accountName.isNotBlank())
    private val addressOk: Boolean get() = username.isNotBlank() && availability !is Availability.Taken && availability !is Availability.Invalid
}

sealed interface OnboardingError {
    val detail: String
    data class Username(override val detail: String) : OnboardingError
    data class Registration(override val detail: String) : OnboardingError
    data class Mint(override val detail: String) : OnboardingError
    data class Network(override val detail: String) : OnboardingError
    data class Limit(val limit: Int) : OnboardingError {
        override val detail: String get() = limit.toString()
    }

    /** The wallet holds as many accounts as this app allows. */
    data class AccountLimit(val limit: Int) : OnboardingError {
        override val detail: String get() = limit.toString()
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val wallet: Wallet,
    private val defaults: AppDefaults,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(
        OnboardingState(
            UsernameSuggestions.random(), defaults.defaultMintHost, defaults.allowMintChange,
            withAddress = defaults.address != AddressPolicy.None, canSkipAddress = defaults.address == AddressPolicy.Optional,
        ),
    )
    val state: StateFlow<OnboardingState> = _state
    private var checkJob: Job? = null
    private var initialized = false

    private var defaultAccountName = ""

    /**
     * Call once from the screen. [defaultAccountName] names the first account (localized by the caller); a new
     * account starts with [newAccountName]. When changing the address, the mint is fixed to the current one.
     */
    fun init(mode: OnboardingMode, defaultAccountName: String, newAccountName: String, presetHost: String? = null) {
        if (initialized) return
        initialized = true
        this.defaultAccountName = defaultAccountName
        viewModelScope.launch {
            when (mode) {
                OnboardingMode.FirstRun -> suggestDefaultName()
                OnboardingMode.NewAccount -> {
                    _state.update {
                        it.copy(mode = mode, accountName = newAccountName, canChangeHost = defaults.allowMintChange, host = presetHost ?: it.host)
                    }
                    suggestDefaultName()
                }
                OnboardingMode.ChangeAddress, OnboardingMode.AddAddress -> {
                    val host = wallet.homeHost.first() ?: _state.value.host
                    _state.update { it.copy(mode = mode, host = host, canChangeHost = false, withAddress = true, canSkipAddress = false) }
                }
                OnboardingMode.AddAddressElsewhere -> {
                    homeHost = wallet.homeHost.first()
                    val other = mintOptions(defaults, preferences.developerMode.first()).firstNotNullOfOrNull { o -> o.host?.takeIf { it != homeHost } }
                    // The same name as at home is the natural first try.
                    val name = wallet.profile.first()?.username.orEmpty()
                    _state.update { it.copy(mode = mode, host = other.orEmpty(), canChangeHost = true, withAddress = true, canSkipAddress = false, username = name) }
                }
                OnboardingMode.RestoreAddress -> {
                    // The mint the account was restored from (also after an app restart half way through).
                    val host = presetHost ?: wallet.keyHosts().firstOrNull() ?: _state.value.host
                    val restored = wallet.balanceMsat.first()
                    // Empty: the user most likely types the address they had rather than taking a suggestion.
                    _state.update { it.copy(mode = mode, host = host, canChangeHost = false, username = "", restoredMsat = restored, withAddress = true) }
                    // The seed-derived address, if it is still registered to this wallet, comes back by itself.
                    wallet.recoverableUsername(host)?.let { name ->
                        _state.update { it.copy(username = name, availability = Availability.Yours) }
                        start()
                        return@launch
                    }
                }
            }
            // The dropdown follows developer mode (the local mint appears while it is on).
            launch { preferences.developerMode.collect(::updateMintOptions) }
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

    /** The derived name depends on the mint; it follows the host unless the user typed their own. */
    private fun onHostChanged() {
        viewModelScope.launch {
            suggestDefaultName()
            scheduleCheck()
        }
    }

    /** The name suggested first for a new account (see [Wallet.defaultUsername]), unless the user typed another. */
    private var defaultName: String? = null

    private suspend fun suggestDefaultName() {
        val s = _state.value
        if (s.mode != OnboardingMode.FirstRun && s.mode != OnboardingMode.NewAccount || !s.withAddress) return
        if (defaultName != null && s.username != defaultName) return
        val name = wallet.defaultUsername(s.host) ?: return
        defaultName = name
        _state.update { it.copy(username = name) }
    }

    /** For [OnboardingMode.AddAddressElsewhere]: the home mint, left out of the choice. */
    private var homeHost: String? = null

    private fun updateMintOptions(developerMode: Boolean) = _state.update { s ->
        val options = mintOptions(defaults, developerMode).filter { it.host == null || it.host != homeHost }
        val selected = keepSelection(options, s.mintOptions, s.selectedMint, s.host)
        s.copy(mintOptions = options, selectedMint = selected, customHost = if (selected == MintOption.Custom) s.host else s.customHost)
    }

    fun onAccountNameChange(value: String) = _state.update { it.copy(accountName = value) }

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

    private suspend fun submit(s: OnboardingState) {
        if (!s.withAddress) return createAccount(s)
        val result = when (s.mode) {
            OnboardingMode.FirstRun -> wallet.onboard(s.username, s.host, defaultAccountName)
            OnboardingMode.NewAccount -> wallet.onboard(s.username, s.host, s.accountName.trim())
            OnboardingMode.ChangeAddress -> wallet.onboard(s.username, s.host, null)
            OnboardingMode.AddAddress -> wallet.addAddress(s.username, addressLimit())
            OnboardingMode.AddAddressElsewhere -> wallet.addAddress(s.username, addressLimit(), s.host)
            OnboardingMode.RestoreAddress -> wallet.onboard(s.username, s.host, null)
        }
        when (result) {
            is OnboardResult.Ready -> _state.update {
                it.copy(busy = false, claimed = Claimed(result.profile.address, result.confirmed), availability = Availability.Yours)
            }
            is OnboardResult.UsernameTaken -> _state.update {
                it.copy(busy = false, availability = Availability.Taken, suggestions = result.suggestions)
            }
            is OnboardResult.UsernameInvalid -> fail(OnboardingError.Username(result.reason))
            is OnboardResult.RegistrationFailed -> fail(OnboardingError.Registration(result.reason ?: ""))
            is OnboardResult.MintInvalid -> fail(OnboardingError.Mint(result.reason))
            is OnboardResult.NetworkError -> fail(OnboardingError.Network(result.reason))
            is OnboardResult.LimitReached -> fail(OnboardingError.Limit(result.limit))
            is OnboardResult.AccountLimitReached -> fail(OnboardingError.AccountLimit(result.limit))
        }
    }

    /** A new account without an address: nothing to show afterwards, it opens right away. */
    private suspend fun createAccount(s: OnboardingState) {
        val name = if (s.mode == OnboardingMode.NewAccount) s.accountName.trim() else defaultAccountName
        when (val result = wallet.createAccount(s.host, name)) {
            is CreateAccountResult.Created -> _state.update { it.copy(busy = false, done = true) }
            is CreateAccountResult.AccountLimitReached -> fail(OnboardingError.AccountLimit(result.limit))
            is CreateAccountResult.MintInvalid -> fail(OnboardingError.Mint(result.reason))
            is CreateAccountResult.NetworkError -> fail(OnboardingError.Network(result.reason))
        }
    }

    fun onContinue() = _state.update { it.copy(done = true) }

    /**
     * Goes on without an address: a new account is set up with just its mint; a restored one already exists and opens
     * as it is. Either can get an address later in Settings.
     */
    fun skipAddress() {
        val s = _state.value
        if (!s.canSkip) return
        checkJob?.cancel()
        if (s.restoringAddress) onContinue() else busy { createAccount(s) }
    }

    /** Removes the just-restored account again, before it has an address (see [Wallet.abortRestore]). */
    fun cancelRestore() {
        if (_state.value.busy || _state.value.claimed != null) return
        checkJob?.cancel()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val remains = wallet.abortRestore()
            _state.update { it.copy(busy = false, restoreCancelled = remains) }
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
            if (!s.withAddress) return@launch
            if (s.username.isBlank()) {
                _state.update { it.copy(availability = Availability.Idle, suggestions = emptyList()) }
                return@launch
            }
            _state.update { it.copy(availability = Availability.Checking) }
            val forNewAccount = !s.forExistingAccount
            val availability = when (val status = wallet.checkAddress(s.username, s.host, forNewAccount)) {
                AddressStatus.Available -> Availability.Available
                AddressStatus.Ours -> Availability.Yours
                // A name registered without a published owner cannot be shown to be ours, so it is someone else's.
                AddressStatus.Taken, AddressStatus.Unconfirmed -> Availability.Taken
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
        return listOf("$base${10 + random.nextInt(90)}", "$base${1000 + random.nextInt(9000)}", UsernameSuggestions.random(random)).distinct()
    }

    /** A new account's recovery phrase, made ahead for the suggested name, isn't kept once this screen is gone. */
    override fun onCleared() {
        val mode = _state.value.mode
        if (mode == OnboardingMode.FirstRun || mode == OnboardingMode.NewAccount) wallet.forgetPreparedPhrase()
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 500L
    }
}

/** The mint dropdown: the brand's suggestions, the local regtest mints in developer mode, and "Custom". */
internal fun mintOptions(defaults: AppDefaults, developerMode: Boolean): List<MintOption> =
    defaults.mintSuggestions.map(MintOption::Suggested) +
        (if (developerMode) defaults.localDevMints.map(MintOption::LocalDev) else emptyList()) +
        MintOption.Custom

/** Keeps the current choice if it is still offered; otherwise picks the option for [host], or Custom. */
internal fun keepSelection(options: List<MintOption>, before: List<MintOption>, selected: MintOption, host: String): MintOption =
    selected.takeIf { it in options && (it != MintOption.Custom || before.isNotEmpty()) }
        ?: options.firstOrNull { it.host == host }
        ?: MintOption.Custom
