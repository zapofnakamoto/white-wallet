package whitewallet.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview
import whitewallet.core.designsystem.warmHostText

/** One image: a preview in one configuration, at one moment. */
class Shot(val preview: ComposablePreview<AndroidPreviewInfo>, val millis: Long) {
    override fun toString() = Previews.fileName(preview, millis)
}

/**
 * Draws every selected preview (-Pscreenshots=check or full, see [Previews.selected]) in the brand's design, as its app would, into
 * build/screenshots/render/; dev/screenshots.py files it under build/screenshots/<set>/<brand>/current/.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewScreenshotTest(private val shot: Shot) {
    @get:Rule val compose = createComposeRule()

    private val locale = Locale.getDefault()
    private val timeZone = TimeZone.getDefault()

    // Numbers and dates follow the phone's language, as they do in the app; times are UTC, so every machine draws the same hour.
    @After
    fun restoreLocale() {
        Locale.setDefault(locale)
        TimeZone.setDefault(timeZone)
    }

    @Test
    fun capture() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        shot.preview.previewInfo.locale.ifEmpty { null }?.let { Locale.setDefault(Locale.forLanguageTag(it)) }
        RuntimeEnvironment.setQualifiers(qualifiers(shot.preview.previewInfo))
        RuntimeEnvironment.setFontScale(shot.preview.previewInfo.fontScale.takeIf { it > 0 } ?: 1f)
        // Hosts show their distinctive part as in the app, which reads the public suffix list at its start.
        warmHostText(RuntimeEnvironment.getApplication())
        // The clock moves only as far as the moment drawn, so endless animations (a brand's breathing mark) come out the same each time.
        compose.mainClock.autoAdvance = false
        compose.setContent { BrandFrame(BrandDesigns.design) { shot.preview() } }
        compose.mainClock.advanceTimeBy(shot.millis)
        compose.onRoot().captureRoboImage(File(System.getProperty("screenshots.dir"), shot.toString()).path)
    }

    companion object {
        /** A brand with a fixed app.appearance looks only that way: its other look is never drawn. */
        private val appearance = BuildConfig.APPEARANCE

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Shot> {
            val set = System.getProperty("screenshots.set", "check")
            return Previews.all
                .filter { Previews.selected(it, set) }
                .filter { appearance == "system" || it.previewInfo.name != "dark" }
                .flatMap { preview -> Previews.frames(preview, set).map { Shot(preview, it) } }
        }

        /** A common phone (Pixel size, at twice the base density), or the preview's device; its language; light or dark. */
        fun qualifiers(info: AndroidPreviewInfo): String {
            val size = if (info.device.contains("width=360dp")) "w360dp-h640dp" else "w411dp-h891dp"
            val night =
                (info.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            val dark =
                when (appearance) {
                    "dark" -> true
                    "light" -> false
                    else -> night
                }
            return listOfNotNull(info.locale.ifEmpty { null }, size, if (dark) "night" else "notnight", "xhdpi").joinToString("-")
        }
    }
}
