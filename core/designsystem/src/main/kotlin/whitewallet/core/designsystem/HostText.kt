package whitewallet.core.designsystem

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle

/**
 * A mint's host. Too long for its line, it breaks after a dot ("mint." / "example"), never inside a name: Android doesn't break there by
 * itself. Read aloud and found by tests as the host itself.
 */
@Composable
fun HostText(host: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        host.replace(".", ".​"),
        modifier.clearAndSetSemantics {
            text = AnnotatedString(host)
            getTextLayoutResult { results -> layout?.let(results::add) != null }
        },
        style = style,
        onTextLayout = { layout = it },
    )
}
