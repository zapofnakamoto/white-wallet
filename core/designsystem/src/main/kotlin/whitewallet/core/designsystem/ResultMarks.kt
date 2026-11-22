package whitewallet.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Something didn't work, drawn: an exclamation mark on a disc in the error colour, still. The default of a [ResultTone.Problem] screen. */
@Composable
fun ProblemMark(modifier: Modifier = Modifier, size: Dp = 112.dp) {
    val color = MaterialTheme.colorScheme.error
    val mark = MaterialTheme.colorScheme.surface
    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2 * 0.8f
        val unit = radius / 40
        drawCircle(color, radius)
        drawLine(mark, Offset(center.x, center.y - 18 * unit), Offset(center.x, center.y + 4 * unit), 7 * unit, StrokeCap.Round)
        drawCircle(mark, 4.5f * unit, Offset(center.x, center.y + 17 * unit))
    }
}

/** Something is still to come, drawn: a clock face, calm and still. The default of a [ResultTone.Waiting] screen. */
@Composable
fun WaitingMark(modifier: Modifier = Modifier, size: Dp = 112.dp) {
    val color = MaterialTheme.colorScheme.surfaceVariant
    val hands = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2 * 0.8f
        val unit = radius / 40
        drawCircle(color, radius)
        drawCircle(hands, 27 * unit, style = Stroke(width = 4 * unit))
        drawLine(hands, center, Offset(center.x, center.y - 18 * unit), 5 * unit, StrokeCap.Round)
        drawLine(hands, center, Offset(center.x + 12 * unit, center.y), 5 * unit, StrokeCap.Round)
        drawCircle(hands, 3.5f * unit)
    }
}
