package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState

/** Where a received note stands, in the user's language: why it isn't claimed yet, or why it can't be. */
@Composable
fun receivedNoteStatus(note: ReceivedNote): String = when (note.state) {
    ReceivedState.Waiting -> stringResource(R.string.received_waiting)
    ReceivedState.Locked -> note.lockedUntilMillis?.let { stringResource(R.string.received_locked_until, recentTime(it)) }
        ?: stringResource(R.string.received_locked)
    ReceivedState.Claiming -> stringResource(R.string.received_claiming)
    ReceivedState.Claimed -> stringResource(R.string.received_claimed)
    ReceivedState.Gone -> stringResource(R.string.received_gone)
    ReceivedState.Invalid -> stringResource(R.string.received_invalid)
}

/**
 * A received note in a list: what it is worth as far as known (not with [amountHidden]), on which mint ([showMint]),
 * and where it stands. One that can no longer be claimed can be removed ([onDismiss]).
 */
@Composable
fun ReceivedNoteRow(note: ReceivedNote, showMint: Boolean, amountHidden: Boolean, onDismiss: (String) -> Unit, modifier: Modifier = Modifier) {
    val status = receivedNoteStatus(note)
    val amount = when {
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
        trailingContent = if (note.state == ReceivedState.Gone || note.state == ReceivedState.Invalid) {
            { WalletButton(kind = ButtonKind.Text, onClick = { onDismiss(note.id) }) { Text(stringResource(R.string.received_dismiss)) } }
        } else {
            null
        },
        modifier = modifier,
    )
}
