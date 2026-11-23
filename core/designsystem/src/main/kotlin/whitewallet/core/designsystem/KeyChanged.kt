package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.Tone

/**
 * The warning for a signing key that doesn't match the pinned one, the same wherever the user meets it (the mint's page, adding the mint
 * again): both keys, and (with [onAccept]) using the new one after a confirmation. Doing nothing is safe, so accepting is never the page's
 * main action. A mint that presents none ([presented] null) has nothing to accept: it is only used again once its key matches.
 */
@Composable
fun KeyChangedCard(host: String, remembered: String?, presented: String?, onAccept: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    var confirming by remember { mutableStateOf(false) }
    WalletCard(tone = Tone.Warning, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val color = MaterialTheme.colorScheme.onErrorContainer
            Text(stringResource(R.string.mint_key_changed, host), color = color)
            for ((label, key) in listOf(R.string.mint_key_remembered to remembered, R.string.mint_key_new to presented)) {
                if (key == null) continue
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    key.chunked(4).joinToString(" "),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
            if (presented != null && onAccept != null) {
                WalletButton(onClick = { confirming = true }, modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.mint_key_accept))
                }
            }
        }
    }
    if (confirming && presented != null && onAccept != null) {
        WalletDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.mint_key_accept_title)) },
            text = { Text(stringResource(R.string.mint_key_accept_body, host)) },
            confirmButton = {
                WalletButton(
                    kind = ButtonKind.Text,
                    onClick = {
                        confirming = false
                        onAccept(presented)
                    },
                ) {
                    Text(stringResource(R.string.mint_key_accept_confirm))
                }
            },
            dismissButton = {
                WalletButton(kind = ButtonKind.Text, onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
