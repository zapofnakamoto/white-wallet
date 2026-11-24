package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MintMarkTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theMarkBesideAMintsNameAddsNothingToWhatIsReadOut() {
        compose.setContent {
            Row(Modifier.semantics(mergeDescendants = true) {}.testTag("row")) {
                WalletMintMark("mint.example", home = true)
                Text("mint.example")
            }
        }
        val config = compose.onNodeWithTag("row").fetchSemanticsNode().config
        assertEquals(listOf("mint.example"), config[SemanticsProperties.Text].map { it.text })
        assertFalse(config.contains(SemanticsProperties.ContentDescription))
    }

    @Test
    fun aPickedMintIsStillARadioButtonReadByItsNameWithItsMarkBeside() {
        var picked: String? = "none"
        compose.setContent {
            MintChoiceSheet(
                title = "Pay from",
                options =
                    listOf(MintOption("mint.example", home = true, detail = null), MintOption("cash.example", home = false, "Up to ₿5")),
                selected = "mint.example",
                onSelect = { picked = it },
                onDismiss = {},
                automatic = "Automatic" to "The wallet picks",
            )
        }
        compose.onNodeWithText("mint.example").assertIsSelected()
        compose.onNodeWithText("Automatic").assertExists()
        compose.onNodeWithText("cash.example").performClick()
        assertEquals("cash.example", picked)
    }
}
