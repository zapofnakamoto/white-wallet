package whitewallet.feature.notes

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.NoteFaceSize
import whitewallet.brand.ui.walletColors
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletNoteFace
import whitewallet.core.designsystem.WalletNoteGroupHeader
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.recentTime
import whitewallet.core.designsystem.showsWorking
import whitewallet.core.model.Note
import whitewallet.core.wallet.NoteAction

/**
 * One mint's notes on a page of their own: its name, total and labels, then every note as a row with a small bill, the biggest (or newest)
 * first. Sorting and selecting are here; Back leads to all mints.
 */
@Composable
internal fun MintPage(mint: MintNotes, state: NotesState, actions: NotesActions, backArrow: Boolean) {
    val working = showsWorking(state.busy)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "header") { MintPageHeader(mint, state, actions, backArrow) }
            if (working) item { WalletLinearProgress(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) }
            state.outcome
                ?.takeIf { state.open == null }
                ?.let { outcome -> item { Outcome(outcome, state.showMints, actions.dismissOutcome) } }
            item(key = "toolbar") { Toolbar(state, actions) }
            items(mint.notes, key = { it.id }) { note -> NoteRow(note, state, actions) }
            // Room for the selection bar, so the last note can scroll above it.
            if (state.selecting) item { Spacer(Modifier.height(120.dp)) }
        }
        if (state.selecting) SelectionBar(state, actions, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Back, then the mint's name, total, how many notes and its labels, drawn by the brand; without `mints.visible` the total and count only.
 * When collecting at the home mint left its notes here, why, and Move anyway.
 */
@Composable
private fun MintPageHeader(mint: MintNotes, state: NotesState, actions: NotesActions, backArrow: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (backArrow)
            IconButton(onClick = actions.closeMint) {
                Icon(painterResource(DesignR.drawable.ic_arrow_back), contentDescription = stringResource(DesignR.string.back))
            }
        val labels =
            listOfNotNull(
                // In the balance, as the total and the pile count them; the others say so on their rows.
                stringResource(R.string.notes_home_mint).takeIf { mint.isHome && state.showMints },
                stringResource(DesignR.string.notes_trusted).takeIf { mint.trusted && state.showMints },
                pluralStringResource(DesignR.plurals.notes_count, mint.inBalance.size, mint.inBalance.size),
            )
        WalletNoteGroupHeader(
            mint = mint.host.takeIf { state.showMints },
            host = mint.host,
            totalMsat = mint.totalMsat,
            home = mint.isHome,
            trusted = mint.trusted && state.showMints,
            labels = labels.joinToString(stringResource(DesignR.string.notes_detail_separator)),
            modifier = Modifier.weight(1f),
        )
    }
    mint.skipped
        ?.takeIf { NoteAction.Move in state.noteActions }
        ?.let { skipped ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.notes_group_not_moved, formatAmount(skipped.feeMsat), skipped.capPercent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                WalletButton(kind = ButtonKind.Text, onClick = { actions.moveAnyway(mint.host) }, enabled = !state.busy) {
                    Text(stringResource(R.string.notes_move_anyway))
                }
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
                    DropdownMenuItem(
                        text = { Text(stringResource(sortLabel(sort))) },
                        onClick = {
                            menu = false
                            actions.setSort(sort)
                        },
                    )
                }
            }
        }
        if (state.canSelect && !state.selecting)
            WalletButton(kind = ButtonKind.Text, onClick = actions.startSelecting, enabled = !state.busy) {
                Text(stringResource(R.string.notes_select))
            }
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

private fun sortLabel(sort: NoteSort) =
    when (sort) {
        NoteSort.Largest -> R.string.notes_sort_largest
        NoteSort.Newest -> R.string.notes_sort_newest
    }

/** The mark of a trusted mint (decorative: the "Trusted" label next to it says it in words). */
@Composable
internal fun TrustedIcon() {
    Icon(painterResource(DesignR.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
}

/**
 * One note, drawn by the brand as a small bill in its mint's colour (greyed out when it isn't in the balance), the amount and its age; a
 * status line only when it isn't simply in the balance. The row's tap, long-press and, while selecting, its checkbox state stay the app's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(note: Note, state: NotesState, actions: NotesActions) {
    val pickable = NotesViewModel.pickable(note)
    val status = noteStatus(note)
    val selected = (note.id in state.selected).takeIf { state.selecting }
    // A note its mint doesn't know is drawn as out of the balance, unless it is being picked for the archive.
    val enabled = NotesViewModel.selectable(note) || (state.selecting && pickable)
    WalletNoteFace(
        size = NoteFaceSize.Row,
        note.amountMsat,
        status = status,
        statusText = noteStatusText(status),
        statusIcon = noteStatusIcon(status),
        age = recentTime(note.updatedAtMillis),
        mint = note.host.takeIf { state.showMints },
        host = note.host,
        trusted = state.mints.any { it.host == note.host && it.trusted },
        selected = selected,
        enabled = enabled && !state.busy,
        colors = MaterialTheme.walletColors.note(state.isHomeMint(note.host)),
        certified = note.certificate != null,
        modifier =
            Modifier.combinedClickable(
                    enabled = !state.busy && (pickable || !state.selecting),
                    onClickLabel = stringResource(R.string.notes_details_open).takeIf { !state.selecting },
                    onClick = { actions.tap(note) },
                    onLongClick = { if (pickable && state.canSelect) actions.longPress(note) },
                )
                .semantics {
                    if (selected != null) {
                        role = Role.Checkbox
                        toggleableState = ToggleableState(selected)
                    }
                },
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
                val offered = state.noteActions
                // Notes their mint doesn't know: only archived, or asked about.
                val unknown = state.selectingUnknown
                if (unknown)
                    WalletButton(kind = ButtonKind.Text, onClick = actions.archiveSelected, enabled = state.canAct) {
                        Text(stringResource(R.string.notes_archive))
                    }
                if (NoteAction.Combine in offered && !unknown)
                    WalletButton(kind = ButtonKind.Text, onClick = actions.combine, enabled = state.canCombine) {
                        Text(stringResource(R.string.notes_combine))
                    }
                if (NoteAction.Move in offered && !unknown)
                    WalletButton(kind = ButtonKind.Text, onClick = actions.openMove, enabled = state.canAct) {
                        Text(stringResource(R.string.notes_move))
                    }
                if (NoteAction.Recheck in offered)
                    WalletButton(kind = ButtonKind.Text, onClick = actions.recheckSelected, enabled = state.canAct) {
                        Text(stringResource(DesignR.string.check_again))
                    }
                WalletButton(kind = ButtonKind.Text, onClick = actions.stopSelecting, enabled = !state.busy) {
                    Text(stringResource(DesignR.string.cancel))
                }
            }
        }
    }
}
