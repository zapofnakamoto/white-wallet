package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.Tone

/**
 * Something that needs the user, as short as it can be and the same wherever it shows (Home, the notes view): what happened ([title]), what
 * it means for the user ([line]), and one way on ([action]): to the page where it is dealt with, or, without one, the one thing to do.
 */
@Composable
fun AttentionCard(
    title: String,
    line: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    WalletCard(modifier.fillMaxWidth(), tone = Tone.Warning) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        }
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(start = 44.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
            WalletButton(kind = ButtonKind.Text, onClick = onAction, enabled = enabled) { Text(action) }
        }
    }
}
