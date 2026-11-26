package whitewallet.core.designsystem

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints

/**
 * A Lightning address, its name bolder and the mint ("@mint.example") quieter. Too long for its line, the mint goes below the name, "@"
 * first, and breaks after a dot if it is still too long, never inside a name: Android doesn't break there by itself. Read aloud and found
 * by tests as the address itself. An address on its own, never inside a sentence.
 */
@Composable
fun AddressText(address: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val weight = style.fontWeight ?: FontWeight.Normal
    val nameColor = MaterialTheme.colorScheme.onSurface
    val mintColor = MaterialTheme.colorScheme.onSurfaceVariant
    val at = address.lastIndexOf('@').takeIf { it >= 0 } ?: address.length
    fun text(between: String) = buildAnnotatedString {
        // Two weight steps over the mint, as a mint's own name in [HostText].
        withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight((weight.weight + 200).coerceAtMost(900)))) {
            append(address.substring(0, at))
        }
        if (at < address.length) withStyle(SpanStyle(color = mintColor)) { append(between + breakable(address.substring(at))) }
    }
    // Drawn whole where it fits, else with the mint on its own line: a line broken where it is full would keep "@mint." beside the name.
    val layouts = remember { arrayOfNulls<TextLayoutResult>(2) }
    val drawn = remember { intArrayOf(0) }
    Layout(
        content = {
            listOf("", "\n").forEachIndexed { i, between ->
                Text(text(between), Modifier.clearAndSetSemantics {}, style = style, onTextLayout = { layouts[i] = it })
            }
        },
        modifier =
            modifier.clearAndSetSemantics {
                this.text = AnnotatedString(address)
                getTextLayoutResult { results -> layouts[drawn[0]]?.let(results::add) != null }
            },
        measurePolicy = remember { AddressLines { drawn[0] = it } },
    )
}

/**
 * Of an address written whole and with its mint on a line of its own, the one that fits its line. As narrow as the second can be: a row
 * asking how little room the address needs is told its longest part, not the whole address.
 */
private class AddressLines(private val onDrawn: (Int) -> Unit) : MeasurePolicy {
    private fun fitting(lines: List<IntrinsicMeasurable>, width: Int) =
        if (lines[0].maxIntrinsicWidth(Constraints.Infinity) <= width) 0 else 1

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val drawn = fitting(measurables, constraints.maxWidth)
        onDrawn(drawn)
        val line = measurables[drawn].measure(constraints)
        return layout(line.width, line.height) { line.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables[1].minIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables[0].maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables[fitting(measurables, width)].minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables[fitting(measurables, width)].maxIntrinsicHeight(width)
}
