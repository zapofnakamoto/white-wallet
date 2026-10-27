package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * The whole screen after money moved, the same for receiving and sending: the brand's picture at [anchor] (a check
 * mark by default) with [status], the [title], the amount, [details] (who, the fee), then [doneLabel] and, smaller,
 * [moreLabel] for another. The title is announced to screen readers and the phone gives a short buzz.
 */
@Composable
fun SuccessScreen(
    anchor: Anchor,
    status: WalletStatus,
    title: String,
    amountMsat: Long,
    doneLabel: String,
    onDone: () -> Unit,
    moreLabel: String,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    details: List<String> = emptyList(),
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(status) { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    val spacing = MaterialTheme.walletSpacing
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = spacing.screen, vertical = spacing.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.gap),
    ) {
        Spacer(Modifier.weight(1f))
        WalletStatusScope(status) { BrandDecoration(anchor) { SuccessCheck() } }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        AmountText(amountMsat)
        details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        Spacer(Modifier.weight(1f))
        PrimaryButton(doneLabel, onDone)
        WalletButton(kind = ButtonKind.Text, onClick = onMore) { Text(moreLabel) }
    }
}
