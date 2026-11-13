package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.UnclaimedReason

/** Where a received note stands, in the user's language: why it isn't claimed yet, or why it can't be. */
@Composable
fun receivedNoteStatus(note: ReceivedNote): String =
    when (note.state) {
        ReceivedState.Waiting -> stringResource(R.string.received_waiting)
        ReceivedState.Locked ->
            note.lockedUntilMillis?.let { stringResource(R.string.received_locked_until, recentTime(it)) }
                ?: stringResource(R.string.received_locked)
        ReceivedState.KeyChanged -> stringResource(R.string.received_key_changed)
        ReceivedState.LightningOnly ->
            stringResource(
                when (note.unclaimedReason) {
                    UnclaimedReason.NoSigningKey -> R.string.received_lightning_no_key
                    UnclaimedReason.Refused -> R.string.received_lightning_refused
                    else -> R.string.received_lightning_not_a_note
                }
            )
        ReceivedState.Claiming -> stringResource(R.string.received_claiming)
        ReceivedState.Melting -> stringResource(R.string.received_melting)
        ReceivedState.Claimed -> stringResource(R.string.received_claimed)
        ReceivedState.Gone -> stringResource(R.string.received_gone)
        ReceivedState.Archived -> archived(note)
    }

/** Why the wallet stopped trying it: for how long it failed (the brand's `notes.received.giveUpAfter`, as it was then), or the user. */
@Composable
private fun archived(note: ReceivedNote): String {
    val (plain, hours, days) =
        when (note.unclaimedReason) {
            UnclaimedReason.ByUser -> return stringResource(R.string.received_archived_by_you)
            UnclaimedReason.Unreachable ->
                Triple(R.string.received_unreachable, R.plurals.received_unreachable_hours, R.plurals.received_unreachable_days)
            // Never archived for a missing signing key or an answer not for a note: such a note is only received over Lightning.
            UnclaimedReason.NoSigningKey,
            UnclaimedReason.NotANote,
            UnclaimedReason.Refused,
            null -> Triple(R.string.received_refused, R.plurals.received_refused_hours, R.plurals.received_refused_days)
        }
    val since = note.failingSinceMillis ?: return stringResource(plain)
    val tried = WholeDuration.atLeast(note.updatedAtMillis - since, listOf(WholeDuration.Unit.Hours, WholeDuration.Unit.Days))
    return when (tried.unit) {
        WholeDuration.Unit.Days -> pluralStringResource(days, tried.count, tried.count)
        else -> pluralStringResource(hours, tried.count, tried.count)
    }
}

/**
 * A received note in a list: what it is worth as far as known (not with [amountHidden]), on which mint ([showMint]), and where it stands.
 * One the wallet still tries can be archived ([onArchive]); one spent by someone else can be removed ([onRemove]); one that can only be
 * received over Lightning opens in Claim ([onReceive]). An archived one is only in the archive, which offers more (see its screen).
 */
@Composable
fun ReceivedNoteRow(
    note: ReceivedNote,
    showMint: Boolean,
    amountHidden: Boolean,
    onRemove: (String) -> Unit,
    onArchive: (String) -> Unit,
    modifier: Modifier = Modifier,
    onReceive: (String) -> Unit = {},
    trailing: (@Composable () -> Unit)? = receivedNoteAction(note, onRemove, onArchive, onReceive),
) {
    val status = receivedNoteStatus(note)
    val amount =
        when {
            amountHidden -> null
            note.amountMsat != null -> formatAmount(note.amountMsat!!)
            note.declaredMsat != null -> stringResource(R.string.received_unconfirmed, formatAmount(note.declaredMsat!!))
            else -> stringResource(R.string.received_unknown_amount)
        }
    WalletListItem(
        headlineContent = { Text(amount ?: status) },
        supportingContent = {
            Column {
                if (amount != null) Text(status)
                if (showMint) Text(stringResource(R.string.received_on, note.host), style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = trailing,
        modifier = modifier,
    )
}

/**
 * Remove for one spent by someone else, Archive for one the wallet still tries, Receive (and Archive) for one only Lightning can bring in;
 * nothing while it is being claimed.
 */
private fun receivedNoteAction(
    note: ReceivedNote,
    onRemove: (String) -> Unit,
    onArchive: (String) -> Unit,
    onReceive: (String) -> Unit,
): (@Composable () -> Unit)? =
    when (note.state) {
        ReceivedState.LightningOnly -> {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WalletButton(kind = ButtonKind.Text, onClick = { onReceive(note.id) }) {
                        Text(stringResource(R.string.received_receive))
                    }
                    WalletButton(kind = ButtonKind.Text, onClick = { onArchive(note.id) }) {
                        Text(stringResource(R.string.received_archive))
                    }
                }
            }
        }
        ReceivedState.Gone -> {
            { WalletButton(kind = ButtonKind.Text, onClick = { onRemove(note.id) }) { Text(stringResource(R.string.received_dismiss)) } }
        }
        ReceivedState.Waiting,
        ReceivedState.Locked,
        ReceivedState.KeyChanged -> {
            { WalletButton(kind = ButtonKind.Text, onClick = { onArchive(note.id) }) { Text(stringResource(R.string.received_archive)) } }
        }
        else -> null
    }
