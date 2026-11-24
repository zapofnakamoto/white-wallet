package whitewallet.core.designsystem

import android.content.Context
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttp

/**
 * A mint's host, the part that tells it from others bolder ([distinctiveName]: "mint.**example**.com"), so a look-alike host stands out.
 * Too long for its line, it breaks after a dot ("mint." / "example"), never inside a name: Android doesn't break there by itself. Read
 * aloud and found by tests as the host itself. A name on its own, never inside a sentence.
 */
@Composable
fun HostText(host: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val name = remember(host) { distinctiveName(host) }
    val weight = style.fontWeight ?: FontWeight.Normal
    val text = buildAnnotatedString {
        if (name == null) append(breakable(host))
        else {
            append(breakable(host.substring(0, name.first)))
            // Two weight steps over the rest: one is hard to see on a phone.
            withStyle(SpanStyle(fontWeight = FontWeight((weight.weight + 200).coerceAtMost(900)))) {
                append(breakable(host.substring(name)))
            }
            append(breakable(host.substring(name.last + 1)))
        }
    }
    Text(
        text,
        modifier.clearAndSetSemantics {
            this.text = AnnotatedString(host)
            getTextLayoutResult { results -> layout?.let(results::add) != null }
        },
        style = style,
        onTextLayout = { layout = it },
    )
}

private fun breakable(text: String) = text.replace(".", ".​")

/**
 * Where in [host] (a port allowed) the name sits that was registered, the one that tells it from others: "example" in "mint.example.com"
 * and "mint.example.co.uk" (public suffixes known), as written (Unicode or punycode, any case). Null for an IP address, `localhost`, or a
 * host that is a public suffix itself: nothing there is emphasised.
 */
internal fun distinctiveName(host: String): IntRange? {
    if (host.startsWith("[")) return null
    val name = host.substringBefore(':')
    if (name.all { it.isDigit() || it == '.' }) return null
    // The public suffix list, from OkHttp (read once, at the app's start: [warmHostText]).
    val registered = runCatching { "http://$name/".toHttpUrlOrNull()?.topPrivateDomain() }.getOrNull() ?: return null
    val labels = name.split('.')
    val index = labels.size - registered.split('.').size
    if (index !in labels.indices) return null
    val start = labels.take(index).sumOf { it.length + 1 }
    return start until start + labels[index].length
}

/**
 * Reads the public suffix list, off the main thread at the app's start, so the first [HostText] doesn't wait for it. OkHttp reads it
 * through [context]: on a phone its startup initializer hands it over too, a test (Robolectric runs none) has only this.
 */
fun warmHostText(context: Context) {
    OkHttp.initialize(context.applicationContext)
    distinctiveName("mint.example.com")
}
