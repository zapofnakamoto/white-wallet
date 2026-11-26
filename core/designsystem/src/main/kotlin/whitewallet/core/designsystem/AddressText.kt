package whitewallet.core.designsystem

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
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
    ) { (whole, broken), constraints ->
        drawn[0] = if (whole.maxIntrinsicWidth(constraints.maxHeight) <= constraints.maxWidth) 0 else 1
        val line = (if (drawn[0] == 0) whole else broken).measure(constraints)
        layout(line.width, line.height) { line.place(0, 0) }
    }
}
