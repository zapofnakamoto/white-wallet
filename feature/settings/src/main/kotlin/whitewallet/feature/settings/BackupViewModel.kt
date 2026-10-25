package whitewallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.wallet.BackupQuiz
import whitewallet.core.wallet.Wallet

/** The steps of the recovery phrase backup, following the Bitcoin Design Guide's manual backup flow. */
sealed interface BackupStep {
    data object Intro : BackupStep
    data object NoScreenLock : BackupStep
    data class Display(val words: List<String>) : BackupStep

    /** [choices] are original positions in display order; [progress] the positions tapped correctly so far. */
    data class Verify(val words: List<String>, val choices: List<Int>, val progress: List<Int>, val wrongNumber: Int?) : BackupStep
    data object Success : BackupStep
}

data class BackupState(
    val step: BackupStep = BackupStep.Intro,
    val alreadyVerified: Boolean = false,
    /** Addresses a restore can't find by itself (not seed-derived), to write down next to the words. */
    val addressesToWriteDown: List<String> = emptyList(),
)

@HiltViewModel
class BackupViewModel @Inject constructor(private val wallet: Wallet) : ViewModel() {
    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state
    private var quiz: BackupQuiz? = null

    init {
        viewModelScope.launch {
            _state.update { it.copy(alreadyVerified = wallet.backupVerified.first(), addressesToWriteDown = wallet.addressesToWriteDown()) }
        }
    }

    /** The phone has no screen lock to ask for. */
    fun onNoScreenLock() = _state.update { it.copy(step = BackupStep.NoScreenLock) }

    fun onAuthenticated() {
        viewModelScope.launch { _state.update { it.copy(step = BackupStep.Display(wallet.recoveryPhrase())) } }
    }

    fun onAuthCancelled() = _state.update { it.copy(step = BackupStep.Intro) }

    fun onWroteItDown() {
        val words = (_state.value.step as? BackupStep.Display)?.words ?: return
        val q = BackupQuiz(words).also { quiz = it }
        _state.update { it.copy(step = BackupStep.Verify(words, q.choices, q.progress, null)) }
    }

    fun onTap(position: Int) {
        val q = quiz ?: return
        val step = _state.value.step as? BackupStep.Verify ?: return
        when (val result = q.tap(position)) {
            BackupQuiz.Result.Complete -> complete()
            BackupQuiz.Result.Correct -> _state.update { it.copy(step = step.copy(progress = q.progress, wrongNumber = null)) }
            is BackupQuiz.Result.Wrong -> _state.update { it.copy(step = step.copy(progress = q.progress, wrongNumber = result.expectedNumber)) }
        }
    }

    /** Only offered when this phrase was already verified before. */
    fun onSkipVerify() {
        if (_state.value.alreadyVerified) complete()
    }

    private fun complete() {
        quiz = null
        viewModelScope.launch {
            wallet.markBackupVerified()
            _state.update { it.copy(step = BackupStep.Success, alreadyVerified = true) }
        }
    }

    override fun onCleared() {
        quiz = null
        _state.value = BackupState()
    }
}
