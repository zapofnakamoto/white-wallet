package whitewallet.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.PhraseCheck
import whitewallet.core.wallet.RecoveryPhrase
import whitewallet.core.wallet.RestoreResult
import whitewallet.core.wallet.Wallet

/** Why a restore didn't happen; each maps to a full string resource. */
sealed interface RestoreError {
    data class AlreadyInWallet(val accountName: String) : RestoreError

    data class AccountLimit(val limit: Int) : RestoreError
}

data class RestoreState(
    val phrase: String = "",
    val busy: Boolean = false,
    val error: RestoreError? = null,
    /** Developer mode offers the BIP-39 test phrase (see [RestoreViewModel.useTestPhrase]). */
    val developerMode: Boolean = false,
    /** Set once restored: the wallet's mints come next, each finding what the phrase holds there. */
    val restored: Boolean = false,
) {
    val check: PhraseCheck
        get() = RecoveryPhrase.check(phrase)

    /** What to tell the user while they type (see [RecoveryPhrase.checkWhileTyping]). */
    val hint: PhraseCheck
        get() = RecoveryPhrase.checkWhileTyping(phrase)

    val canSubmit: Boolean
        get() = !busy && check is PhraseCheck.Valid
}

/** Brings a wallet back from its recovery phrase alone: what it holds is found as its mints are added, in the next steps. */
@HiltViewModel
class RestoreViewModel @Inject constructor(private val wallet: Wallet, preferences: AppPreferences) : ViewModel() {
    private val _state = MutableStateFlow(RestoreState())
    val state: StateFlow<RestoreState> = _state

    init {
        viewModelScope.launch { preferences.developerMode.collect { on -> _state.update { it.copy(developerMode = on) } } }
    }

    fun onPhraseChange(value: String) = _state.update { it.copy(phrase = value, error = null) }

    /** Developer mode: fills in "abandon abandon … about", the well-known BIP-39 test phrase. Never for real money. */
    fun useTestPhrase() {
        if (_state.value.developerMode) onPhraseChange(TEST_PHRASE)
    }

    /** Restores the phrase as a wallet named [name] (the app's word for a first wallet, in the user's language). */
    fun restore(name: String) {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val error =
                when (val result = wallet.restore(s.phrase, null, name)) {
                    is RestoreResult.Restored -> {
                        // The phrase isn't needed any more; don't keep it in memory longer than the screen.
                        _state.update { it.copy(busy = false, phrase = "", restored = true) }
                        return@launch
                    }
                    is RestoreResult.AlreadyInWallet -> RestoreError.AlreadyInWallet(result.accountName)
                    is RestoreResult.AccountLimitReached -> RestoreError.AccountLimit(result.limit)
                    // Only with a mint, which this step doesn't contact.
                    is RestoreResult.MintInvalid,
                    is RestoreResult.NetworkError,
                    // The button is only enabled for a valid phrase.
                    is RestoreResult.InvalidPhrase -> null
                }
            _state.update { it.copy(busy = false, error = error) }
        }
    }
}

/** The BIP-39 test vector for all-zero entropy: anyone can spend what it holds, so only for developing. */
internal const val TEST_PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
