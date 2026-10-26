package whitewallet.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import whitewallet.brand.ui.LocalReduceMotion
import whitewallet.brand.ui.walletColors

/**
 * Something went well, drawn: a disc in the success colour pops in, a ring goes out from it and a check mark draws
 * itself. With reduced motion it is simply there. The default at [whitewallet.brand.ui.Anchor.ReceiveSuccess].
 */
@Composable
fun SuccessCheck(modifier: Modifier = Modifier, size: Dp = 112.dp) {
    val reduceMotion = LocalReduceMotion.current
    val disc = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val ring = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val check = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        launch { ring.animateTo(1f, tween(700, delayMillis = 120)) }
        disc.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        check.animateTo(1f, tween(320))
    }
    val color = MaterialTheme.walletColors.success
    val mark = MaterialTheme.colorScheme.surface
    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2 * 0.8f
        if (ring.value in 0.01f..0.99f) {
            drawCircle(color.copy(alpha = 0.5f * (1 - ring.value)), radius * (1 + 0.25f * ring.value), style = Stroke(width = 3.dp.toPx()))
        }
        drawCircle(color, radius * disc.value)
        if (check.value > 0f) {
            val unit = radius / 40
            val path = Path().apply {
                moveTo(center.x - 17 * unit, center.y + 1 * unit)
                lineTo(center.x - 5 * unit, center.y + 13 * unit)
                lineTo(center.x + 18 * unit, center.y - 12 * unit)
            }
            val drawn = Path()
            PathMeasure().apply { setPath(path, false) }.let { it.getSegment(0f, it.length * check.value, drawn, true) }
            drawPath(drawn, mark, style = Stroke(width = 7 * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

