package whitewallet.feature.send

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.SecureScreen
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.scanner.QrScanner
import whitewallet.core.wallet.IncomingLink

/**
 * Scan anything from Home: a note opens Claim, an invoice, Lightning address or pay link opens Send ([onScanned]). A code that is neither
 * says so, and the camera stays on.
 */
@Composable
fun ScanRoute(onBack: () -> Unit, onScanned: (IncomingLink) -> Unit) {
    // The camera may show a note's QR code: no screenshots, no Recents thumbnail.
    SecureScreen { ScanScreen(onBack, onScanned) }
}

@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onScanned: (IncomingLink) -> Unit,
    scanner: @Composable (Modifier, (String) -> Unit) -> Unit = { m, f -> QrScanner(f, m) },
) {
    var unknown by remember { mutableStateOf(false) }
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.scan_title), onBack) }) { padding ->
        ScreenColumn(Modifier.padding(padding)) {
            Text(stringResource(R.string.scan_hint), textAlign = TextAlign.Center)
            scanner(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)) { text ->
                val link = IncomingLink.classify(text)
                unknown = link == null
                link?.let(onScanned)
            }
            if (unknown) Text(stringResource(R.string.scan_unknown), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}
