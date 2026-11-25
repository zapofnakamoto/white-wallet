package whitewallet.core.designsystem

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A mint that can't be picked because its signing key doesn't match says so the same way as everywhere: the warning, not plain words. */
@RunWith(RobolectricTestRunner::class)
class MintChoiceTest {
    @get:Rule val compose = createComposeRule()

    private val mismatch = RuntimeEnvironment.getApplication().getString(R.string.key_mismatch_short)

    private fun show(vararg options: MintOption) = compose.setContent {
        MintChoiceSheet("Pick", options.toList(), selected = null, onSelect = {}, onDismiss = {})
    }

    @Test
    fun aMintWhoseKeyChangedShowsTheWarningAndCannotBePicked() {
        show(MintOption("a.example", home = false, detail = null, enabled = false, keyChanged = true))
        compose.onNode(hasTestTag(MINT_KEY_WARNING_TAG), useUnmergedTree = true).assertExists()
        compose.onNode(hasText(mismatch, substring = true)).assertExists()
        compose.onNode(hasText("a.example", substring = true)).assertIsNotEnabled()
    }

    @Test
    fun theWarningTakesThePlaceOfTheDetail() {
        show(MintOption("a.example", home = false, detail = "Up to ₿5", enabled = false, keyChanged = true))
        compose.onNodeWithText("Up to ₿5").assertDoesNotExist()
    }

    @Test
    fun aMintWithAMatchingKeyShowsNoWarning() {
        show(MintOption("a.example", home = true, detail = "Home mint · up to ₿5"))
        compose.onNode(hasTestTag(MINT_KEY_WARNING_TAG), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("Home mint · up to ₿5", substring = true)).assertExists()
        compose.onNode(hasText("a.example", substring = true)).assertIsEnabled()
    }

    @Test
    fun aMintThatDidntAnswerSaysSoUnderItsLineAndCanStillBePicked() {
        show(MintOption("a.example", home = false, detail = "Up to ₿5", answering = false))
        val notAnswering = RuntimeEnvironment.getApplication().getString(R.string.mint_not_answering_short)
        compose.onNode(hasText("Up to ₿5", substring = true)).assertExists()
        compose.onNode(hasText(notAnswering, substring = true)).assertExists()
        compose.onNode(hasText("a.example", substring = true)).assertIsEnabled()
    }
}
