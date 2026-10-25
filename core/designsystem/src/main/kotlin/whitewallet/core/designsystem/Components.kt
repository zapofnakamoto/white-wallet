package whitewallet.core.designsystem

import androidx.compose.ui.semantics.heading
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** A trailing "opens a page" chevron; mirrored in right-to-left languages. */
@Composable
fun ChevronIcon() {
    Icon(painterResource(R.drawable.ic_chevron_end), contentDescription = null)
}

/**
 * A large amount, such as the balance. With [onToggleHidden], tapping it hides or shows the figure (Design Guide:
 * units and symbols); while [hidden], only dots are shown and read out.
 */
@Composable
fun AmountText(msat: Long, modifier: Modifier = Modifier, hidden: Boolean = false, onToggleHidden: (() -> Unit)? = null) {
    val spoken = if (hidden) stringResource(R.string.amount_hidden_description) else spokenAmount(msat)
    val toggleLabel = stringResource(if (hidden) R.string.amount_show else R.string.amount_hide)
    Text(
        if (hidden) stringResource(R.string.amount_hidden) else formatAmount(msat),
        style = MaterialTheme.typography.displaySmall,
        textAlign = TextAlign.Center,
        modifier = modifier
            .then(if (onToggleHidden != null) Modifier.clickable(onClickLabel = toggleLabel, onClick = onToggleHidden) else Modifier)
            .semantics { contentDescription = spoken },
    )
}

/** A full-width primary action that shows a spinner while [busy]. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text(text)
    }
}

/** Top bar with an optional back button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        // A heading, so screen reader users can jump to it.
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                }
            }
        },
        actions = { actions() },
    )
}

/** Renders [content] as a QR code. Pass a [description] when [content] is a secret, so screen readers never read it out. */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, size: Dp = 260.dp, description: String = content) {
    val bitmap = remember(content) { qrBitmap(content) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        filterQuality = FilterQuality.None,
        modifier = modifier.size(size).semantics { contentDescription = description },
    )
}

private fun qrBitmap(content: String): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }
    return bitmap
}

/** Centered column used by the simple screens. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}
