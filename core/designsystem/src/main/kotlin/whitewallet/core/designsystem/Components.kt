package whitewallet.core.designsystem

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import whitewallet.brand.ui.AmountParams
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.brand.ui.QrCodeCoverParams
import whitewallet.brand.ui.QrCodeParams
import whitewallet.brand.ui.TopBarParams
import whitewallet.brand.ui.walletSpacing

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
    LocalBrandDesign.current.components.Amount(
        AmountParams(if (hidden) stringResource(R.string.amount_hidden) else formatAmount(msat)),
        modifier
            .then(if (onToggleHidden != null) Modifier.clickable(onClickLabel = toggleLabel, onClick = onToggleHidden) else Modifier)
            .semantics { contentDescription = spoken },
    )
}

/**
 * Which build is running ([LocalBuildLabel]), for developers; nothing in a release build. Plain text, not a brand
 * component. Where it doesn't fit, shortened from the start, so the commit and `-dirty` stay.
 */
@Composable
fun WalletBuildLabel(modifier: Modifier = Modifier) {
    val label = LocalBuildLabel.current ?: return
    Text(label, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.StartEllipsis)
}

/** A full-width primary action that shows a spinner while [busy]. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    WalletButton(onClick, modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, busy = busy) { Text(text) }
}

/** Top bar with an optional back button. */
@Composable
fun WalletTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    WalletTopBar(
        // A heading, so screen reader users can jump to it.
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        onBack = onBack,
        actions = actions,
    )
}

/** Top bar with a title of its own (such as a brand's wordmark) and an optional back button. */
@Composable
fun WalletTopBar(title: @Composable () -> Unit, onBack: (() -> Unit)?, actions: @Composable () -> Unit) {
    LocalBrandDesign.current.components.TopBar(
        TopBarParams(
            title = title,
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                }
            },
            actions = { actions() },
        ),
        Modifier,
    )
}

/** Renders [content] as a QR code. Pass a [description] when [content] is a secret, so screen readers never read it out. */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, size: Dp = 260.dp, description: String = content) {
    val bitmap = remember(content) { qrBitmap(content).asImageBitmap() }
    LocalBrandDesign.current.components.QrCode(QrCodeParams(bitmap, size), modifier.semantics { contentDescription = description })
}

/**
 * Stands in for a secret QR code until the user taps it ([onReveal]), so nobody nearby can scan it by chance: as large
 * as the code, so nothing moves when it shows. The brand draws it (`QrCodeCover`); [label] says what a tap does.
 */
@Composable
fun QrCodeCover(label: String, onReveal: () -> Unit, modifier: Modifier = Modifier, size: Dp = 260.dp) {
    LocalBrandDesign.current.components.QrCodeCover(
        QrCodeCoverParams(label, size),
        modifier.clickable(role = Role.Button, onClick = onReveal),
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
    val spacing = MaterialTheme.walletSpacing
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = spacing.screen, vertical = spacing.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.gap),
    ) { content() }
}
