package whitewallet.core.designsystem

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.brand.ReleaseStage

@RunWith(RobolectricTestRunner::class)
class ReleaseBadgeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aTitleWithItsBadgeCanBeMeasuredForItsNaturalWidth() {
        // A layout that asks how wide the title would like to be (the top bar does, to see whether it fits) gets an answer.
        compose.setContent {
            CompositionLocalProvider(LocalReleaseStage provides ReleaseStage.Beta) {
                WithReleaseBadge(Modifier.width(IntrinsicSize.Max), centered = false) { Text("Wallet") }
            }
        }
        compose.onNodeWithText("Wallet").assertExists()
    }
}
