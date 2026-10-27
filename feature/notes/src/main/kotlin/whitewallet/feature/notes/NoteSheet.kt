package whitewallet.feature.notes

import androidx.compose.foundation.layout.Arrangement
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
import whitewallet.core.wallet.ServiceMoveQuote
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.WalletAssistChip
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletSheet
import whitewallet.core.designsystem.WalletTextField

/** One note's details and what can be done with it: share it whole, split, recheck, renew. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NoteSheet(note: Note, state: NotesState, actions: NotesActions) {
    WalletSheet(onDismissRequest = actions.close) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatAmount(note.amountMsat), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.notes_sheet_where, note.host, recentTime(note.updatedAtMillis)), style = MaterialTheme.typography.bodyMedium)
            if (state.openTrusted) {
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WalletButton(kind = ButtonKind.Tonal, onClick = { actions.share(note.id) }, enabled = enabled) { Text(stringResource(R.string.notes_share)) }
                WalletButton(kind = ButtonKind.Secondary, onClick = { actions.openSplit(note.id) }, enabled = enabled) { Text(stringResource(R.string.notes_split)) }
                WalletButton(kind = ButtonKind.Secondary, onClick = { actions.recheck(note.id) }, enabled = enabled) { Text(stringResource(R.string.notes_recheck)) }
                WalletButton(kind = ButtonKind.Secondary, onClick = { actions.renew(note.id) }, enabled = enabled) { Text(stringResource(R.string.notes_renew)) }
            }
            if (state.busy) WalletLinearProgress(Modifier.fillMaxWidth())
            state.outcome?.let { Outcome(it, actions.dismissOutcome) }
            Text(stringResource(R.string.notes_share_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.notes_renew_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    d.quote is ServiceMoveQuote.Ready -> {
                        Text(stringResource(R.string.notes_move_quote, formatAmount(d.quote.amountMsat), formatAmount(d.quote.expectedMsat), d.quote.toHost))
                        Text(
                            if (d.quote.feeMsat > 0) stringResource(R.string.notes_fee, formatAmount(d.quote.feeMsat)) else stringResource(R.string.notes_no_fee),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    d.quote is ServiceMoveQuote.Invalid -> Text(
                        quoteProblemText(d.quote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            WalletButton(kind = ButtonKind.Text, onClick = actions.move, enabled = !d.quoting && d.quote is ServiceMoveQuote.Ready) { Text(stringResource(R.string.notes_move)) }
        },
        dismissButton = { WalletButton(kind = ButtonKind.Text, onClick = actions.dismissDialog) { Text(stringResource(R.string.notes_cancel)) } },
    )
}

@Composable
private fun quoteProblemText(quote: ServiceMoveQuote.Invalid): String {
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
        NoteProblem.DifferentServices -> R.string.notes_problem_different_mints
        NoteProblem.TooFewNotes -> R.string.notes_problem_too_few
        NoteProblem.InvalidAmount -> R.string.notes_problem_invalid_amount
        NoteProblem.SameService -> R.string.notes_problem_same_mint
        NoteProblem.ServiceInvalid -> R.string.notes_problem_mint_invalid
        NoteProblem.Unreachable -> R.string.notes_problem_unreachable
        NoteProblem.BelowMinimum -> R.string.notes_problem_below_minimum
        NoteProblem.AboveMaximum -> R.string.notes_problem_above_maximum
        NoteProblem.InvoiceMismatch -> R.string.notes_problem_invoice_mismatch
        NoteProblem.Refused -> R.string.notes_problem_refused
        NoteProblem.PaymentFailed -> R.string.notes_problem_payment_failed
        NoteProblem.Declined -> R.string.notes_problem_declined
    },
)
