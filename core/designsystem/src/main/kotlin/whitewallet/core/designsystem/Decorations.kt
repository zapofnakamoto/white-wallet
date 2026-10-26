package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.brand.ui.LocalReduceMotion
import whitewallet.brand.ui.LocalWalletStatus

/**
 * The brand's content at [anchor] (or [default] without a brand design), with the current screen's status. It is
 * decorative: screen readers skip it, as the texts around it say what it shows.
 */
@Composable
fun BrandDecoration(anchor: Anchor, modifier: Modifier = Modifier, default: @Composable () -> Unit = {}) {
    Box(modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        LocalBrandDesign.current.Decoration(anchor, decorationContext(), default)
    }
}

/**
 * A title the brand may draw itself (a wordmark) at [anchor]; [default] is the plain text. Screen readers always read
 * [title] as a heading, whatever the brand draws.
 */
@Composable
fun BrandTitle(anchor: Anchor, title: String, modifier: Modifier = Modifier, default: @Composable () -> Unit) {
    Box(modifier.clearAndSetSemantics { heading(); contentDescription = title }, contentAlignment = Alignment.Center) {
        LocalBrandDesign.current.Decoration(anchor, decorationContext(), default)
    }
}

/** Tells the brand's design what [content] is about (see [WalletStatus]). */
@Composable
fun WalletStatusScope(status: WalletStatus, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWalletStatus provides status, content = content)
}

@Composable
private fun decorationContext() = DecorationContext(LocalWalletStatus.current, LocalReduceMotion.current, LocalAppName.current)
