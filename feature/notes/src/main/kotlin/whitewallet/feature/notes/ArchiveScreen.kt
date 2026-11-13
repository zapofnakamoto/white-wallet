package whitewallet.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.ReceivedNoteRow
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletSnackbarHost
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.model.ReceivedNote
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.ClaimResult
import whitewallet.core.wallet.Wallet

/** What trying an archived note again came to, told once. */
sealed interface RetryOutcome {
    data class Claimed(val amountMsat: Long) : RetryOutcome

    /** Back among the received notes: kept, refused for now, being claimed, or found spent. */
    data object Back : RetryOutcome
}

data class ArchiveState(
    /** The notes were read: until then the screen draws nothing. */
    val loaded: Boolean = true,
    /** The active account's archived received notes, newest first. */
    val notes: List<ReceivedNote> = emptyList(),
    val showMints: Boolean = true,
    /** The note waiting for the user to confirm removing it for good. */
    val removing: String? = null,
    /** A note being tried again. */
    val retrying: String? = null,
    val outcome: RetryOutcome? = null,
)

/** The archive: received notes the wallet no longer tries; each can be tried again or removed for good. */
@HiltViewModel
class ArchiveViewModel @Inject constructor(private val wallet: Wallet, defaults: AppDefaults) : ViewModel() {
    private val ui = MutableStateFlow(ArchiveState(showMints = defaults.showMints))

    val state: StateFlow<ArchiveState> =
        combine(wallet.receivedNotes, ui) { received, u -> u.copy(loaded = true, notes = ReceivedNote.archived(received)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchiveState(loaded = false))

    fun retry(id: String) {
        if (ui.value.retrying != null) return
        ui.update { it.copy(retrying = id, outcome = null) }
        viewModelScope.launch {
            val result = wallet.retryReceivedNote(id)
            ui.update {
                it.copy(
                    retrying = null,
                    outcome =
                        when (result) {
                            is ClaimResult.Claimed -> RetryOutcome.Claimed(result.amountMsat)
                            null -> null
                            else -> RetryOutcome.Back
                        },
                )
            }
        }
    }

    /** Asks before removing [id] for good. */
    fun askRemove(id: String) = ui.update { it.copy(removing = id) }

    fun cancelRemove() = ui.update { it.copy(removing = null) }

    fun remove() {
        val id = ui.value.removing ?: return
        ui.update { it.copy(removing = null) }
        viewModelScope.launch { wallet.removeReceivedNote(id) }
    }

    fun outcomeShown() = ui.update { it.copy(outcome = null) }
}

@Composable
fun ArchiveRoute(onBack: () -> Unit, viewModel: ArchiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ArchiveScreen(
        state,
        ArchiveActions(viewModel::retry, viewModel::askRemove, viewModel::cancelRemove, viewModel::remove, viewModel::outcomeShown),
        onBack,
    )
}

/** Everything the archive can ask for. */
class ArchiveActions(
    val retry: (String) -> Unit = {},
    val askRemove: (String) -> Unit = {},
    val cancelRemove: () -> Unit = {},
    val remove: () -> Unit = {},
    val outcomeShown: () -> Unit = {},
)

@Composable
fun ArchiveScreen(state: ArchiveState, actions: ArchiveActions, onBack: () -> Unit) {
    val snackbars = remember { SnackbarHostState() }
    state.outcome?.let { outcome ->
        val message =
            when (outcome) {
                is RetryOutcome.Claimed -> stringResource(R.string.archive_retry_claimed, formatAmount(outcome.amountMsat))
                RetryOutcome.Back -> stringResource(R.string.archive_retry_kept)
            }
        LaunchedEffect(outcome) {
            snackbars.showSnackbar(message, duration = SnackbarDuration.Long)
            actions.outcomeShown()
        }
    }
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.archive_title), onBack) }, snackbarHost = { WalletSnackbarHost(snackbars) }) {
        padding ->
        if (!pageLoaded(state.loaded)) return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                WalletListItem(
                    headlineContent = {
                        Text(
                            stringResource(if (state.notes.isEmpty()) R.string.archive_empty else R.string.archive_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                )
            }
            items(state.notes, key = { it.id }) { note ->
                ReceivedNoteRow(
                    note,
                    state.showMints,
                    amountHidden = false,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            WalletButton(
                                kind = ButtonKind.Text,
                                enabled = state.retrying == null,
                                busy = state.retrying == note.id,
                                onClick = { actions.retry(note.id) },
                            ) {
                                Text(stringResource(R.string.archive_retry))
                            }
                            WalletButton(
                                kind = ButtonKind.Text,
                                enabled = state.retrying == null,
                                onClick = { actions.askRemove(note.id) },
                            ) {
                                Text(stringResource(R.string.archive_remove))
                            }
                        }
                    },
                )
            }
        }
    }
    if (state.removing != null) {
        WalletDialog(
            onDismissRequest = actions.cancelRemove,
            title = { Text(stringResource(R.string.archive_remove_title)) },
            text = { Text(stringResource(R.string.archive_remove_body)) },
            confirmButton = {
                WalletButton(kind = ButtonKind.Text, onClick = actions.remove) { Text(stringResource(R.string.archive_remove_confirm)) }
            },
            dismissButton = {
                WalletButton(kind = ButtonKind.Text, onClick = actions.cancelRemove) { Text(stringResource(R.string.archive_remove_keep)) }
            },
        )
    }
}
