package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import whitewallet.brand.ReleaseNote
import whitewallet.brand.ui.Tone

/**
 * "What's new": [notes] in a sheet, newest first, important ones as warnings. "Got it" calls [onDone]; closing it
 * otherwise (swiping it away, back) calls [onDismiss].
 */
@Composable
fun ReleaseNotesSheet(notes: List<ReleaseNote>, onDone: () -> Unit, onDismiss: () -> Unit = onDone) {
    WalletSheet(onDismissRequest = onDismiss) {
        // Scrolls: several notes after a long time without updating don't fit.
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.release_notes_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            ReleaseNoteList(notes)
            PrimaryButton(stringResource(R.string.release_notes_done), onDone)
        }
    }
}

/** Each of [notes] as a card: a warning for an important one. */
@Composable
fun ReleaseNoteList(notes: List<ReleaseNote>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        notes.forEach { note ->
            WalletCard(tone = if (note.important) Tone.Warning else Tone.Neutral, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(note.title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(note.body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
