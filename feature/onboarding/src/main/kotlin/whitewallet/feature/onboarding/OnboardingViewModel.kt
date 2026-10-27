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
import whitewallet.core.wallet.AddressStatus
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.ConnectResult
import whitewallet.core.wallet.CreateAccountResult
import whitewallet.core.wallet.HomeChangePreview
import whitewallet.core.wallet.OnboardResult
import whitewallet.core.wallet.UsernameSuggestions
import whitewallet.core.wallet.Wallet

/** Live result of checking the typed name on the service. */
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

    /** Picks a different primary address for the active account, on its current service. The old one stays registered. */
    ChangeAddress,

    /** Registers another (non-primary) address for the active account, on its current service. */
    AddAddress,

    /**
     * Registers an address for the active account on another service: same recovery phrase, that service's own keys.
     * Never primary; payments to it arrive as notes there.
     */
    AddAddressElsewhere,

    /**
     * Moves the active account's home to another service (brand `allowServiceChange`), with its primary address there
     * if the account has one (see [Wallet.changeHome]). The old address stays registered.
     */
    ChangeService,

    /**
     * Gives a just-restored account (no address yet) its address on the mint it was restored from: one it already
     * owns (taken back without registering again) or a new one.
     */
    RestoreAddress,
}

/** An entry of the service dropdown. */
sealed interface ServiceOption {
    /** The host it stands for; null for [Custom], whose host the user types. */
    val host: String?

    data class Suggested(override val host: String) : ServiceOption

    /** The local regtest mint (dev/regtest); only offered in developer mode in debug builds. */
    data class LocalDev(override val host: String) : ServiceOption

    data object Custom : ServiceOption {
        override val host: String? = null
    }
}

data class OnboardingState(
    val username: String,
    /** The service actually used: the selected option's host, or [customHost] for [ServiceOption.Custom]. */
    val host: String,
    val canChangeHost: Boolean,
    val serviceOptions: List<ServiceOption> = emptyList(),
    val selectedService: ServiceOption = ServiceOption.Custom,
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
    /** False when a new account starts without a Lightning Address (brand `requireAddress=false`): only its mint is asked for. */
    val withAddress: Boolean = true,
    /** For [OnboardingMode.ChangeService]: what the change would do with the money elsewhere, shown for confirmation. */
    val review: HomeChangePreview? = null,
) {
    val changingAddress: Boolean get() = mode == OnboardingMode.ChangeAddress
    val addingAddress: Boolean get() = mode == OnboardingMode.AddAddress
    val addingElsewhere: Boolean get() = mode == OnboardingMode.AddAddressElsewhere
    val changingService: Boolean get() = mode == OnboardingMode.ChangeService
    val restoringAddress: Boolean get() = mode == OnboardingMode.RestoreAddress

    /** The address belongs to the active account (not to an account being created). */
    val forExistingAccount: Boolean get() = changingAddress || addingAddress || addingElsewhere || changingService || restoringAddress
    val canSubmit: Boolean get() = !busy && (if (withAddress) addressOk else host.isNotBlank()) &&
        (mode != OnboardingMode.NewAccount || accountName.isNotBlank()) && (!addingElsewhere && !changingService || host.isNotBlank())
    private val addressOk: Boolean get() = username.isNotBlank() && availability !is Availability.Taken && availability !is Availability.Invalid
}

sealed interface OnboardingError {
    val detail: String
    data class Username(override val detail: String) : OnboardingError
    data class Registration(override val detail: String) : OnboardingError
    data class Service(override val detail: String) : OnboardingError
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
        OnboardingState(UsernameSuggestions.random(), defaults.defaultServiceHost, defaults.allowServiceChange, withAddress = defaults.requireAddress),
    )
    val state: StateFlow<OnboardingState> = _state
    private var checkJob: Job? = null
    private var initialized = false

    private var defaultAccountName = ""

    /**
     * Call once from the screen. [defaultAccountName] names the first account (localized by the caller); a new
     * account starts with [newAccountName]. When changing the address, the service is fixed to the current one.
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
                        it.copy(mode = mode, accountName = newAccountName, canChangeHost = defaults.allowServiceChange, host = presetHost ?: it.host)
                    }
                    suggestDefaultName()
                }
                OnboardingMode.ChangeAddress, OnboardingMode.AddAddress -> {
                    val host = wallet.homeHost.first() ?: _state.value.host
                    _state.update { it.copy(mode = mode, host = host, canChangeHost = false, withAddress = true) }
                }
                OnboardingMode.AddAddressElsewhere -> {
                    homeHost = wallet.homeHost.first()
                    val other = serviceOptions(defaults, preferences.developerMode.first()).firstNotNullOfOrNull { o -> o.host?.takeIf { it != homeHost } }
                    // The same name as at home is the natural first try.
                    val name = wallet.profile.first()?.username.orEmpty()
                    _state.update { it.copy(mode = mode, host = other.orEmpty(), canChangeHost = true, withAddress = true, username = name) }
                }
                OnboardingMode.ChangeService -> {
                    homeHost = wallet.homeHost.first()
                    val target = presetHost?.takeIf { it != homeHost }
                        ?: serviceOptions(defaults, preferences.developerMode.first()).firstNotNullOfOrNull { o -> o.host?.takeIf { it != homeHost } }
                    val profile = wallet.profile.first()
                    // The same name as now is the natural first try.
                    _state.update {
                        it.copy(mode = mode, host = target.orEmpty(), canChangeHost = true, withAddress = profile != null, username = profile?.username.orEmpty())
                    }
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
            launch { preferences.developerMode.collect(::updateServiceOptions) }
            scheduleCheck(immediately = true)
        }
    }

    fun onServiceSelected(option: ServiceOption) {
        _state.update { it.copy(selectedService = option, host = option.host ?: it.customHost, error = null, review = null) }
        onHostChanged()
    }

    fun onCustomHostChange(value: String) {
        _state.update { it.copy(customHost = value, host = value, error = null, review = null) }
        onHostChanged()
    }

    /** The derived name depends on the service; it follows the host unless the user typed their own. */
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

    /** For [OnboardingMode.AddAddressElsewhere] and [OnboardingMode.ChangeService]: the home mint, left out of the choice. */
    private var homeHost: String? = null

    private fun updateServiceOptions(developerMode: Boolean) = _state.update { s ->
        val options = serviceOptions(defaults, developerMode).filter { it.host == null || it.host != homeHost }
        val selected = keepSelection(options, s.serviceOptions, s.selectedService, s.host)
        s.copy(serviceOptions = options, selectedService = selected, customHost = if (selected == ServiceOption.Custom) s.host else s.customHost)
    }

    fun onAccountNameChange(value: String) = _state.update { it.copy(accountName = value) }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value, error = null, suggestions = emptyList(), review = null) }
        scheduleCheck()
    }

    fun onSuggestion(name: String) = onUsernameChange(name)

    fun start() {
        val s = _state.value
        if (!s.canSubmit) return
        checkJob?.cancel()
        _state.update { it.copy(busy = true, error = null) }
        // Changing the home mint shows what it does with the money first (see [confirmChange]).
        if (s.changingService) return review(s)
        submit(s, moveMoney = false)
    }

    /** From the summary: change the home mint, and with [moveMoney] have the money moved even if the account doesn't collect. */
    fun confirmChange(moveMoney: Boolean) {
        val s = _state.value
        if (s.busy || s.review == null) return
        _state.update { it.copy(busy = true) }
        submit(s, moveMoney)
    }

    /** Back from the summary to the choice of mint and name. */
    fun backFromReview() = _state.update { if (it.busy) it else it.copy(review = null) }

    private fun review(s: OnboardingState) {
        viewModelScope.launch {
            val preview = wallet.previewChangeHome(s.host) ?: return@launch fail(OnboardingError.Service(s.host))
            // Nothing elsewhere: nothing to confirm.
            if (preview.lines.isEmpty()) return@launch submit(s, moveMoney = false)
            _state.update { it.copy(busy = false, review = preview) }
        }
    }

    private fun submit(s: OnboardingState, moveMoney: Boolean) {
        viewModelScope.launch {
            if (!s.withAddress) return@launch if (s.changingService) changeMint(s, moveMoney) else createAccount(s)
            val result = when (s.mode) {
                OnboardingMode.FirstRun -> wallet.onboard(s.username, s.host, defaultAccountName)
                OnboardingMode.NewAccount -> wallet.onboard(s.username, s.host, s.accountName.trim())
                OnboardingMode.ChangeAddress -> wallet.onboard(s.username, s.host, null)
                OnboardingMode.AddAddress -> wallet.addAddress(s.username, addressLimit())
                OnboardingMode.AddAddressElsewhere -> wallet.addAddress(s.username, addressLimit(), s.host)
                OnboardingMode.ChangeService -> wallet.changeHome(s.host, s.username, moveMoney)
                OnboardingMode.RestoreAddress -> wallet.onboard(s.username, s.host, null)
            }
            when (result) {
                is OnboardResult.Ready -> _state.update {
                    it.copy(busy = false, claimed = Claimed(result.profile.address, result.confirmed), availability = Availability.Yours)
                }
                is OnboardResult.UsernameTaken -> _state.update {
                    it.copy(busy = false, availability = Availability.Taken, suggestions = result.suggestions, review = null)
                }
                is OnboardResult.UsernameInvalid -> fail(OnboardingError.Username(result.reason))
                is OnboardResult.RegistrationFailed -> fail(OnboardingError.Registration(result.reason ?: ""))
                is OnboardResult.ServiceInvalid -> fail(OnboardingError.Service(result.reason))
                is OnboardResult.NetworkError -> fail(OnboardingError.Network(result.reason))
                is OnboardResult.LimitReached -> fail(OnboardingError.Limit(result.limit))
                is OnboardResult.AccountLimitReached -> fail(OnboardingError.AccountLimit(result.limit))
            }
        }
    }

    /** A new account without an address: nothing to show afterwards, it opens right away. */
    private suspend fun createAccount(s: OnboardingState) {
        val name = if (s.mode == OnboardingMode.NewAccount) s.accountName.trim() else defaultAccountName
        when (val result = wallet.createAccount(s.host, name)) {
            is CreateAccountResult.Created -> _state.update { it.copy(busy = false, done = true) }
            is CreateAccountResult.AccountLimitReached -> fail(OnboardingError.AccountLimit(result.limit))
            is CreateAccountResult.ServiceInvalid -> fail(OnboardingError.Service(result.reason))
            is CreateAccountResult.NetworkError -> fail(OnboardingError.Network(result.reason))
        }
    }

    /** An account without an address only changes its mint: nothing to show afterwards. */
    private suspend fun changeMint(s: OnboardingState, moveMoney: Boolean) {
        when (val result = wallet.changeHome(s.host, moveMoney)) {
            is ConnectResult.Connected -> _state.update { it.copy(busy = false, done = true) }
            is ConnectResult.Failed -> when (val r = result.result) {
                is OnboardResult.NetworkError -> fail(OnboardingError.Network(r.reason))
                is OnboardResult.ServiceInvalid -> fail(OnboardingError.Service(r.reason))
                else -> fail(OnboardingError.Service(r.toString()))
            }
        }
    }

    fun onContinue() = _state.update { it.copy(done = true) }

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

    /** Addresses per service: the brand's limit, or the developer setting while developer mode is on. */
    private suspend fun addressLimit(): Int =
        defaults.addressLimit(preferences.developerMode.first(), preferences.maxAddressesPerService.first())

    private fun fail(error: OnboardingError) = _state.update { it.copy(busy = false, error = error, review = null) }

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

    private companion object {
        const val DEBOUNCE_MILLIS = 500L
    }
}

/** The service dropdown: the brand's suggestions, the local regtest mints in developer mode, and "Custom". */
internal fun serviceOptions(defaults: AppDefaults, developerMode: Boolean): List<ServiceOption> =
    defaults.serviceSuggestions.map(ServiceOption::Suggested) +
        (if (developerMode) defaults.localDevServices.map(ServiceOption::LocalDev) else emptyList()) +
        ServiceOption.Custom

/** Keeps the current choice if it is still offered; otherwise picks the option for [host], or Custom. */
internal fun keepSelection(options: List<ServiceOption>, before: List<ServiceOption>, selected: ServiceOption, host: String): ServiceOption =
    selected.takeIf { it in options && (it != ServiceOption.Custom || before.isNotEmpty()) }
        ?: options.firstOrNull { it.host == host }
        ?: ServiceOption.Custom
