package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// Native graphics: text is measured as on a phone, so a word's width is real.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListRowTest {
    @get:Rule val compose = createComposeRule()

    private fun row(trailingWidth: Int) = compose.setContent {
        Box(Modifier.width(300.dp)) {
            WalletListItem(
                headlineContent = { Text("mint.example") },
                trailingContent = { Box(Modifier.size(trailingWidth.dp, 20.dp).testTag("trailing")) },
            )
        }
    }

    /** Where [matcher]'s node is drawn: the row also composes its parts once more to measure them, never placing those. */
    private fun placed(matcher: SemanticsMatcher): Rect =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().single { it.layoutInfo.isPlaced }.boundsInRoot

    @Test
    fun aRightSideThatWouldSplitAWordGoesBelowTheText() {
        // A large font, or a long action: beside it, the host would break mid-word.
        row(trailingWidth = 220)
        val text = placed(hasText("mint.example"))
        val trailing = placed(hasTestTag("trailing"))
        assertTrue("$trailing below $text", trailing.top >= text.bottom)
    }

    @Test
    fun aRightSideThatLeavesRoomStaysBeside() {
        row(trailingWidth = 40)
        val text = placed(hasText("mint.example"))
        val trailing = placed(hasTestTag("trailing"))
        assertTrue("$trailing beside $text", trailing.top < text.bottom && trailing.left >= text.right)
    }
}
