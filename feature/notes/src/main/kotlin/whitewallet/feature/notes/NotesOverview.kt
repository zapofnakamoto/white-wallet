package whitewallet.feature.notes

import android.icu.text.ListFormatter
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.NoteFacePlace
import whitewallet.brand.ui.NoteFaceSize
import whitewallet.brand.ui.NoteStatus
import whitewallet.brand.ui.Tone
import whitewallet.brand.ui.walletColors
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.core.designsystem.EmptyState
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.ReceivedNoteRow
import whitewallet.core.designsystem.WalletCard
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletNoteFace
import whitewallet.core.designsystem.WalletNotePile
import whitewallet.core.designsystem.WalletStatusScope
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.needsUser
import whitewallet.core.designsystem.recentTime
import whitewallet.core.designsystem.showsWorking
import whitewallet.core.designsystem.waitsFor
import whitewallet.core.model.Note

/**
 * All mints at a glance: the balance; what needs the user, only when anything does; each mint's notes as a pile of bills, swiped through
 * and tapped to open; then what isn't in the balance (received, handed out, the archive) and the note settings.
 */
@Composable
internal fun NotesOverview(state: NotesState, actions: NotesActions, scroll: ScrollState, pager: PagerState) {
    // Received links that wait for the user open their section by themselves.
    var receivedOpen by rememberSaveable { mutableStateOf(state.received.any { it.needsUser() }) }
    val received = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Summary(state)
        if (showsWorking(state.busy)) WalletLinearProgress(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        state.outcome?.takeIf { state.open == null }?.let { Outcome(it, state.showMints, actions.dismissOutcome) }
        if (state.mints.isEmpty() && state.shared.isEmpty() && state.received.isEmpty() && state.archivedCount == 0) {
            EmptyState(stringResource(R.string.notes_empty), anchor = Anchor.NotesEmpty) {
                PrimaryButton(stringResource(R.string.notes_receive), actions.openReceive)
            }
        }
        Attention(state, actions) {
            receivedOpen = true
            scope.launch { received.bringIntoView() }
        }
        if (state.mints.isNotEmpty()) MintPiles(state, actions, pager)
        OutsideBalance(state, actions, receivedOpen, { receivedOpen = !receivedOpen }, Modifier.bringIntoViewRequester(received))
        actions.openSettings?.let { SettingsRow(it) }
    }
}

/** The balance, and how many notes on how many mints make it up. */
@Composable
private fun Summary(state: NotesState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        WalletStatusScope(notesStatus(state)) { BrandDecoration(Anchor.NotesHeader) }
        Text(formatAmount(state.totalMsat), style = MaterialTheme.typography.headlineMedium)
        if (state.noteCount > 0) {
            val count = state.balanceNoteCount
            val parts = buildList {
                add(pluralStringResource(R.plurals.notes_count, count, count))
                if (state.showMints && state.mints.size > 1)
                    add(pluralStringResource(R.plurals.notes_mints, state.mints.size, state.mints.size))
            }
            Text(parts.joinToString(stringResource(R.string.notes_detail_separator)), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One thing that needs the user, in a line; [onClick] leads to it (null: the line says it all). */
private class AttentionLine(val text: String, val onClick: (() -> Unit)?)

/**
 * What needs the user, one short line each, only when anything does: notes their mint doesn't know, notes waiting for an answer, mints that
 * don't answer or whose key changed, received links the wallet can't claim on its own ([onReceived] opens them). Uncertified notes don't:
 * they are spent like any other.
 */
@Composable
private fun Attention(state: NotesState, actions: NotesActions, onReceived: () -> Unit) {
    val lines = buildList {
        state.unknownNotes
            .takeIf { it.isNotEmpty() }
            ?.let { notes ->
                add(AttentionLine(pluralStringResource(R.plurals.notes_archived_notes, notes.size, notes.size), openMintOf(notes, actions)))
            }
        state.waitingNotes
            .takeIf { it.isNotEmpty() }
            ?.let { notes ->
                add(
                    AttentionLine(
                        pluralStringResource(R.plurals.notes_attention_waiting, notes.size, notes.size),
                        openMintOf(notes, actions),
                    )
                )
            }
        state.silentMints
            .takeIf { it.isNotEmpty() }
            ?.let { hosts ->
                add(
                    AttentionLine(
                        if (state.showMints)
                            pluralStringResource(
                                R.plurals.notes_attention_not_answering,
                                hosts.size,
                                hosts.size,
                                ListFormatter.getInstance().format(hosts),
                            )
                        else stringResource(DesignR.string.mint_not_answering_unnamed),
                        hosts.singleOrNull()?.let { host -> { actions.openMint(host) } },
                    )
                )
            }
        state.keyChangedMints
            .takeIf { it.isNotEmpty() }
            ?.let { hosts ->
                add(
                    AttentionLine(
                        if (state.showMints)
                            pluralStringResource(
                                R.plurals.notes_attention_key_changed,
                                hosts.size,
                                hosts.size,
                                ListFormatter.getInstance().format(hosts),
                            )
                        else stringResource(DesignR.string.mint_key_changed_nothing_sent),
                        actions.openMintDetails?.let { open -> hosts.singleOrNull()?.let { host -> { open(host) } } },
                    )
                )
            }
        state.received
            .count { it.needsUser() }
            .takeIf { it > 0 }
            ?.let { count ->
                add(AttentionLine(pluralStringResource(R.plurals.notes_attention_received, count, count), onReceived))
            }
    }
    if (lines.isEmpty()) return
    WalletCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), tone = Tone.Warning) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(DesignR.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.notes_attention_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        lines.forEach { line ->
            val click = line.onClick
            Row(
                (if (click != null) Modifier.clickable(onClick = click) else Modifier)
                    .fillMaxWidth()
                    .padding(start = 44.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(line.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (click != null) Icon(painterResource(DesignR.drawable.ic_chevron_end), contentDescription = null)
            }
        }
        Box(Modifier.padding(bottom = 6.dp))
    }
}

/** Opens the page of the (first) mint holding [notes]. */
private fun openMintOf(notes: List<Note>, actions: NotesActions): (() -> Unit)? =
    notes.firstOrNull()?.host?.let { host -> { actions.openMint(host) } }

/**
 * Each mint's notes as a pile of bills, one mint at a time: swiped through, the next one peeking in at the edge, or moved by the arrows
 * (the dots say where). A tap opens the mint's notes.
 */
@Composable
private fun MintPiles(state: NotesState, actions: NotesActions, pager: PagerState) {
    val several = state.mints.size > 1
    HorizontalPager(
        pager,
        contentPadding = PaddingValues(horizontal = if (several) 40.dp else 16.dp),
        pageSpacing = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        key = { state.mints[it].host },
    ) { page ->
        val mint = state.mints[page]
        val bills = mint.inBalance
        val count = pluralStringResource(R.plurals.notes_count, bills.size, bills.size)
        val range =
            bills
                .takeIf { it.size > 1 && it.first().amountMsat != it.last().amountMsat }
                ?.let { stringResource(R.string.notes_range, formatAmount(it.last().amountMsat), formatAmount(it.first().amountMsat)) }
        // Without named mints, nothing tells one mint from the other: no labels either.
        val labels =
            listOfNotNull(
                stringResource(R.string.notes_home_mint).takeIf { mint.isHome && state.showMints },
                stringResource(R.string.notes_trusted).takeIf { mint.trusted && state.showMints },
            )
        WalletNotePile(
            mint = mint.host.takeIf { state.showMints },
            labels = labels.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.notes_detail_separator)),
            totalMsat = mint.totalMsat,
            detail = listOfNotNull(count, range).joinToString(stringResource(R.string.notes_detail_separator)),
            bills = bills.map { it.amountMsat },
            colors = MaterialTheme.walletColors.note(state.colorIndex(mint.host)),
            home = mint.isHome,
            trusted = mint.trusted && state.showMints,
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(onClickLabel = stringResource(R.string.notes_mint_open)) { actions.openMint(mint.host) }
                    .padding(vertical = 8.dp),
        )
    }
    if (several) PageControls(pager, state.mints.size)
}

/** Arrows to the previous and next mint, and a dot per mint; the dots are read out as "Mint 2 of 3". */
@Composable
private fun PageControls(pager: PagerState, count: Int) {
    val scope = rememberCoroutineScope()
    val current = pager.currentPage
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { scope.launch { pager.animateScrollToPage(current - 1) } }, enabled = current > 0) {
            Icon(
                painterResource(DesignR.drawable.ic_chevron_end),
                stringResource(R.string.notes_mint_previous),
                modifier = Modifier.rotate(180f),
            )
        }
        val position = stringResource(R.string.notes_mint_position, current + 1, count)
        Row(
            Modifier.clearAndSetSemantics {
                stateDescription = position
                contentDescription = position
            },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(count) { i ->
                val color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                Box(Modifier.size(8.dp).background(color, CircleShape))
            }
        }
        IconButton(onClick = { scope.launch { pager.animateScrollToPage(current + 1) } }, enabled = current < count - 1) {
            Icon(painterResource(DesignR.drawable.ic_chevron_end), stringResource(R.string.notes_mint_next))
        }
    }
}

/**
 * Money that isn't in the balance, apart from it: received links not claimed yet (opened by a tap, or by themselves when one waits for the
 * user), notes handed out (opened by a tap), and the archive.
 */
@Composable
private fun OutsideBalance(
    state: NotesState,
    actions: NotesActions,
    receivedOpen: Boolean,
    toggleReceived: () -> Unit,
    receivedModifier: Modifier,
) {
    if (state.received.isEmpty() && state.shared.isEmpty() && state.archivedCount == 0) return
    var sharedOpen by rememberSaveable { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(
        stringResource(R.string.notes_outside_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
    if (state.received.isNotEmpty()) {
        Section(
            stringResource(DesignR.string.received_title),
            amount(state.receivedMsat, state.received.size),
            receivedOpen,
            toggleReceived,
            receivedModifier,
        )
        if (receivedOpen) {
            Hint(stringResource(DesignR.string.received_hint))
            state.received.forEach { note ->
                ReceivedNoteRow(
                    note,
                    state.showMints,
                    amountHidden = false,
                    actions.received,
                    wait = note.waitsFor(state.offline, state.notAnsweringHosts),
                )
            }
        }
    }
    if (state.shared.isNotEmpty()) {
        Section(
            stringResource(DesignR.string.handed_out_title),
            amount(state.sharedMsat, state.shared.size),
            sharedOpen,
            { sharedOpen = !sharedOpen },
        )
        if (sharedOpen) {
            Hint(stringResource(R.string.notes_shared_hint))
            state.shared.forEach { note -> SharedRow(note, state.showMints, actions.openShared) }
        }
    }
    if (state.archivedCount > 0) ArchivedRow(state, actions.openArchive)
}

@Composable
private fun amount(msat: Long, count: Int): String =
    listOf(formatAmount(msat), pluralStringResource(R.plurals.notes_count, count, count))
        .joinToString(stringResource(R.string.notes_detail_separator))

/** A row that opens and closes what's below it: its title, what it holds, and the arrow that says which way it goes. */
@Composable
private fun Section(title: String, detail: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val state = stringResource(if (open) R.string.notes_section_open else R.string.notes_section_closed)
    WalletListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = {
            Icon(
                painterResource(DesignR.drawable.ic_arrow_drop_down),
                contentDescription = null,
                modifier = if (open) Modifier.rotate(180f) else Modifier,
            )
        },
        modifier = modifier.clickable(onClick = onToggle).semantics { stateDescription = state },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A handed-out note, drawn by the brand like the others (a greyed-out bill: it isn't in the balance); it waits for its recipient. */
@Composable
private fun SharedRow(note: Note, showMints: Boolean, onOpen: (String) -> Unit) {
    WalletNoteFace(
        size = NoteFaceSize.Row,
        note.amountMsat,
        modifier = Modifier.clickable { onOpen(note.id) },
        status = NoteStatus.Waiting,
        age = recentTime(note.updatedAtMillis),
        mint = note.host.takeIf { showMints },
        place = NoteFacePlace.Shared,
    )
}

/**
 * Opens the archive: received links the wallet no longer tries, and notes their mint doesn't know that the user archived. Only there when
 * it holds any.
 */
@Composable
private fun ArchivedRow(state: NotesState, onOpen: () -> Unit) {
    WalletListItem(
        headlineContent = { Text(stringResource(R.string.archive_title)) },
        supportingContent = {
            Column {
                val links = state.archivedLinks
                val notes = state.archivedNotes
                if (links > 0) Text(pluralStringResource(R.plurals.notes_archived_links, links, links))
                if (notes > 0) Text(pluralStringResource(R.plurals.notes_archived_notes, notes, notes))
            }
        },
        trailingContent = { Icon(painterResource(DesignR.drawable.ic_chevron_end), contentDescription = null) },
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.notes_archived_open), onClick = onOpen),
    )
}

/** The note settings (strategy, collecting at the home mint, auto-rotate), last on the view. */
@Composable
private fun SettingsRow(onOpen: () -> Unit) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    WalletListItem(
        headlineContent = { Text(stringResource(R.string.notes_settings)) },
        leadingContent = { Icon(painterResource(DesignR.drawable.ic_tune), contentDescription = null) },
        trailingContent = { Icon(painterResource(DesignR.drawable.ic_chevron_end), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}
