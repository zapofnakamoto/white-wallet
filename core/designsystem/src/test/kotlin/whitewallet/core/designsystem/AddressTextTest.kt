package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// Native graphics: lines break as on a phone.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddressTextTest {
    @get:Rule val compose = createComposeRule()

    private val address = "calmcedar7523@mint.lnurlcash.com"

    private fun lines(): List<String> {
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(address).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layout)
        val result = layout.single()
        val text = result.layoutInput.text.text
        return (0 until result.lineCount).map {
            text.substring(result.getLineStart(it), result.getLineEnd(it)).filter { c -> c != '\u200B' && c != '\n' }
        }
    }

    @Test
    fun anAddressTooLongForItsLineBreaksBeforeTheAt() {
        compose.setContent { Box(Modifier.width(200.dp)) { AddressText(address) } }
        assertEquals(listOf("calmcedar7523", "@mint.lnurlcash.com"), lines())
    }

    @Test
    fun aMintTooLongForItsLineBreaksAfterADot() {
        compose.setContent { Box(Modifier.width(110.dp)) { AddressText(address) } }
        assertEquals(listOf("calmcedar7523", "@mint.lnurlcash.", "com"), lines())
    }

    @Test
    fun anAddressThatFitsStaysOnOneLine() {
        compose.setContent { Box(Modifier.width(400.dp)) { AddressText(address) } }
        assertEquals(listOf(address), lines())
    }

    @Test
    fun itIsReadAndFoundAsTheAddressItself() {
        compose.setContent { AddressText(address) }
        compose.onNodeWithText(address).assertExists()
    }

    private fun arrowBeside(address: String) {
        compose.setContent {
            Box(Modifier.width(411.dp)) {
                WalletListItem(
                    leadingContent = { Box(Modifier.size(40.dp)) },
                    headlineContent = { Text("Main") },
                    supportingContent = { AddressText(address) },
                    trailingContent = { Box(Modifier.size(24.dp).testTag("arrow")) },
                )
            }
        }
        fun placed(matcher: SemanticsMatcher) =
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().single { it.layoutInfo.isPlaced }.boundsInRoot
        val text = placed(hasText(address))
        val arrow = placed(hasTestTag("arrow"))
        assertTrue("$arrow beside $text", arrow.top < text.bottom && arrow.left >= text.right)
    }

    @Test fun aRowsArrowStaysBesideALongAddress() = arrowBeside(address)

    // The longest name the wallet lets a user choose.
    @Test fun aRowsArrowStaysBesideTheLongestName() = arrowBeside("abcdefghijklmnopqrstuvwxyz012345@mint.lnurlcash.com")

    // An onion mint's name fits no line of a phone: below the text the arrow wouldn't keep it whole either.
    @Test
    fun aRowsArrowStaysBesideAnOnionMint() =
        arrowBeside("abcdefghijklmnopqrstuvwxyz012345@2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion")

    // Longer than the wallet lets anyone choose, and than any line: a name another wallet registered.
    @Test fun aRowsArrowStaysBesideANameTooLongForAnyLine() = arrowBeside("a1b2c3d4e5".repeat(8) + "@mint.lnurlcash.com")

    @Test
    fun aNameTooLongForItsLineStillHasTheMintOnALineOfItsOwn() {
        val long = "a1b2c3d4e5".repeat(8) + "@mint.example"
        compose.setContent { Box(Modifier.width(200.dp)) { AddressText(long) } }
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(long).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layout)
        val result = layout.single()
        val text = result.layoutInput.text.text
        val last = text.substring(result.getLineStart(result.lineCount - 1), result.getLineEnd(result.lineCount - 1))
        assertEquals("@mint.example", last.filter { c -> c != '\u200B' })
    }
}
