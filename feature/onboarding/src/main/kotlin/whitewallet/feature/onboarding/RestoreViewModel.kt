package whitewallet.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.PhraseCheck
import whitewallet.core.wallet.RecoveryPhrase
import whitewallet.core.wallet.RestoreResult
import whitewallet.core.wallet.Wallet
import java.io.IOException
import whitewallet.core.wallet.MintKeyChangedException

/** Why a restore didn't happen; each maps to a full string resource. */
sealed interface RestoreError {
    data class AlreadyInWallet(val accountName: String) : RestoreError
    data class AccountLimit(val limit: Int) : RestoreError
    data object Mint : RestoreError
    data object Network : RestoreError
}

data class RestoreState(
    val phrase: String = "",
    val host: String,
    val canChangeHost: Boolean,
    val mintOptions: List<MintOption> = emptyList(),
    val selectedMint: MintOption = MintOption.Custom,
    val customHost: String = "",
    /** Asked for when the restored account joins existing ones; the first account gets the default name. */
    val askAccountName: Boolean = false,
    val accountName: String = "",
    val busy: Boolean = false,
    val error: RestoreError? = null,
    /** Developer mode offers the BIP-39 test phrase (see [RestoreViewModel.useTestPhrase]). */
    val developerMode: Boolean = false,
    /** Set once restored: the host the account was restored on (its address is picked there next). */
    val restoredOn: String? = null,
) {
    val check: PhraseCheck get() = RecoveryPhrase.check(phrase)

    /** What to tell the user while they type (see [RecoveryPhrase.checkWhileTyping]). */
    val hint: PhraseCheck get() = RecoveryPhrase.checkWhileTyping(phrase)
    val canSubmit: Boolean get() = !busy && check is PhraseCheck.Valid && host.isNotBlank() && (!askAccountName || accountName.isNotBlank())
}

@HiltViewModel
class RestoreViewModel @Inject constructor(
    private val wallet: Wallet,
    private val defaults: AppDefaults,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(RestoreState(host = defaults.defaultMintHost, canChangeHost = defaults.allowMintChange))
    val state: StateFlow<RestoreState> = _state
    private var initialized = false
    private var defaultAccountName = ""

    /** Call once from the screen. [asNewAccount]: the wallet already has accounts, so the restored one gets a name. */
    fun init(asNewAccount: Boolean, defaultAccountName: String, newAccountName: String) {
        if (initialized) return
        initialized = true
        this.defaultAccountName = defaultAccountName
        _state.update { it.copy(askAccountName = asNewAccount, accountName = if (asNewAccount) newAccountName else "") }
        viewModelScope.launch {
            preferences.developerMode.collect { developerMode ->
                _state.update { s ->
                    val options = mintOptions(defaults, developerMode)
                    val selected = keepSelection(options, s.mintOptions, s.selectedMint, s.host)
                    s.copy(
                        mintOptions = options, selectedMint = selected, developerMode = developerMode,
                        customHost = if (selected == MintOption.Custom) s.host else s.customHost,
                    )
                }
            }
        }
    }

    fun onPhraseChange(value: String) = _state.update { it.copy(phrase = value, error = null) }

    /** Developer mode: fills in "abandon abandon … about", the well-known BIP-39 test phrase. Never for real money. */
    fun useTestPhrase() {
        if (_state.value.developerMode) onPhraseChange(TEST_PHRASE)
    }
    fun onAccountNameChange(value: String) = _state.update { it.copy(accountName = value) }
    fun onMintSelected(option: MintOption) = _state.update { it.copy(selectedMint = option, host = option.host ?: it.customHost, error = null) }
    fun onCustomHostChange(value: String) = _state.update { it.copy(customHost = value, host = value, error = null) }

    fun restore() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val name = if (s.askAccountName) s.accountName.trim() else defaultAccountName
            val result = try {
                wallet.restore(s.phrase, s.host, name)
            } catch (e: IOException) {
                return@launch _state.update { it.copy(busy = false, error = RestoreError.Network) }
            } catch (e: MintKeyChangedException) {
                return@launch _state.update { it.copy(busy = false, error = RestoreError.Mint) }
            }
            val error = when (result) {
                is RestoreResult.Restored -> {
                    // The phrase isn't needed any more; don't keep it in memory longer than the screen.
                    _state.update { it.copy(busy = false, phrase = "", restoredOn = s.host.trim().lowercase()) }
                    return@launch
                }
                is RestoreResult.AlreadyInWallet -> RestoreError.AlreadyInWallet(result.accountName)
                is RestoreResult.AccountLimitReached -> RestoreError.AccountLimit(result.limit)
                is RestoreResult.MintInvalid -> RestoreError.Mint
                is RestoreResult.NetworkError -> RestoreError.Network
                // The button is only enabled for a valid phrase.
                is RestoreResult.InvalidPhrase -> null
            }
            _state.update { it.copy(busy = false, error = error) }
        }
    }
}

/** The BIP-39 test vector for all-zero entropy: anyone can spend what it holds, so only for developing. */
internal const val TEST_PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
