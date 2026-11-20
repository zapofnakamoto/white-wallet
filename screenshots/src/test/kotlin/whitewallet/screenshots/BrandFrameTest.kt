package whitewallet.screenshots

import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.brand.ui.DefaultBrandDesign

@RunWith(RobolectricTestRunner::class)
class BrandFrameTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun drawsInInspectionModeAsPreviewsDo() {
        var inspecting = false
        compose.setContent { BrandFrame(DefaultBrandDesign) { inspecting = LocalInspectionMode.current } }
        compose.waitForIdle()
        assertTrue("a design's random moves can only stay put in inspection mode", inspecting)
    }
}
