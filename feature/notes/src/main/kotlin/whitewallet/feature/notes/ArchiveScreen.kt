package whitewallet.feature.notes

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.Page
import whitewallet.core.designsystem.BrandBody
import whitewallet.core.designsystem.EmptyState
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.ReceivedNoteRow
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletSnackbarHost
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.model.Note
import whitewallet.core.model.ReceivedNote
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.ClaimResult
import whitewallet.core.wallet.NoteCheck
import whitewallet.core.wallet.Wallet

/** What trying an archived link again, or asking about an archived note, came to, told once. */
sealed interface RetryOutcome {
    data class Claimed(val amountMsat: Long) : RetryOutcome

    /** Back among the received notes: kept, refused for now, being claimed, or found spent. */
    data object Back : RetryOutcome

    /** The mint knows the archived note again: it is back in the balance. */
    data class NoteBack(val amountMsat: Long) : RetryOutcome

    /** The mint still doesn't know it (or burned it meanwhile, which it is now). */
    data object NoteStill : RetryOutcome

    data object NoteNoAnswer : RetryOutcome

    data class NoteKeyChanged(val host: String) : RetryOutcome

    /** A note the user handed out: its mint knows it again, and it waits to be claimed. */
    data object NoteHandedOutAgain : RetryOutcome

    /** A note the user handed out and asked to take back: its mint knows it again, but refused ([mintMessage]: its words, if any). */
    data class NoteTakeBackRefused(val mintMessage: String?) : RetryOutcome
}

data class ArchiveState(
    /** The notes were read: until then the screen draws nothing. */
    val loaded: Boolean = true,
    /** The active account's archived received notes, newest first. */
    val notes: List<ReceivedNote> = emptyList(),
    /** The active account's notes archived while their mint doesn't know them ([Note.isArchived]), newest first. */
    val unknownNotes: List<Note> = emptyList(),
    val showMints: Boolean = true,
    /** The received note waiting for the user to confirm removing it for good. */
    val removing: String? = null,
    /** The archived note waiting for the user to confirm removing it for good. */
    val removingNote: String? = null,
    /** A received note being tried again, or an archived note being asked about. */
    val retrying: String? = null,
    val outcome: RetryOutcome? = null,
) {
    val isEmpty: Boolean
        get() = notes.isEmpty() && unknownNotes.isEmpty()
}

/**
 * The archive: received notes the wallet no longer tries, each to be tried again or removed for good; and notes their mint doesn't know
 * that the user archived, each to be asked about now or removed for good.
 */
@HiltViewModel
class ArchiveViewModel @Inject constructor(private val wallet: Wallet, defaults: AppDefaults) : ViewModel() {
    private val ui = MutableStateFlow(ArchiveState(showMints = defaults.showMints))

    val state: StateFlow<ArchiveState> =
        combine(wallet.receivedNotes, wallet.notes, ui) { received, notes, u ->
                u.copy(
                    loaded = true,
                    notes = ReceivedNote.archived(received),
                    unknownNotes = notes.filter { it.isArchived }.sortedByDescending { it.archivedAtMillis },
                )
            }
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
                            is ClaimResult.Failed,
                            is ClaimResult.Kept,
                            ClaimResult.Pending -> RetryOutcome.Back
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

    /** Asks the mint about the archived note [id] now; one it knows again is back in the balance. */
    fun recheck(id: String) {
        if (ui.value.retrying != null) return
        ui.update { it.copy(retrying = id, outcome = null) }
        viewModelScope.launch {
            val outcome =
                when (val check = wallet.recheckNote(id)) {
                    is NoteCheck.Ok -> RetryOutcome.NoteBack(check.amountMsat)
                    // Taken back as the user asked: back in the balance all the same.
                    is NoteCheck.TakenBack -> RetryOutcome.NoteBack(check.amountMsat)
                    NoteCheck.HandedOutAgain -> RetryOutcome.NoteHandedOutAgain
                    is NoteCheck.TakeBackRefused -> RetryOutcome.NoteTakeBackRefused(check.mintMessage)
                    NoteCheck.Unknown,
                    NoteCheck.Spent -> RetryOutcome.NoteStill
                    is NoteCheck.KeyChanged -> RetryOutcome.NoteKeyChanged(check.host)
                    // Gone meanwhile (removed, or back): nothing to tell.
                    NoteCheck.NotFound -> null
                    else -> RetryOutcome.NoteNoAnswer
                }
            ui.update { it.copy(retrying = null, outcome = outcome) }
        }
    }

    /** Asks before removing the archived note [id] for good. */
    fun askRemoveNote(id: String) = ui.update { it.copy(removingNote = id) }

    fun cancelRemoveNote() = ui.update { it.copy(removingNote = null) }

    fun removeNote() {
        val id = ui.value.removingNote ?: return
        ui.update { it.copy(removingNote = null) }
        viewModelScope.launch { wallet.removeArchivedNote(id) }
    }

    fun outcomeShown() = ui.update { it.copy(outcome = null) }
}

@Composable
fun ArchiveRoute(onBack: () -> Unit, viewModel: ArchiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ArchiveScreen(
        state,
        ArchiveActions(
            viewModel::retry,
            viewModel::askRemove,
            viewModel::cancelRemove,
            viewModel::remove,
            viewModel::outcomeShown,
            viewModel::recheck,
            viewModel::askRemoveNote,
            viewModel::cancelRemoveNote,
            viewModel::removeNote,
        ),
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
    val recheck: (String) -> Unit = {},
    val askRemoveNote: (String) -> Unit = {},
    val cancelRemoveNote: () -> Unit = {},
    val removeNote: () -> Unit = {},
)

@Composable
fun ArchiveScreen(state: ArchiveState, actions: ArchiveActions, onBack: () -> Unit) {
    val snackbars = remember { SnackbarHostState() }
    state.outcome?.let { outcome ->
        val message =
            when (outcome) {
                is RetryOutcome.Claimed -> stringResource(R.string.archive_retry_claimed, formatAmount(outcome.amountMsat))
                RetryOutcome.Back -> stringResource(R.string.archive_retry_kept)
                is RetryOutcome.NoteBack -> stringResource(R.string.archive_note_back, formatAmount(outcome.amountMsat))
                RetryOutcome.NoteStill -> stringResource(R.string.archive_note_still)
                RetryOutcome.NoteNoAnswer -> stringResource(R.string.archive_note_no_answer)
                is RetryOutcome.NoteKeyChanged -> stringResource(R.string.notes_key_changed, outcome.host)
                RetryOutcome.NoteHandedOutAgain -> pluralStringResource(R.plurals.notes_checked_handed_out, 1, 1)
                is RetryOutcome.NoteTakeBackRefused ->
                    listOfNotNull(
                            pluralStringResource(R.plurals.notes_checked_take_back_refused, 1, 1),
                            outcome.mintMessage?.let { stringResource(DesignR.string.mint_message, it) },
                        )
                        .joinToString(" ")
            }
        LaunchedEffect(outcome) {
            snackbars.showSnackbar(message, duration = SnackbarDuration.Long)
            actions.outcomeShown()
        }
    }
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.archive_title), onBack) }, snackbarHost = { WalletSnackbarHost(snackbars) }) {
        padding ->
        if (!pageLoaded(state.loaded)) return@Scaffold
        BrandBody(Page.Archive, if (state.isEmpty) WalletStatus.Empty else WalletStatus.Idle) {
            // Notes handed out as links were never in the balance: a part of their own, which says what becomes of them.
            val (handedOut, held) = state.unknownNotes.partition { it.handedOut != null }
            // Each part is titled only when more than one holds something.
            val titled = listOf(state.notes, held, handedOut).count { it.isNotEmpty() } > 1
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                if (state.isEmpty) item { EmptyState(stringResource(R.string.archive_empty)) }
                if (state.notes.isNotEmpty()) {
                    item { PartHeader(R.string.archive_links_title.takeIf { titled }, R.string.archive_hint, divider = false) }
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
                                        Text(stringResource(DesignR.string.try_again))
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
                if (held.isNotEmpty()) {
                    val first = state.notes.isEmpty()
                    item {
                        PartHeader(R.string.archive_notes_title.takeIf { titled }, R.string.archive_notes_hint, divider = titled && !first)
                    }
                    items(held, key = { "note:${it.id}" }) { note -> ArchivedNoteRow(note, state, actions) }
                }
                if (handedOut.isNotEmpty()) {
                    val first = state.notes.isEmpty() && held.isEmpty()
                    item {
                        PartHeader(
                            R.string.archive_handed_out_title.takeIf { titled },
                            R.string.archive_handed_out_hint,
                            divider = titled && !first,
                        )
                    }
                    items(handedOut, key = { "note:${it.id}" }) { note -> ArchivedNoteRow(note, state, actions) }
                }
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
    if (state.removingNote != null) {
        WalletDialog(
            onDismissRequest = actions.cancelRemoveNote,
            title = { Text(stringResource(R.string.archive_note_remove_title)) },
            text = { Text(stringResource(R.string.archive_note_remove_body)) },
            confirmButton = {
                WalletButton(kind = ButtonKind.Text, onClick = actions.removeNote) { Text(stringResource(R.string.archive_remove_confirm)) }
            },
            dismissButton = {
                WalletButton(kind = ButtonKind.Text, onClick = actions.cancelRemoveNote) {
                    Text(stringResource(R.string.archive_remove_keep))
                }
            },
        )
    }
}

/** A part of the archive: its [title] (null when it is the only part) and what it holds. */
@Composable
private fun PartHeader(@StringRes title: Int?, @StringRes hint: Int, divider: Boolean) {
    if (divider) HorizontalDivider()
    WalletListItem(
        headlineContent = {
            if (title != null)
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            else Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = title?.let { { Text(stringResource(hint)) } },
    )
}

/** An archived note its mint doesn't know: what it was worth, where, and asking about it now or removing it for good. */
@Composable
private fun ArchivedNoteRow(note: Note, state: ArchiveState, actions: ArchiveActions) {
    WalletListItem(
        headlineContent = { Text(formatAmount(note.amountMsat)) },
        supportingContent = {
            Column {
                Text(stringResource(if (note.handedOut != null) R.string.notes_state_unknown_handed_out else R.string.notes_state_unknown))
                if (state.showMints) Text(stringResource(DesignR.string.received_on, note.host), style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WalletButton(
                    kind = ButtonKind.Text,
                    enabled = state.retrying == null,
                    busy = state.retrying == note.id,
                    onClick = { actions.recheck(note.id) },
                ) {
                    Text(stringResource(DesignR.string.check_again))
                }
                WalletButton(kind = ButtonKind.Text, enabled = state.retrying == null, onClick = { actions.askRemoveNote(note.id) }) {
                    Text(stringResource(R.string.archive_remove))
                }
            }
        },
    )
}
