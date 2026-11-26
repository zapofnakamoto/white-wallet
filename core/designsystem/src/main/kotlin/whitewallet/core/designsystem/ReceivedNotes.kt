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

/** What a received note in [ReceivedState.Waiting] waits for: what its row says. */
enum class ReceivedWait {
    /** The phone: offline, nothing is asked. */
    Offline,

    /** Its mint, which didn't answer the last time. */
    MintNotAnswering,

    /** Nothing in the way: the next refresh claims it. */
    NextRefresh,
}

/** What this note waits for: offline first (it says nothing about the mint), then its mint if it is among [notAnswering]. */
fun ReceivedNote.waitsFor(offline: Boolean, notAnswering: Set<String>): ReceivedWait =
    when {
        offline -> ReceivedWait.Offline
        host in notAnswering -> ReceivedWait.MintNotAnswering
        else -> ReceivedWait.NextRefresh
    }

/** Where a received note stands, in the user's language: why it isn't claimed yet ([wait]), or why it can't be. */
@Composable
fun receivedNoteStatus(note: ReceivedNote, wait: ReceivedWait = ReceivedWait.NextRefresh): String =
    when (note.state) {
        ReceivedState.Waiting ->
            when (wait) {
                ReceivedWait.Offline -> stringResource(R.string.received_waiting_offline)
                ReceivedWait.MintNotAnswering -> stringResource(R.string.received_waiting_mint, note.host)
                ReceivedWait.NextRefresh -> stringResource(R.string.received_waiting)
            }
        ReceivedState.Locked ->
            note.lockedUntilMillis?.let { stringResource(R.string.received_locked_until, recentTime(it)) }
                ?: stringResource(R.string.received_locked)
        ReceivedState.KeyChanged -> stringResource(R.string.received_key_changed)
        ReceivedState.LightningOnly ->
            stringResource(
                when (note.unclaimedReason) {
                    UnclaimedReason.NoSigningKey -> R.string.received_lightning_no_key
                    UnclaimedReason.Refused,
                    UnclaimedReason.Unreachable,
                    UnclaimedReason.NotANote,
                    UnclaimedReason.ByUser,
                    UnclaimedReason.Spent,
                    null -> R.string.received_lightning_not_a_note
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
            UnclaimedReason.Spent -> return stringResource(R.string.received_gone)
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
        WholeDuration.Unit.Seconds,
        WholeDuration.Unit.Minutes,
        WholeDuration.Unit.Hours -> pluralStringResource(hours, tried.count, tried.count)
    }
}

/**
 * What a received note in a list can ask for, by its id: removing one spent by someone else, archiving one the wallet still tries, or
 * receiving one only Lightning can bring in (in Claim).
 */
class ReceivedNoteActions(
    val remove: (String) -> Unit = {},
    val archive: (String) -> Unit = {},
    val receive: (String) -> Unit = {},
)

/** A received note in a list, with the action its state offers (see [ReceivedNoteActions]) and what it [wait]s for. */
@Composable
fun ReceivedNoteRow(
    note: ReceivedNote,
    amountHidden: Boolean,
    actions: ReceivedNoteActions,
    modifier: Modifier = Modifier,
    wait: ReceivedWait = ReceivedWait.NextRefresh,
) = ReceivedNoteRow(note, amountHidden, modifier, wait = wait, trailing = receivedNoteAction(note, actions))

/**
 * A received note in a list: what it is worth as far as known (not with [amountHidden]), on which mint, and where it stands (what it
 * [wait]s for), with [trailing] actions of the caller's own (the archive's).
 */
@Composable
fun ReceivedNoteRow(
    note: ReceivedNote,
    amountHidden: Boolean,
    modifier: Modifier = Modifier,
    wait: ReceivedWait = ReceivedWait.NextRefresh,
    trailing: (@Composable () -> Unit)?,
) {
    val status = receivedNoteStatus(note, wait)
    val known = note.amountMsat
    val declared = note.declaredMsat
    val amount =
        when {
            amountHidden -> null
            known != null -> formatAmount(known)
            declared != null -> stringResource(R.string.received_unconfirmed, formatAmount(declared))
            else -> stringResource(R.string.received_unknown_amount)
        }
    WalletListItem(
        headlineContent = { Text(amount ?: status) },
        supportingContent = {
            Column {
                if (amount != null) Text(status)
                Text(stringResource(R.string.received_on, note.host), style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = trailing,
        modifier = modifier,
    )
}

/**
 * A received note the wallet can't claim on its own, so it waits for the user: one only Lightning can bring in, one spent by someone else,
 * one whose mint's key changed, or a locked one past its date (or without one) that its mint keeps refusing. Its row offers what they can
 * do. Every other pending note only waits, and the wallet claims it on its own. Home and the notes view both ask this.
 */
fun ReceivedNote.needsUser(nowMillis: Long = System.currentTimeMillis()): Boolean =
    when (state) {
        ReceivedState.LightningOnly,
        ReceivedState.Gone,
        ReceivedState.KeyChanged -> true
        ReceivedState.Locked -> lockedUntilMillis.let { it == null || it <= nowMillis }
        ReceivedState.Waiting,
        ReceivedState.Claiming,
        ReceivedState.Melting,
        ReceivedState.Claimed,
        ReceivedState.Archived -> false
    }

/**
 * Remove for one spent by someone else, Archive for one whose mint's key changed or whose lock has passed (or has no date), Receive (and
 * Archive) for one only Lightning can bring in; nothing for one that only waits (the wallet claims it, or archives it after trying long
 * enough).
 */
private fun archive(note: ReceivedNote, actions: ReceivedNoteActions): @Composable () -> Unit = {
    WalletButton(kind = ButtonKind.Text, onClick = { actions.archive(note.id) }) { Text(stringResource(R.string.received_archive)) }
}

private fun receivedNoteAction(note: ReceivedNote, actions: ReceivedNoteActions): (@Composable () -> Unit)? =
    when (note.state) {
        ReceivedState.LightningOnly -> {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WalletButton(kind = ButtonKind.Text, onClick = { actions.receive(note.id) }) {
                        Text(stringResource(R.string.received_receive))
                    }
                    WalletButton(kind = ButtonKind.Text, onClick = { actions.archive(note.id) }) {
                        Text(stringResource(R.string.received_archive))
                    }
                }
            }
        }
        ReceivedState.Gone -> {
            {
                WalletButton(kind = ButtonKind.Text, onClick = { actions.remove(note.id) }) {
                    Text(stringResource(R.string.received_dismiss))
                }
            }
        }
        // A locked one past its date that still isn't claimed: its mint keeps refusing it, and the wallet never gives it up by itself.
        ReceivedState.Locked if note.needsUser() -> archive(note, actions)
        ReceivedState.KeyChanged -> archive(note, actions)
        ReceivedState.Waiting,
        ReceivedState.Locked,
        ReceivedState.Claiming,
        ReceivedState.Melting,
        ReceivedState.Claimed,
        ReceivedState.Archived -> null
    }
