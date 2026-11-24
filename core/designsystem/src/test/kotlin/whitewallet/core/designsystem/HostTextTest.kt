package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

// Native graphics: lines break as on a phone.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostTextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aHostTooLongForItsLineBreaksAfterADotNotMidName() {
        compose.setContent { Box(Modifier.width(60.dp)) { HostText("mint.example") } }
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("mint.example").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layout)
        val text = layout.single().layoutInput.text.text
        val lines =
            (0 until layout.single().lineCount).map { text.substring(layout.single().getLineStart(it), layout.single().getLineEnd(it)) }
        assertEquals(listOf("mint.", "example"), lines.map { it.filter { c -> c != '\u200B' } })
    }

    @Test
    fun itIsReadAndFoundAsTheHostItself() {
        compose.setContent { HostText("mint.example") }
        compose.onNodeWithText("mint.example").assertExists()
    }

    // As the app does at its start: OkHttp reads the public suffix list through the app.
    @Before fun readPublicSuffixes() = warmHostText(RuntimeEnvironment.getApplication())

    @Test
    fun theRegisteredNameIsEmphasisedPublicSuffixesKnown() {
        fun emphasised(host: String) = distinctiveName(host)?.let(host::substring)
        assertEquals("example", emphasised("mint.example.com"))
        assertEquals("example", emphasised("mint.example.co.uk"))
        assertEquals("harbor", emphasised("mint.harbor.example"))
        // A suffix anyone can register under: the name under it tells mints apart.
        assertEquals("mymint", emphasised("mymint.fly.dev"))
        assertEquals(null, emphasised("fly.dev"))
        assertEquals("example", emphasised("mint.example.com:3338"))
        assertEquals("Example", emphasised("Mint.Example.COM"))
        assertEquals("münze", emphasised("mint.münze.de"))
        assertEquals("xn--mnze-0ra", emphasised("mint.xn--mnze-0ra.de"))
    }

    @Test
    fun addressesAndSingleNamesAreNotEmphasised() {
        assertEquals(null, distinctiveName("192.168.1.20:3338"))
        assertEquals(null, distinctiveName("[::1]:3338"))
        assertEquals(null, distinctiveName("localhost:8111"))
        assertEquals(null, distinctiveName("mint"))
    }
}
