package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
class TopBarTest {
    @get:Rule val compose = createComposeRule()

    private fun bar(actionsWidth: Int) = compose.setContent {
        Box(Modifier.width(300.dp)) {
            WalletTopBar("Request log", onBack = {}) { Box(Modifier.size(actionsWidth.dp, 40.dp).testTag("actions")) }
        }
    }

    /** Where [matcher]'s node is drawn: the bar also composes its parts once more to measure them, never placing those. */
    private fun placed(matcher: SemanticsMatcher): Rect =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().single { it.layoutInfo.isPlaced }.boundsInRoot

    @Test
    fun aTitleThatWouldSplitAWordGetsALineOfItsOwnUnderTheActions() {
        // A large font, or long actions: beside them, the title would break mid-word ("Reques" / "t log").
        bar(actionsWidth = 190)
        val title = placed(hasText("Request log"))
        val actions = placed(hasTestTag("actions"))
        assertTrue("$title under $actions", title.top >= actions.bottom)
        assertTrue("$title on one line", title.height < 2 * 28)
    }

    @Test
    fun aTitleWithRoomStaysBesideTheActions() {
        bar(actionsWidth = 48)
        val title = placed(hasText("Request log"))
        val actions = placed(hasTestTag("actions"))
        assertTrue("$title beside $actions", title.top < actions.bottom && title.right <= actions.left)
    }
}
