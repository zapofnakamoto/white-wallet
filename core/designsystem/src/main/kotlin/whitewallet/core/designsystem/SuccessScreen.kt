package whitewallet.core.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.walletSpacing

/** A button of a [SuccessScreen]: what it says and what it does. */
@Immutable class SuccessAction(val label: String, val onClick: () -> Unit)

/**
 * The whole screen after something worked, the same everywhere (money moved, a mint, an address or an account added, a scan finished): the
 * brand's picture at [anchor] (a check mark by default) with [status], the [title], the amount if there is one, [lines] (who, the fee, what
 * was found) and [content] for anything more. Below, always in reach however long the rest is, [done] and, smaller, [more]. Back does what
 * [done] does. The title is announced to screen readers and the phone gives a short buzz. If [amountMsat] changes while the screen is up
 * (more arrived), it counts to the new figure and the buzz comes again with the new [status].
 */
@Composable
fun SuccessScreen(
    anchor: Anchor,
    status: WalletStatus,
    title: String,
    done: SuccessAction,
    modifier: Modifier = Modifier,
    more: SuccessAction? = null,
    amountMsat: Long? = null,
    lines: List<String> = emptyList(),
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(status) { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    BackHandler(onBack = done.onClick)
    val spacing = MaterialTheme.walletSpacing
    Column(
        modifier.fillMaxSize().padding(horizontal = spacing.screen, vertical = spacing.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.gap),
    ) {
        // Centred while it fits, scrolling above the buttons once it doesn't.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.gap, Alignment.CenterVertically),
            ) {
                WalletStatusScope(status) { BrandDecoration(anchor) { SuccessCheck() } }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                )
                amountMsat?.let { CountingAmountText(it) }
                lines.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                content?.invoke(this)
            }
        }
        PrimaryButton(done.label, done.onClick)
        more?.let { WalletButton(kind = ButtonKind.Text, onClick = it.onClick) { Text(it.label) } }
    }
}
