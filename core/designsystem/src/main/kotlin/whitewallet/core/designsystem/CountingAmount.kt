package whitewallet.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
 * screen reader hears only the new amount, once, not the figures on the way. Whose amount it is goes in [of] (an account's id): when that
 * changes, the new figure is drawn at once too, never counted to from someone else's. Drawn again after something covered it, it counts on
 * from the figure kept in [last], the one the user saw before, if there is one.
 */
@Composable
fun CountingAmountText(
    msat: Long,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    onToggleHidden: (() -> Unit)? = null,
    of: Any? = null,
    last: LastFigure? = null,
) = key(of) { Counting(msat, modifier, hidden, onToggleHidden, last) }

/**
 * The figure a [CountingAmountText] drew last, kept by its caller while something covers it (a loading screen): drawn again, it counts on
 * from there. It is what was on screen, part of the way if a count was cut short, not the amount it was counting to.
 */
class LastFigure {
    internal var msat: Long? = null
}

@Composable
private fun Counting(msat: Long, modifier: Modifier, hidden: Boolean, onToggleHidden: (() -> Unit)?, last: LastFigure?) {
    var from by remember { mutableLongStateOf(last?.msat ?: msat) }
    var to by remember { mutableLongStateOf(last?.msat ?: msat) }
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
    SideEffect { last?.msat = shown }
}

/** The figure [fraction] of the way from [from] to [to]. */
private fun between(from: Long, to: Long, fraction: Float): Long = from + ((to - from).toDouble() * fraction).roundToLong()
