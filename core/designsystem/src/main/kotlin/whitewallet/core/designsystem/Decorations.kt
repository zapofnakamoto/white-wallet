package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import whitewallet.brand.ReleaseStage
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

/**
 * [title] with the release stage's badge beside it, vertically centred, whatever the brand draws as the title. [centered]: the title stays in the middle, and where the badge doesn't fit beside it, it goes below; otherwise
 * (a top bar) the badge follows the title, which gets the rest of the width. A stable release shows only [title].
 */
@Composable
fun WithReleaseBadge(modifier: Modifier = Modifier, centered: Boolean = true, title: @Composable () -> Unit) {
    if (LocalReleaseStage.current == ReleaseStage.Stable) return Box(modifier) { title() }
    Layout({ title(); WalletReleaseBadge() }, modifier) { (titleMeasurable, badgeMeasurable), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val badge = badgeMeasurable.measure(loose)
        val gap = 4.dp.roundToPx()
        val side = badge.width + gap
        if (!centered) {
            val text = titleMeasurable.measure(loose.copy(maxWidth = (constraints.maxWidth - side).coerceAtLeast(0)))
            val height = maxOf(text.height, badge.height)
            return@Layout layout(maxOf(constraints.minWidth, text.width + side), height) {
                text.place(0, (height - text.height) / 2)
                badge.place(text.width + gap, (height - badge.height) / 2)
            }
        }
        val text = titleMeasurable.measure(loose)
        // Beside the title, with the same room on the other side so the title stays in the middle.
        if (text.width + 2 * side <= constraints.maxWidth) {
            val width = maxOf(constraints.minWidth, text.width + 2 * side)
            val left = (width - text.width) / 2
            val height = maxOf(text.height, badge.height)
            layout(width, height) {
                text.place(left, (height - text.height) / 2)
                badge.place(left + text.width + gap, (height - badge.height) / 2)
            }
        } else {
            val width = maxOf(constraints.minWidth, text.width, badge.width)
            layout(width, text.height + 2 * gap + badge.height) {
                text.place((width - text.width) / 2, 0)
                badge.place((width - badge.width) / 2, text.height + 2 * gap)
            }
        }
    }
}

/** Tells the brand's design what [content] is about (see [WalletStatus]). */
@Composable
fun WalletStatusScope(status: WalletStatus, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWalletStatus provides status, content = content)
}

@Composable
private fun decorationContext() =
    DecorationContext(LocalWalletStatus.current, LocalReduceMotion.current, LocalAppName.current, LocalReleaseStage.current)
