package whitewallet.core.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign

@RunWith(RobolectricTestRunner::class)
class LoadingTest {
    @get:Rule val compose = createComposeRule()

    private val loading = RuntimeEnvironment.getApplication().getString(R.string.loading)

    /** A page that is loaded once the returned function is called with true. */
    private fun page(loaded: Boolean = false, whole: Boolean = false): (Boolean) -> Unit {
        var isLoaded by mutableStateOf(loaded)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLoadingTimes provides LoadingTimes(shownAfterMillis = 400, shownAtLeastMillis = 500)) {
                if (if (whole) screenLoaded(isLoaded) else pageLoaded(isLoaded)) Text("the page")
            }
        }
        compose.mainClock.advanceTimeByFrame()
        return {
            isLoaded = it
            Snapshot.sendApplyNotifications()
        }
    }

    private fun assertBlank() {
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()
        compose.onNodeWithText("the page").assertDoesNotExist()
    }

    private fun assertIndicator() {
        compose.onNodeWithContentDescription(loading).assertExists()
        compose.onNodeWithText("the page").assertDoesNotExist()
    }

    private fun assertPage() {
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()
        compose.onNodeWithText("the page").assertExists()
    }

    @Test
    fun aLoadedPageShowsAtOnce() {
        page(loaded = true)
        assertPage()
    }

    @Test
    fun aPageLoadedInAMomentNeverShowsTheIndicator() {
        val load = page()
        assertBlank()
        compose.mainClock.advanceTimeBy(300)
        assertBlank()
        load(true)
        compose.mainClock.advanceTimeByFrame()
        assertPage()
        // Nor later: the wait for the indicator ended with the loading.
        compose.mainClock.advanceTimeBy(1_000)
        assertPage()
    }

    @Test
    fun aPageLoadedJustAfterTheIndicatorCameUpKeepsItForItsShortestTime() {
        val load = page()
        compose.mainClock.advanceTimeBy(405)
        assertIndicator()
        load(true)
        compose.mainClock.advanceTimeBy(400)
        assertIndicator()
        compose.mainClock.advanceTimeBy(150)
        assertPage()
    }

    @Test
    fun aPageLoadedAfterTheShortestTimeShowsAtOnce() {
        val load = page()
        compose.mainClock.advanceTimeBy(2_000)
        assertIndicator()
        load(true)
        compose.mainClock.advanceTimeByFrame()
        assertPage()
    }

    @Test
    fun loadingAgainStartsOver() {
        val load = page(loaded = true)
        load(false)
        compose.mainClock.advanceTimeBy(300)
        assertBlank()
        compose.mainClock.advanceTimeBy(200)
        assertIndicator()
        load(true)
        compose.mainClock.advanceTimeBy(600)
        assertPage()
    }

    @Test
    fun aWholeScreenFollowsTheSameRule() {
        val load = page(whole = true)
        assertBlank()
        compose.mainClock.advanceTimeBy(405)
        assertIndicator()
        load(true)
        compose.mainClock.advanceTimeBy(400)
        assertIndicator()
        compose.mainClock.advanceTimeBy(150)
        assertPage()
    }

    @Test
    fun workOnAPageThatStaysShowsByTheSameRule() {
        var working by mutableStateOf(false)
        fun set(value: Boolean) {
            working = value
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Text("the page")
            if (showsWorking(working)) Text("working")
        }
        // Quick work: no sign of it.
        set(true)
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("working").assertDoesNotExist()
        set(false)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("working").assertDoesNotExist()
        // Work that ends just after its sign came up: the sign stays for its shortest time.
        set(true)
        compose.mainClock.advanceTimeBy(450)
        compose.onNodeWithText("working").assertExists()
        set(false)
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("working").assertExists()
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithText("working").assertDoesNotExist()
        compose.onNodeWithText("the page").assertExists()
    }

    @Test
    fun aWaitThatGoesOnInTheNextPlaceIsOneWait() {
        var now = 0L
        val carry = LoadingCarry { now }
        var place by mutableStateOf(1)
        var loaded by mutableStateOf(false)
        fun apply(block: () -> Unit) {
            block()
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLoadingCarry provides carry) {
                if (place == 1) {
                    if (pageLoaded(false)) Text("first")
                } else {
                    if (pageLoaded(loaded)) Text("second")
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        assertIndicator()
        // The next place loads too: its indicator is up from its first frame.
        apply { place = 2 }
        now += 100
        compose.mainClock.advanceTimeByFrame()
        assertIndicator()
        apply { loaded = true }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("second").assertExists()
        // Long after, a wait starts over with its moment of nothing.
        now += 10_000
        apply { loaded = false }
        compose.mainClock.advanceTimeBy(200)
        assertBlank()
    }

    @Test
    fun aBrandDrawsTheIndicator() {
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    if (anchor == Anchor.PageLoading) Text("brand loading") else default()
                }
            }
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalBrandDesign provides design) { pageLoaded(false) } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("brand loading", useUnmergedTree = true).assertExists()
        // Still "Loading" to a screen reader, whatever the picture.
        compose.onNodeWithContentDescription(loading).assertExists()
    }
}
