package whitewallet.feature.notes

import android.icu.text.ListFormatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.recentTime
import whitewallet.core.model.Note
import whitewallet.core.model.NoteState
import whitewallet.core.designsystem.R as DesignR
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.ListIconSize
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCheckbox
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletListIcon
import whitewallet.core.designsystem.WalletListItem

/** Everything the notes view can ask for. Navigation ([openShared], [openSettings]) is the caller's. */
class NotesActions(
    val tap: (Note) -> Unit = {},
    val longPress: (Note) -> Unit = {},
    val startSelecting: () -> Unit = {},
    val stopSelecting: () -> Unit = {},
    val close: () -> Unit = {},
    val dismissOutcome: () -> Unit = {},
    val setSort: (NoteSort) -> Unit = {},
    val share: (String) -> Unit = {},
    val openSplit: (String) -> Unit = {},
    val recheck: (String) -> Unit = {},
    val renew: (String) -> Unit = {},
    val combine: () -> Unit = {},
    val openMove: () -> Unit = {},
    val recheckSelected: () -> Unit = {},
    val moveAnyway: (host: String) -> Unit = {},
    val splitAmount: (String) -> Unit = {},
    val split: () -> Unit = {},
    val moveTarget: (String) -> Unit = {},
    val move: () -> Unit = {},
    val dismissDialog: () -> Unit = {},
    val openShared: (String) -> Unit = {},
)

/** Wires [NotesViewModel] to [NotesContent]; [onOpenShared] shows a shared note (its QR code, or taking it back). */
@Composable
fun rememberNotesActions(viewModel: NotesViewModel, onOpenShared: (String) -> Unit): NotesActions = remember(viewModel, onOpenShared) {
    NotesActions(
        tap = viewModel::tap, longPress = viewModel::select, startSelecting = viewModel::startSelecting, stopSelecting = viewModel::stopSelecting,
        close = viewModel::close, dismissOutcome = viewModel::dismissOutcome, setSort = viewModel::setSort, share = viewModel::share,
        openSplit = viewModel::openSplit, recheck = viewModel::recheck, renew = viewModel::renew, combine = viewModel::combine,
        openMove = viewModel::openMove, recheckSelected = viewModel::recheckSelected, moveAnyway = viewModel::moveAnyway,
        splitAmount = viewModel::onSplitAmount, split = viewModel::split, moveTarget = viewModel::onMoveTarget, move = viewModel::move,
        dismissDialog = viewModel::dismissDialog, openShared = onOpenShared,
    )
}

/**
 * The Notes tab's content, inside Home (which supplies the top bar and pull to refresh, see [NotesViewModel.refresh]).
 * A just-shared note opens through [onOpenShared].
 */
@Composable
fun NotesTab(viewModel: NotesViewModel, onOpenShared: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.sharedId) {
        state.sharedId?.let { onOpenShared(it); viewModel.sharedShown() }
    }
    NotesContent(state, rememberNotesActions(viewModel, onOpenShared))
}

/** The notes view on its own page, for brands with `notesView=settings`; reached from account settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesRoute(onBack: () -> Unit, onSettings: () -> Unit, onOpenShared: (String) -> Unit, viewModel: NotesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.sharedId) {
        state.sharedId?.let { onOpenShared(it); viewModel.sharedShown() }
    }
    Scaffold(
        topBar = { WalletTopBar(stringResource(R.string.notes_title), onBack, actions = { NoteSettingsButton(onSettings) }) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            NotesContent(state, rememberNotesActions(viewModel, onOpenShared))
        }
    }
}

/** Opens the note settings (strategy, collecting at the home mint, auto-rotate). */
@Composable
fun NoteSettingsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(painterResource(DesignR.drawable.ic_tune), stringResource(R.string.notes_settings)) }
}

/**
 * The notes, per mint: how much, where, and whether anything is waiting. Tap a note for its details and actions;
 * long-press (or Select) to pick several on one mint.
 */
@Composable
fun NotesContent(state: NotesState, actions: NotesActions) {
    // Back ends selecting before it leaves the tab.
    BackHandler(enabled = state.selecting) { actions.stopSelecting() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Summary(state) }
            if (state.busy) item { WalletLinearProgress(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) }
            state.outcome?.takeIf { state.open == null }?.let { outcome -> item { Outcome(outcome, actions.dismissOutcome) } }
            if (state.loaded && state.mints.isEmpty() && state.shared.isEmpty()) {
                item { Text(stringResource(R.string.notes_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
            }
            if (state.mints.isNotEmpty()) item { Toolbar(state, actions) }
            state.mints.forEach { mint ->
                item(key = "mint:${mint.host}") { MintHeader(mint, state, actions.moveAnyway) }
                items(mint.notes, key = { it.id }) { note -> NoteRow(note, state, actions) }
            }
            if (state.shared.isNotEmpty()) {
                item { SharedHeader() }
                items(state.shared, key = { "shared:${it.id}" }) { note -> SharedRow(note, actions.openShared) }
            }
            // Room for the selection bar, so the last note can scroll above it.
            if (state.selecting) item { Spacer(Modifier.height(120.dp)) }
        }
        if (state.selecting) SelectionBar(state, actions, Modifier.align(Alignment.BottomCenter))
    }
    state.openNote?.let { NoteSheet(it, state, actions) }
    when (val d = state.dialog) {
        is NotesDialog.Split -> SplitDialog(d, state.noteStrategy?.takeIf { state.switchesToManual }, actions)
        is NotesDialog.Move -> MoveDialog(d, state, actions)
        null -> Unit
    }
}

@Composable
private fun Summary(state: NotesState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(formatAmount(state.totalMsat), style = MaterialTheme.typography.headlineMedium)
        if (state.noteCount > 0) {
            val parts = buildList {
                add(pluralStringResource(R.plurals.notes_count, state.noteCount, state.noteCount))
                if (state.mints.size > 1) add(pluralStringResource(R.plurals.notes_mints, state.mints.size, state.mints.size))
            }
            Text(parts.joinToString(stringResource(R.string.notes_detail_separator)), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.sharedMsat > 0) {
            Text(stringResource(R.string.notes_waiting_total, formatAmount(state.sharedMsat)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Sorting, and starting a selection. */
@Composable
private fun Toolbar(state: NotesState, actions: NotesActions) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            WalletButton(kind = ButtonKind.Text, onClick = { menu = true }) {
                Text(stringResource(sortLabel(state.sort)))
                Icon(painterResource(DesignR.drawable.ic_arrow_drop_down), stringResource(R.string.notes_sort))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                NoteSort.entries.forEach { sort ->
                    DropdownMenuItem(text = { Text(stringResource(sortLabel(sort))) }, onClick = { menu = false; actions.setSort(sort) })
                }
            }
        }
        if (!state.selecting) WalletButton(kind = ButtonKind.Text, onClick = actions.startSelecting, enabled = !state.busy) { Text(stringResource(R.string.notes_select)) }
    }
    if (state.noteStrategy?.reshapesNotes == true && state.selecting) {
        Text(
            stringResource(R.string.notes_strategy_warning, stringResource(state.noteStrategy.title)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private fun sortLabel(sort: NoteSort) = when (sort) {
    NoteSort.Largest -> R.string.notes_sort_largest
    NoteSort.Newest -> R.string.notes_sort_newest
}

@Composable
private fun MintHeader(mint: MintNotes, state: NotesState, onMoveAnyway: (String) -> Unit) {
    HorizontalDivider()
    val labels = listOfNotNull(
        stringResource(R.string.notes_home_mint).takeIf { mint.isHome },
        stringResource(R.string.notes_trusted).takeIf { mint.trusted },
    )
    WalletListItem(
        leadingContent = if (mint.trusted) ({ TrustedIcon() }) else null,
        headlineContent = { Text(mint.host, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() }) },
        supportingContent = if (labels.isEmpty()) null else ({ Text(labels.joinToString(stringResource(R.string.notes_detail_separator))) }),
        trailingContent = { Text(formatAmount(mint.totalMsat), style = MaterialTheme.typography.titleSmall) },
    )
    mint.skipped?.let { skipped ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.notes_group_not_moved, formatAmount(skipped.feeMsat), skipped.capPercent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            WalletButton(kind = ButtonKind.Text, onClick = { onMoveAnyway(mint.host) }, enabled = !state.busy) { Text(stringResource(R.string.notes_move_anyway)) }
        }
    }
}

/** The mark of a trusted mint (decorative: the "Trusted" label next to it says it in words). */
@Composable
internal fun TrustedIcon() {
    Icon(painterResource(DesignR.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
}

/** One note: the amount and its age; a status line only when it isn't simply in the balance. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(note: Note, state: NotesState, actions: NotesActions) {
    val selectable = NotesViewModel.selectable(note)
    val status = noteStatus(note)
    WalletListItem(
        leadingContent = if (state.selecting) ({
            WalletCheckbox(checked = note.id in state.selected, onCheckedChange = { actions.tap(note) }, enabled = selectable && !state.busy)
        }) else null,
        headlineContent = { Text(formatAmount(note.amountMsat), style = MaterialTheme.typography.bodyLarge) },
        supportingContent = status?.let { (icon, text) ->
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WalletListIcon(icon, size = ListIconSize.Inline)
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        trailingContent = { Text(recentTime(note.updatedAtMillis), style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.combinedClickable(
            enabled = !state.busy && (selectable || !state.selecting),
            onClick = { actions.tap(note) },
            onLongClick = { if (selectable) actions.longPress(note) },
        ),
    )
}

/** What's unusual about a note, if anything: its mark and its words. */
@Composable
private fun noteStatus(note: Note): Pair<Int, String>? = when {
    note.state == NoteState.Paying -> DesignR.drawable.ic_pending to stringResource(R.string.notes_state_paying)
    note.state == NoteState.Ambiguous -> DesignR.drawable.ic_unknown to stringResource(R.string.notes_state_ambiguous)
    note.certificate == null -> DesignR.drawable.ic_uncertified to stringResource(R.string.notes_uncertified)
    else -> null
}

@Composable
private fun SharedHeader() {
    HorizontalDivider()
    WalletListItem(
        headlineContent = { Text(stringResource(R.string.notes_shared_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() }) },
        supportingContent = { Text(stringResource(R.string.notes_shared_hint)) },
    )
}

@Composable
private fun SharedRow(note: Note, onOpen: (String) -> Unit) {
    WalletListItem(
        headlineContent = { Text(formatAmount(note.amountMsat)) },
        supportingContent = { Text(note.host) },
        trailingContent = { Text(recentTime(note.updatedAtMillis), style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.clickable { onOpen(note.id) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(state: NotesState, actions: NotesActions, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            val count = state.selected.size
            Text(
                if (count == 0) stringResource(R.string.notes_select_hint)
                else pluralStringResource(R.plurals.notes_selected, count, count, formatAmount(state.selectedMsat)),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WalletButton(kind = ButtonKind.Text, onClick = actions.combine, enabled = state.canCombine) { Text(stringResource(R.string.notes_combine)) }
                WalletButton(kind = ButtonKind.Text, onClick = actions.openMove, enabled = state.canAct) { Text(stringResource(R.string.notes_move)) }
                WalletButton(kind = ButtonKind.Text, onClick = actions.recheckSelected, enabled = state.canAct) { Text(stringResource(R.string.notes_recheck)) }
                WalletButton(kind = ButtonKind.Text, onClick = actions.stopSelecting, enabled = !state.busy) { Text(stringResource(R.string.notes_cancel)) }
            }
        }
    }
}

@Composable
internal fun Outcome(outcome: NotesOutcome, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            outcomeText(outcome),
            style = MaterialTheme.typography.bodyMedium,
            color = if (outcome.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        WalletButton(kind = ButtonKind.Text, onClick = onDismiss) { Text(stringResource(R.string.notes_ok)) }
    }
}

private val NotesOutcome.isProblem: Boolean
    get() = this is NotesOutcome.Failed || this is NotesOutcome.KeyChanged || this is NotesOutcome.AlreadySpent ||
        (this is NotesOutcome.Checked && (gone > 0 || unreachable.isNotEmpty()))

@Composable
private fun outcomeText(outcome: NotesOutcome): String = when (outcome) {
    is NotesOutcome.Combined -> stringResource(R.string.notes_combined, formatAmount(outcome.amountMsat))
    is NotesOutcome.Split -> stringResource(R.string.notes_split_done, formatAmount(outcome.firstMsat), formatAmount(outcome.secondMsat))
    is NotesOutcome.Moved -> stringResource(R.string.notes_moved, formatAmount(outcome.arrivedMsat), outcome.host)
    is NotesOutcome.Renewed -> stringResource(R.string.notes_renewed, formatAmount(outcome.amountMsat))
    is NotesOutcome.Checked -> buildList {
        if (outcome.gone > 0) add(pluralStringResource(R.plurals.notes_checked_gone, outcome.gone, outcome.gone))
        else if (outcome.unreachable.isEmpty()) add(pluralStringResource(R.plurals.notes_checked_ok, outcome.ok, outcome.ok))
        if (outcome.unreachable.isNotEmpty()) add(stringResource(R.string.notes_checked_unreachable, ListFormatter.getInstance().format(outcome.unreachable)))
    }.joinToString(" ")
    is NotesOutcome.KeyChanged -> stringResource(R.string.notes_key_changed, outcome.host)
    NotesOutcome.AlreadySpent -> stringResource(R.string.notes_already_spent)
    NotesOutcome.Pending -> stringResource(R.string.notes_pending)
    is NotesOutcome.Failed -> problemText(outcome.problem)
}
