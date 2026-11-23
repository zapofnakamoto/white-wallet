package whitewallet.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import whitewallet.brand.ui.NoteFaceSize
import whitewallet.brand.ui.walletColors
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.ReceivedNoteRow
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletNoteFace
import whitewallet.core.designsystem.recentTime
import whitewallet.core.designsystem.showsWorking
import whitewallet.core.designsystem.waitsFor
import whitewallet.core.model.Note

/**
 * Everything that needs the user, on a page of its own, by mint: under each mint's name whether it isn't answering or its key changed, then
 * its notes it doesn't know or that wait for an answer (a tap opens a note's details and actions). Received links the wallet can't claim on
 * its own come last, with their own actions. Back leads to all mints.
 */
@Composable
internal fun AttentionPage(state: NotesState, actions: NotesActions) {
    val troubled = (state.unknownNotes + state.waitingNotes).groupBy { it.host }
    val hosts = state.mints.map { it.host }.filter { it in troubled || it in state.silentMints || it in state.keyChangedMints }
    val working = showsWorking(state.busy)
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.closeAttention) {
                    Icon(painterResource(DesignR.drawable.ic_arrow_back), contentDescription = stringResource(DesignR.string.back))
                }
                Text(
                    stringResource(R.string.notes_attention_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        if (working) item { WalletLinearProgress(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) }
        state.outcome?.takeIf { state.open == null }?.let { outcome -> item { Outcome(outcome, state.showMints, actions.dismissOutcome) } }
        hosts.forEachIndexed { index, host ->
            // Named, each mint under its name; unnamed, still apart from the one before.
            if (state.showMints) item(key = "mint $host") { Heading(host) }
            else if (index > 0) item(key = "apart $host") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            if (host in state.silentMints)
                item(key = "silent $host") { MintLine(stringResource(DesignR.string.mint_not_answering_unnamed)) }
            if (host in state.keyChangedMints)
                item(key = "key $host") {
                    MintLine(
                        stringResource(DesignR.string.mint_key_changed_nothing_sent),
                        actions.openMintDetails?.let { open -> { open(host) } },
                    )
                }
            items(troubled[host].orEmpty(), key = { it.id }) { note -> TroubledNoteRow(note, state, actions) }
        }
        val received = state.receivedNeedingUser
        if (received.isNotEmpty()) {
            item(key = "received") { Heading(stringResource(DesignR.string.received_title)) }
            items(received, key = { "received ${it.id}" }) { note ->
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
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** What is wrong with a mint itself; [onClick] leads to where the user can do something about it. */
@Composable
private fun MintLine(text: String, onClick: (() -> Unit)? = null) {
    Row(
        (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(painterResource(DesignR.drawable.ic_chevron_end), contentDescription = null)
    }
}

/** A note its mint doesn't know or that waits for an answer, as on its mint's page, with what is unusual about it in words. */
@Composable
private fun TroubledNoteRow(note: Note, state: NotesState, actions: NotesActions) {
    val status = noteStatus(note)
    WalletNoteFace(
        size = NoteFaceSize.Row,
        note.amountMsat,
        status = status,
        statusText = noteStatusText(status),
        statusIcon = noteStatusIcon(status),
        age = recentTime(note.updatedAtMillis),
        mint = note.host.takeIf { state.showMints },
        trusted = state.mints.any { it.host == note.host && it.trusted },
        // Drawn as on its mint's page: one its mint doesn't know as out of the balance.
        enabled = NotesViewModel.selectable(note) && !state.busy,
        colors = MaterialTheme.walletColors.note(state.colorIndex(note.host)),
        certified = note.certificate != null,
        modifier = Modifier.clickable(enabled = !state.busy, onClick = { actions.tap(note) }),
    )
}
