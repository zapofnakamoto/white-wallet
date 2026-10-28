package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
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
 * (a top bar) the badge follows the title, which gets the rest of the width. A debug build adds its [WalletBuildLabel]:
 * after the badge when centred, on a line of its own below the title and badge in a top bar; it is shortened only
 * where it is wider than the space. A stable release build shows only [title].
 */
@Composable
fun WithReleaseBadge(modifier: Modifier = Modifier, centered: Boolean = true, title: @Composable () -> Unit) {
    if (LocalReleaseStage.current == ReleaseStage.Stable && LocalBuildLabel.current == null) return Box(modifier) { title() }
    // Boxes, so both slots are there to measure when one of them draws nothing (a stable debug build has no badge).
    Layout({ title(); Box { WalletReleaseBadge() }; Box { WalletBuildLabel() } }, modifier) { (titleMeasurable, badgeMeasurable, labelMeasurable), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gap = 4.dp.roundToPx()
        fun side(width: Int) = if (width > 0) width + gap else 0
        val badge = badgeMeasurable.measure(loose)
        if (!centered) {
            // The badge beside the title; the label on a line of its own below them, with the whole width.
            val text = titleMeasurable.measure(loose.copy(maxWidth = (constraints.maxWidth - side(badge.width)).coerceAtLeast(0)))
            val label = labelMeasurable.measure(loose)
            val line = maxOf(text.height, badge.height)
            return@Layout layout(maxOf(constraints.minWidth, text.width + side(badge.width), label.width), line + label.height) {
                text.place(0, (line - text.height) / 2)
                badge.place(text.width + gap, (line - badge.height) / 2)
                label.place(0, line)
            }
        }
        val text = titleMeasurable.measure(loose)
        val label = labelMeasurable.measure(loose.copy(maxWidth = (constraints.maxWidth - side(badge.width)).coerceAtLeast(0)))
        // The badge and the label as one group: beside the title, with the same room on the other side so the title
        // stays in the middle, or centred below it.
        val group = side(badge.width) + side(label.width) - gap
        val groupHeight = maxOf(badge.height, label.height)
        fun Placeable.PlacementScope.placeGroup(x: Int, y: Int, height: Int) {
            badge.place(x, y + (height - badge.height) / 2)
            label.place(x + side(badge.width), y + (height - label.height) / 2)
        }
        if (text.width + 2 * (group + gap) <= constraints.maxWidth) {
            val width = maxOf(constraints.minWidth, text.width + 2 * (group + gap))
            val left = (width - text.width) / 2
            val height = maxOf(text.height, groupHeight)
            layout(width, height) {
                text.place(left, (height - text.height) / 2)
                placeGroup(left + text.width + gap, 0, height)
            }
        } else {
            val width = maxOf(constraints.minWidth, text.width, group)
            layout(width, text.height + 2 * gap + groupHeight) {
                text.place((width - text.width) / 2, 0)
                placeGroup((width - group) / 2, text.height + 2 * gap, groupHeight)
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
