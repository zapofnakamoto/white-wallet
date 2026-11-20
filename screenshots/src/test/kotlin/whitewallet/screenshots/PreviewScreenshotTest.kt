package whitewallet.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import whitewallet.core.designsystem.EmptyState

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun emptyState() {
        compose.setContent { EmptyState("Nothing here") }
        compose.onRoot().captureRoboImage(File(System.getProperty("screenshots.dir"), "EmptyState.png").path)
    }
}
