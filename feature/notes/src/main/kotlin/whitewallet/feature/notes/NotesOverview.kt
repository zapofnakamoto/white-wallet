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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.NoteFacePlace
import whitewallet.brand.ui.NoteFaceSize
import whitewallet.brand.ui.NoteStatus
import whitewallet.brand.ui.inBalance
import whitewallet.brand.ui.walletColors
import whitewallet.core.designsystem.AmountText
import whitewallet.core.designsystem.AttentionCard
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.core.designsystem.EmptyState
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.ReceivedNoteRow
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletNoteFace
import whitewallet.core.designsystem.WalletNotePile
import whitewallet.core.designsystem.WalletStatusScope
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.recentTime
import whitewallet.core.designsystem.showsWorking
import whitewallet.core.designsystem.waitsFor
import whitewallet.core.model.Note
import whitewallet.core.model.NoteState

/**
 * All mints at a glance: the balance; what needs the user, only when anything does; each mint's notes as a pile of bills, swiped through
 * and tapped to open; then what isn't in the balance (received, handed out, the archive) and the note settings.
 */
@Composable
internal fun NotesOverview(state: NotesState, actions: NotesActions, overview: OverviewState) {
    Column(Modifier.fillMaxSize().verticalScroll(overview.scroll)) {
        Summary(state)
        if (showsWorking(state.busy)) WalletLinearProgress(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        state.outcome?.takeIf { state.open == null }?.let { Outcome(it, state.showMints, actions.dismissOutcome) }
        if (state.empty) {
            EmptyState(stringResource(R.string.notes_empty), anchor = Anchor.NotesEmpty) {
                PrimaryButton(stringResource(R.string.notes_receive), actions.openReceive)
            }
        }
        Attention(state, actions.openAttention)
        if (state.mints.isNotEmpty()) MintPiles(state, actions, overview.pager)
        OutsideBalance(state, actions, overview)
        actions.openSettings?.let { SettingsRow(it) }
    }
}

/**
 * What the overview keeps while a mint's page or what needs the user is open: where it was scrolled and swiped to, and which sections are
 * open.
 */
internal class OverviewState(
    val scroll: ScrollState,
    val pager: PagerState,
    val receivedOpen: MutableState<Boolean>,
    val sharedOpen: MutableState<Boolean>,
)

/** The balance, and how many notes on how many mints make it up. */
@Composable
private fun Summary(state: NotesState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        WalletStatusScope(notesStatus(state)) { BrandDecoration(Anchor.NotesHeader) }
        // The balance as the wallet tab shows it, the brand's amount.
        AmountText(state.totalMsat)
        // What the balance holds: no "0 notes" while every note is set aside (they are in the needs-attention card).
        if (state.balanceNoteCount > 0) {
            val count = state.balanceNoteCount
            val parts = buildList {
                add(pluralStringResource(DesignR.plurals.notes_count, count, count))
                if (state.showMints && state.mints.size > 1)
                    add(pluralStringResource(R.plurals.notes_mints, state.mints.size, state.mints.size))
            }
            Text(parts.joinToString(stringResource(DesignR.string.notes_detail_separator)), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * What needs the user, one short line each, only when anything does: notes their mint doesn't know, notes waiting for an answer, mints that
 * don't answer or whose key changed, received links the wallet can't claim on its own. Uncertified notes don't: they are spent like any
 * other. [onOpen] shows all of it on a page of its own, by mint.
 */
@Composable
private fun Attention(state: NotesState, onOpen: () -> Unit) {
    if (!state.needsAttention) return
    // Each problem as a title and what it means, the most important first: the card shows that one, and counts the others.
    val problems =
        buildList<Pair<String, String>> {
            state.keyChangedMints
                .takeIf { it.isNotEmpty() }
                ?.let { hosts ->
                    add(
                        (if (state.showMints)
                            pluralStringResource(
                                R.plurals.notes_attention_key_changed,
                                hosts.size,
                                hosts.size,
                                ListFormatter.getInstance().format(hosts),
                            )
                        else stringResource(DesignR.string.key_mismatch_short)) to stringResource(DesignR.string.attention_key_changed_line)
                    )
                }
            state.unknownNotes
                .takeIf { it.isNotEmpty() }
                ?.let { notes ->
                    val hosts = notes.map { it.host }.distinct()
                    add(
                        when {
                            !state.showMints -> pluralStringResource(R.plurals.notes_attention_unknown_unnamed, notes.size, notes.size)
                            hosts.size == 1 ->
                                pluralStringResource(R.plurals.notes_attention_unknown_at, notes.size, notes.size, hosts.single())
                            else -> pluralStringResource(R.plurals.notes_attention_unknown, notes.size, notes.size)
                        } to stringResource(DesignR.string.attention_unknown_line, formatAmount(notes.sumOf { it.amountMsat }))
                    )
                }
            state.silentMints
                .takeIf { it.isNotEmpty() }
                ?.let { hosts ->
                    add(
                        (if (state.showMints)
                            pluralStringResource(
                                R.plurals.notes_attention_not_answering,
                                hosts.size,
                                hosts.size,
                                ListFormatter.getInstance().format(hosts),
                            )
                        else stringResource(DesignR.string.mint_not_answering_unnamed)) to
                            stringResource(
                                if (hosts.any(state::isHomeMint)) DesignR.string.attention_not_answering_home_line
                                else DesignR.string.attention_not_answering_line
                            )
                    )
                }
            state.waitingNotes
                .takeIf { it.isNotEmpty() }
                ?.let { notes ->
                    add(
                        pluralStringResource(R.plurals.notes_attention_waiting, notes.size, notes.size) to
                            stringResource(DesignR.string.attention_waiting_line)
                    )
                }
            state.receivedNeedingUser.size
                .takeIf { it > 0 }
                ?.let { count ->
                    add(
                        pluralStringResource(R.plurals.notes_attention_received, count, count) to
                            stringResource(DesignR.string.attention_received_line)
                    )
                }
        }
    val (title, line) = problems.firstOrNull() ?: return
    AttentionCard(
        title,
        if (problems.size == 1) line else pluralStringResource(DesignR.plurals.attention_more, problems.size - 1, problems.size - 1),
        stringResource(DesignR.string.attention_show),
        onOpen,
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Each mint's notes as a pile of bills, one mint at a time: swiped through, the next one peeking in at the edge, or moved by the arrows
 * (the dots say where). A tap opens the mint's notes.
 */
@Composable
private fun MintPiles(state: NotesState, actions: NotesActions, pager: PagerState) {
    val several = state.mints.size > 1
    // One scale for every mint's bills: a big note looks big next to another mint's small ones.
    val largest = state.mints.maxOf { mint -> mint.notes.maxOfOrNull { it.amountMsat } ?: 0 }
    HorizontalPager(
        pager,
        contentPadding = PaddingValues(horizontal = if (several) 28.dp else 16.dp),
        pageSpacing = 12.dp,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        key = { state.mints[it].host },
    ) { page ->
        val mint = state.mints[page]
        val counted = mint.inBalance
        // Behind those in the balance, greyed out: the notes out of it (unknown to the mint, waiting for an answer, being paid).
        val bills = counted + mint.notes.filter { it.state != NoteState.Outstanding }.sortedByDescending { it.amountMsat }
        val statuses = bills.map(::noteStatus)
        val range =
            counted
                .takeIf { it.size > 1 && it.first().amountMsat != it.last().amountMsat }
                ?.let { stringResource(R.string.notes_range, formatAmount(it.last().amountMsat), formatAmount(it.first().amountMsat)) }
        // With none in the balance: what is held up there, out of it (not what is being paid: that is on its way out), and why.
        val heldMsat = bills.filter { it.state == NoteState.Unknown || it.state == NoteState.Ambiguous }.sumOf { it.amountMsat }
        val total = if (counted.isNotEmpty()) mint.totalMsat else heldMsat.takeIf { it > 0 }
        val outsideBalance = stringResource(R.string.notes_outside_title).takeIf { counted.isEmpty() && total != null }
        // Without named mints, nothing tells one mint from the other: no labels but the count, or why none counts. Why comes first, so
        // a narrow card cuts the home and trusted labels (its mark says home) rather than it.
        val kind =
            listOfNotNull(
                stringResource(R.string.notes_home_mint).takeIf { mint.isHome && state.showMints },
                stringResource(DesignR.string.notes_trusted).takeIf { mint.trusted && state.showMints },
            )
        val labels =
            if (counted.isNotEmpty()) kind + pluralStringResource(DesignR.plurals.notes_count, counted.size, counted.size)
            else listOfNotNull(outsideReason(bills), outsideMore(bills)) + kind
        WalletNotePile(
            mint = mint.host.takeIf { state.showMints },
            host = mint.host,
            labels = labels.joinToString(stringResource(DesignR.string.notes_detail_separator)),
            totalMsat = total,
            range = range,
            outsideBalance = outsideBalance,
            bills = bills.map { it.amountMsat },
            certified = bills.map { it.certificate != null },
            statuses = statuses,
            frontIcon = statuses.firstOrNull()?.takeIf { !it.inBalance }?.let(::noteStatusIcon),
            largestMsat = largest,
            colors = MaterialTheme.walletColors.note(mint.isHome),
            home = mint.isHome,
            trusted = mint.trusted && state.showMints,
            onClick = { actions.openMint(mint.host) },
            onClickLabel = stringResource(R.string.notes_mint_open),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (several) PageControls(pager, state.mints.size)
}

/**
 * The notes out of the balance, by why, the most important first, as the needs-attention card has them: unknown to the mint, waiting for an
 * answer, being paid; each with how it is said with how many ("2 notes unknown to the mint").
 */
private fun outsideGroups(notes: List<Note>): List<Pair<Int, List<Note>>> =
    listOf(
            R.plurals.notes_pile_unknown to NoteState.Unknown,
            R.plurals.notes_attention_waiting to NoteState.Ambiguous,
            R.plurals.notes_pile_paying to NoteState.Paying,
        )
        .map { (words, s) -> words to notes.filter { it.state == s } }
        .filter { it.second.isNotEmpty() }

/** Why the first of [outsideGroups] is out of the balance, with how many; null without any. */
@Composable
private fun outsideReason(notes: List<Note>): String? =
    outsideGroups(notes).firstOrNull()?.let { (words, group) -> pluralStringResource(words, group.size, group.size) }

/** How many more notes are out of the balance for another reason ("1 more"); null without any. */
@Composable
private fun outsideMore(notes: List<Note>): String? {
    val more = outsideGroups(notes).drop(1).sumOf { it.second.size }
    return if (more > 0) pluralStringResource(R.plurals.notes_pile_more, more, more) else null
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
    overview: OverviewState,
) {
    if (state.received.isEmpty() && state.handedOutCount == 0 && state.archivedCount == 0) return
    var receivedOpen by overview.receivedOpen
    var sharedOpen by overview.sharedOpen
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(
        stringResource(R.string.notes_outside_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
    if (state.received.isNotEmpty()) {
        Section(
            stringResource(DesignR.string.received_title),
            // What may still come in, summed and counted alike; links spent by someone else are only listed.
            state.receivedPending.size.takeIf { it > 0 }?.let { amount(state.receivedMsat, it) }
                ?: pluralStringResource(DesignR.plurals.notes_count, state.received.size, state.received.size),
            receivedOpen,
            { receivedOpen = !receivedOpen },
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
    if (state.handedOutCount > 0) {
        Section(
            stringResource(DesignR.string.handed_out_title),
            amount(state.handedOutMsat, state.handedOutCount),
            sharedOpen,
            { sharedOpen = !sharedOpen },
        )
        if (sharedOpen) {
            if (state.shared.isNotEmpty()) Hint(stringResource(R.string.notes_shared_hint))
            state.shared.forEach { note -> SharedRow(note, state.showMints, actions.openShared) }
            state.handedOutUnknown.forEach { note -> HandedOutUnknownRow(note, state.showMints, actions.tap) }
        }
    }
    if (state.archivedCount > 0) ArchivedRow(state, actions.openArchive)
}

@Composable
private fun amount(msat: Long, count: Int): String =
    listOf(formatAmount(msat), pluralStringResource(DesignR.plurals.notes_count, count, count))
        .joinToString(stringResource(DesignR.string.notes_detail_separator))

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
        modifier =
            modifier.clickable(onClick = onToggle).semantics {
                stateDescription = state
                // Spoken as a section that opens and closes, not as a button that says "open".
                val toggle = {
                    onToggle()
                    true
                }
                if (open) collapse(action = toggle) else expand(action = toggle)
            },
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
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.notes_details_open)) { onOpen(note.id) },
        status = NoteStatus.Waiting,
        certified = note.certificate != null,
        age = recentTime(note.updatedAtMillis),
        mint = note.host.takeIf { showMints },
        host = note.host,
        place = NoteFacePlace.Shared,
    )
}

/**
 * A handed-out note its mint doesn't know: nobody can claim it until the mint knows it again. It opens its details (check again, archive),
 * not its QR code.
 */
@Composable
private fun HandedOutUnknownRow(note: Note, showMints: Boolean, onOpen: (Note) -> Unit) {
    WalletNoteFace(
        size = NoteFaceSize.Row,
        note.amountMsat,
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.notes_details_open)) { onOpen(note) },
        status = NoteStatus.Unknown,
        certified = note.certificate != null,
        statusText = noteStatusText(NoteStatus.Unknown, handedOut = true),
        statusIcon = noteStatusIcon(NoteStatus.Unknown),
        age = recentTime(note.updatedAtMillis),
        mint = note.host.takeIf { showMints },
        host = note.host,
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
