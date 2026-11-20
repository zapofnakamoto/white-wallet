package whitewallet.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlin.math.roundToLong

/** How long an amount takes to count from the old figure to the new one. */
private const val COUNT_MILLIS = 500

/**
 * An [AmountText] that counts from the old figure to the new one when [msat] changes while it is on screen, slowing down as it arrives. The
 * first figure is drawn at once, never counted up from zero. It follows the system's animation scale (none: the new figure at once). A
 * screen reader hears only the new amount, once, not the figures on the way.
 */
@Composable
fun CountingAmountText(msat: Long, modifier: Modifier = Modifier, hidden: Boolean = false, onToggleHidden: (() -> Unit)? = null) {
    var from by remember { mutableLongStateOf(msat) }
    var to by remember { mutableLongStateOf(msat) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(msat) {
        if (msat == to) return@LaunchedEffect
        // From the figure on screen now, if the last count hasn't arrived yet.
        from = progress.value.let { if (it >= 1f) to else between(from, to, it) }
        to = msat
        progress.snapTo(0f)
        progress.animateTo(1f, tween(COUNT_MILLIS, easing = EaseOutCubic))
    }
    val shown = if (progress.value >= 1f) to else between(from, to, progress.value)
    AmountText(shown, modifier, hidden, onToggleHidden, spokenMsat = msat)
}

/** The figure [fraction] of the way from [from] to [to]. */
private fun between(from: Long, to: Long, fraction: Float): Long = from + ((to - from).toDouble() * fraction).roundToLong()
