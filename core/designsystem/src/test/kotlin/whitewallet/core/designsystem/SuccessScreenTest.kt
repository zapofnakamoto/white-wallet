package whitewallet.core.designsystem

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign

@RunWith(RobolectricTestRunner::class)
class SuccessScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theBrandIsAskedForTheAnchorWithTheStatus() {
        val decorated = mutableListOf<Pair<Anchor, WalletStatus>>()
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    decorated += anchor to context.status
                }
            }
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design) {
                SuccessScreen(Anchor.MintAdded, WalletStatus.Done(0), "Mint added", SuccessAction("Done") {})
            }
        }
        compose.onNodeWithText("Mint added").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertEquals(listOf(Anchor.MintAdded to WalletStatus.Done(0)), decorated.distinct())
    }

    @Test
    fun withoutAnAmountOrSecondButtonOnlyTheDoneButtonShows() {
        var done = false
        compose.setContent {
            SuccessScreen(
                Anchor.ScanDone,
                WalletStatus.Done(0),
                "Scan finished",
                SuccessAction("Done") { done = true },
                lines = listOf("Nothing new found."),
            )
        }
        compose.onNodeWithText("Nothing new found.").assertExists()
        compose.onAllNodesWithText("Done").assertCountEquals(1)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("Done").performClick()
        assertTrue(done)
    }

    @Test
    fun theButtonsStayOnScreenWhenTheContentIsTall() {
        compose.setContent {
            SuccessScreen(
                Anchor.ScanDone,
                WalletStatus.Done(0),
                "Scan finished",
                SuccessAction("Done") {},
                more = SuccessAction("Again") {},
                content = { repeat(60) { Text("Line $it") } },
            )
        }
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithText("Again").assertIsDisplayed()
    }

    @Test
    fun backDoesWhatDoneDoes() {
        var done = false
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            SuccessScreen(Anchor.AccountCreated, WalletStatus.Done(0), "Account created", SuccessAction("Open account") { done = true })
        }
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(done)
    }
}
