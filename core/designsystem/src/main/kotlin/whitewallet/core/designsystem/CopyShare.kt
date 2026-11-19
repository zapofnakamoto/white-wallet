package whitewallet.core.designsystem

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import whitewallet.brand.ui.ButtonKind

/** Copy and Share for [text] (an address, an invoice), side by side; once copied, [copiedMessage] says so below them. */
@Composable
fun CopyShare(text: String, copiedMessage: String) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember(text) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        WalletButton(
            kind = ButtonKind.Secondary,
            onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("lightning", text))) }
                copied = true
            },
        ) {
            Text(stringResource(R.string.copy))
        }
        WalletButton(
            kind = ButtonKind.Secondary,
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, null))
            },
        ) {
            Text(stringResource(R.string.share))
        }
    }
    if (copied) Text(copiedMessage, style = MaterialTheme.typography.bodySmall)
}
