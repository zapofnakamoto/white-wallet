package whitewallet.core.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import whitewallet.brand.ui.Page

@RunWith(RobolectricTestRunner::class)
class EmptyStateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun anEmptyStateSaysWhyAndOffersTheWayForward() {
        val decorated = mutableListOf<Pair<Anchor, WalletStatus>>()
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    decorated += anchor to context.status
                }
            }
        var clicked = false
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design) {
                EmptyState("Nothing yet", anchor = Anchor.NotesEmpty) { PrimaryButton("Do it", { clicked = true }) }
            }
        }
        compose.onNodeWithText("Nothing yet").assertExists()
        compose.onNodeWithText("Do it").performClick()
        assertTrue(clicked)
        assertEquals(listOf(Anchor.NotesEmpty to WalletStatus.Empty), decorated.distinct())
    }

    @Test
    fun withoutAnAnchorNoDecorationIsAskedFor() {
        val decorated = mutableListOf<Anchor>()
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    decorated += anchor
                }
            }
        compose.setContent { CompositionLocalProvider(LocalBrandDesign provides design) { EmptyState("Nothing yet") } }
        compose.onNodeWithText("Nothing yet").assertExists()
        assertEquals(emptyList<Anchor>(), decorated)
    }

    @Test
    fun withoutABrandTheBodyIsThePage() {
        compose.setContent { BrandBody(Page.Mints, WalletStatus.Empty) { Text("the page") } }
        compose.onNodeWithText("the page").assertExists()
    }

    @Test
    fun aBrandWrapsTheBodyKnowingThePageAndWhatItIsAbout() {
        val seen = mutableListOf<Pair<Page, WalletStatus>>()
        val design =
            object : BrandDesign {
                @Composable
                override fun Body(page: Page, context: DecorationContext, content: @Composable () -> Unit) {
                    seen += page to context.status
                    Text("frame")
                    content()
                }
            }
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design) { BrandBody(Page.Archive, WalletStatus.Empty) { Text("the page") } }
        }
        compose.onNodeWithText("frame").assertExists()
        compose.onNodeWithText("the page").assertExists()
        assertEquals(listOf(Page.Archive to WalletStatus.Empty), seen.distinct())
    }
}
