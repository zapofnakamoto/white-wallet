package whitewallet.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import whitewallet.brand.NoteStrategy
import whitewallet.core.designsystem.LocalAmountUnit
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.recentTime
import whitewallet.core.model.AmountUnit
import whitewallet.core.model.Note
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.NoteProblem
import whitewallet.core.wallet.MintMoveQuote
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.wallet.NoteAction
import whitewallet.core.wallet.SimulatedFailure
import whitewallet.core.designsystem.WalletAssistChip
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCard
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.R as DesignR
import whitewallet.brand.ui.Tone
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import whitewallet.core.designsystem.WalletFilterChip
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletSheet
import whitewallet.core.designsystem.WalletTextField
import whitewallet.brand.ui.NoteFaceSize
import whitewallet.core.designsystem.WalletNoteFace

/** One note's details and what the brand lets users do with it: share it whole, split, recheck, renew. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NoteSheet(note: Note, state: NotesState, actions: NotesActions) {
    // Opens fully, and an action's outcome scrolls into view: it comes up below the buttons, where a half-open sheet
    // or a small phone would hide it.
    val outcomeView = remember { BringIntoViewRequester() }
    LaunchedEffect(state.outcome) { if (state.outcome != null) outcomeView.bringIntoView() }
    WalletSheet(onDismissRequest = actions.close, openFully = true) {
        // Scrolls: a large note face, the hints and, for developers, more below don't fit a small phone.
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WalletNoteFace(
                NoteFaceSize.Large,
                note.amountMsat,
                Modifier.fillMaxWidth(),
                status = noteStatus(note),
                statusText = noteStatusText(noteStatus(note)),
                age = recentTime(note.updatedAtMillis),
                mint = note.host.takeIf { state.showMints },
                trusted = state.openTrusted,
                enabled = NotesViewModel.selectable(note) && !state.busy,
            )
            Text(
                if (state.showMints) stringResource(R.string.notes_sheet_where, note.host, recentTime(note.updatedAtMillis)) else recentTime(note.updatedAtMillis),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.showMints && state.openTrusted) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrustedIcon()
                    Text(stringResource(R.string.notes_trusted), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                stringResource(
                    when {
                        note.state == NoteState.Paying -> R.string.notes_state_paying
                        note.state == NoteState.Ambiguous -> R.string.notes_state_ambiguous
                        note.certificate != null -> R.string.notes_certified
                        else -> R.string.notes_uncertified
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val enabled = NotesViewModel.selectable(note) && !state.busy
            val offered = state.noteActions
            if (offered.any { !it.bulk } || NoteAction.Recheck in offered) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (NoteAction.Share in offered) WalletButton(kind = ButtonKind.Tonal, onClick = { actions.share(note.id) }, enabled = enabled) { Text(stringResource(R.string.notes_share)) }
                    if (NoteAction.Split in offered) WalletButton(kind = ButtonKind.Secondary, onClick = { actions.openSplit(note.id) }, enabled = enabled) { Text(stringResource(R.string.notes_split)) }
                    if (NoteAction.Recheck in offered) WalletButton(kind = ButtonKind.Secondary, onClick = { actions.recheck(note.id) }, enabled = NotesViewModel.recheckable(note) && !state.busy) { Text(stringResource(R.string.notes_recheck)) }
                    if (NoteAction.Renew in offered) WalletButton(kind = ButtonKind.Secondary, onClick = { actions.renew(note.id) }, enabled = enabled) { Text(stringResource(R.string.notes_renew)) }
                }
            }
            if (state.failuresOffered) SimulatedFailureChips(state.simulatedFailure, actions.simulateFailure)
            if (state.busy) WalletLinearProgress(Modifier.fillMaxWidth())
            state.outcome?.let { Box(Modifier.bringIntoViewRequester(outcomeView)) { Outcome(it, state.showMints, actions.dismissOutcome) } }
            // The share hint mentions splitting, so it needs both.
            if (NoteAction.Share in offered && NoteAction.Split in offered) {
                Text(stringResource(R.string.notes_share_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (NoteAction.Renew in offered) {
                Text(stringResource(R.string.notes_renew_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.developerMode && note.purpose != null && note.index != null) {
                Text(
                    stringResource(R.string.notes_dev_key, note.purpose!!, note.index!!, note.id.take(8) + "…" + note.id.takeLast(8)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Debug builds in developer mode: makes the next change to notes fail once, as chosen (see [SimulatedFailure]), to
 * see its failure screens. Tapping the chosen one again takes it back. Framed and marked as for developers, so it
 * is never mistaken for part of the app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimulatedFailureChips(chosen: SimulatedFailure.Kind?, onChoose: (SimulatedFailure.Kind?) -> Unit) = WalletCard(tone = Tone.Warning) {
    Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(DesignR.drawable.ic_developer), contentDescription = null, Modifier.size(18.dp))
        Text(stringResource(R.string.notes_dev_section), style = MaterialTheme.typography.labelLarge)
    }
    Text(stringResource(R.string.notes_dev_fail), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
    FlowRow(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SimulatedFailure.Kind.entries.forEach { kind ->
            WalletFilterChip(
                selected = kind == chosen,
                onClick = { onChoose(kind.takeIf { it != chosen }) },
                label = {
                    Text(stringResource(when (kind) {
                        SimulatedFailure.Kind.Unreachable -> R.string.notes_dev_fail_unreachable
                        SimulatedFailure.Kind.Refused -> R.string.notes_dev_fail_refused
                        SimulatedFailure.Kind.AnswerLost -> R.string.notes_dev_fail_lost
                    }))
                },
            )
        }
    }
}

@Composable
internal fun SplitDialog(d: NotesDialog.Split, undoneBy: NoteStrategy?, actions: NotesActions) {
    WalletDialog(
        onDismissRequest = actions.dismissDialog,
        title = { Text(stringResource(R.string.notes_split_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WalletTextField(
                    value = d.amountText,
                    onValueChange = actions.splitAmount,
                    label = { Text(stringResource(if (LocalAmountUnit.current == AmountUnit.Bitcoin) R.string.notes_split_amount_bitcoin else R.string.notes_split_amount_sats)) },
                    supportingText = { Text(stringResource(R.string.notes_split_range, formatAmount(d.minMsat), formatAmount(d.limits.maxMsat))) },
                    isError = d.amountText.isNotEmpty() && d.amountMsat == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                d.amountMsat?.let { amount ->
                    Text(stringResource(R.string.notes_split_preview, formatAmount(amount), formatAmount(d.limits.maxMsat + 1 - amount)))
                }
                Text(
                    if (d.limits.feeMsat > 0) stringResource(R.string.notes_fee, formatAmount(d.limits.feeMsat)) else stringResource(R.string.notes_no_fee),
                    style = MaterialTheme.typography.bodySmall,
                )
                undoneBy?.let { Text(stringResource(R.string.notes_split_switches_manual, stringResource(it.title)), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { WalletButton(kind = ButtonKind.Text, onClick = actions.split, enabled = d.amountMsat != null) { Text(stringResource(R.string.notes_split)) } },
        dismissButton = { WalletButton(kind = ButtonKind.Text, onClick = actions.dismissDialog) { Text(stringResource(R.string.notes_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoveDialog(d: NotesDialog.Move, state: NotesState, actions: NotesActions) {
    val from = state.mints.firstOrNull { m -> m.notes.any { it.id in state.selected } }?.host
    WalletDialog(
        onDismissRequest = actions.dismissDialog,
        title = { Text(stringResource(R.string.notes_move_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.notes_move_hint), style = MaterialTheme.typography.bodySmall)
                WalletTextField(
                    value = d.target,
                    onValueChange = actions.moveTarget,
                    label = { Text(stringResource(R.string.notes_move_target)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                val suggestions = state.knownHosts.filter { it != from && it != d.target }
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { host -> WalletAssistChip(onClick = { actions.moveTarget(host) }, label = { Text(host) }) }
                    }
                }
                when {
                    d.quoting -> WalletLinearProgress(Modifier.fillMaxWidth())
                    d.quote is MintMoveQuote.Ready -> {
                        Text(stringResource(R.string.notes_move_quote, formatAmount(d.quote.amountMsat), formatAmount(d.quote.expectedMsat), d.quote.toHost))
                        Text(
                            if (d.quote.feeMsat > 0) stringResource(R.string.notes_fee, formatAmount(d.quote.feeMsat)) else stringResource(R.string.notes_no_fee),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    d.quote is MintMoveQuote.Invalid -> Text(
                        quoteProblemText(d.quote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            WalletButton(kind = ButtonKind.Text, onClick = actions.move, enabled = !d.quoting && d.quote is MintMoveQuote.Ready) { Text(stringResource(R.string.notes_move)) }
        },
        dismissButton = { WalletButton(kind = ButtonKind.Text, onClick = actions.dismissDialog) { Text(stringResource(R.string.notes_cancel)) } },
    )
}

@Composable
private fun quoteProblemText(quote: MintMoveQuote.Invalid): String {
    val limit = quote.limitMsat
    return when {
        quote.problem == NoteProblem.BelowMinimum && limit != null -> stringResource(R.string.notes_problem_below_minimum_amount, formatAmount(limit))
        quote.problem == NoteProblem.AboveMaximum && limit != null -> stringResource(R.string.notes_problem_above_maximum_amount, formatAmount(limit))
        else -> problemText(quote.problem)
    }
}

@Composable
internal fun problemText(problem: NoteProblem): String = stringResource(
    when (problem) {
        NoteProblem.NotFound -> R.string.notes_problem_not_found
        NoteProblem.DifferentMints -> R.string.notes_problem_different_mints
        NoteProblem.TooFewNotes -> R.string.notes_problem_too_few
        NoteProblem.InvalidAmount -> R.string.notes_problem_invalid_amount
        NoteProblem.SameMint -> R.string.notes_problem_same_mint
        NoteProblem.MintInvalid -> R.string.notes_problem_mint_invalid
        NoteProblem.Unreachable -> R.string.notes_problem_unreachable
        NoteProblem.BelowMinimum -> R.string.notes_problem_below_minimum
        NoteProblem.AboveMaximum -> R.string.notes_problem_above_maximum
        NoteProblem.InvoiceMismatch -> R.string.notes_problem_invoice_mismatch
        NoteProblem.Refused -> R.string.notes_problem_refused
        NoteProblem.PaymentFailed -> R.string.notes_problem_payment_failed
        NoteProblem.Declined -> R.string.notes_problem_declined
    },
)
