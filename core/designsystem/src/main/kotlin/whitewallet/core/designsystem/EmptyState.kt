package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.walletSpacing

/**
 * A page with nothing to show, the same on every page: the brand's decoration at [anchor] if the page has one, the text, and the one way
 * forward ([action], a [PrimaryButton]) if there is one. [padding]: none where the page's column pads already.
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    anchor: Anchor? = null,
    padding: PaddingValues = PaddingValues(MaterialTheme.walletSpacing.screen),
    action: (@Composable () -> Unit)? = null,
) {
    val spacing = MaterialTheme.walletSpacing
    Column(
        modifier.fillMaxWidth().padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.gap),
    ) {
        anchor?.let { WalletStatusScope(WalletStatus.Empty) { BrandDecoration(it) } }
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        action?.invoke()
    }
}
