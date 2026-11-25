package whitewallet.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.After
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview
import whitewallet.core.designsystem.warmHostText

/**
 * Every text of every preview, light and dark, in the brand's design, stands out from what is drawn behind it: at least 4.5:1, or 3:1 for
 * large text (WCAG AA). It measures the image, not the colour roles, so it finds a brand colour that clashes as well as code that puts a
 * colour on a background it wasn't made for (a snackbar's button in the theme's primary on the snackbar's inverse surface). A disabled
 * control is exempt, as in WCAG.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContrastTest(private val preview: Look) {
    /** One preview in one of its looks, light or dark. */
    class Look(val preview: ComposablePreview<AndroidPreviewInfo>) {
        override fun toString() = Previews.fileName(preview, Previews.SETTLED_MILLIS).removeSuffix(".png")
    }

    @get:Rule val compose = createComposeRule()

    private val locale = Locale.getDefault()
    private val timeZone = TimeZone.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(locale)
        TimeZone.setDefault(timeZone)
    }

    @Test
    fun textStandsOut() {
        val info = preview.preview.previewInfo
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        RuntimeEnvironment.setQualifiers(PreviewScreenshotTest.qualifiers(info))
        RuntimeEnvironment.setFontScale(1f)
        warmHostText(RuntimeEnvironment.getApplication())
        compose.mainClock.autoAdvance = false
        compose.setContent { BrandFrame(BrandDesigns.design) { preview.preview() } }
        compose.mainClock.advanceTimeBy(Previews.SETTLED_MILLIS)
        // A dialog or sheet is a window of its own, over the screen. Only the topmost is read, drawn on its own: what is under it is dimmed
        // on purpose, and has a preview of its own without it.
        val top = topWindow()
        val image = Bitmap.createBitmap(top.view.width, top.view.height, Bitmap.Config.ARGB_8888)
        top.view.draw(Canvas(image))
        val faint = texts(top.semanticsOwner.unmergedRootSemanticsNode).mapNotNull { faint(it, image) }
        if (faint.isNotEmpty()) fail("Text hard to read in $preview:\n" + faint.joinToString("\n") { "  $it" })
    }

    /** The Compose view of the window on top: the last window opened. */
    private fun topWindow(): ViewRootForTest {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val windows = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>
        fun find(view: View): ViewRootForTest? =
            view as? ViewRootForTest
                ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
        return windows.filterIsInstance<View>().mapNotNull(::find).last()
    }

    private fun texts(node: SemanticsNode): List<SemanticsNode> =
        (if (node.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null) listOf(node) else emptyList()) +
            node.children.flatMap(::texts)

    /** What is wrong with [node]'s text in [image], or null when it stands out (or is disabled, or not on screen). */
    private fun faint(node: SemanticsNode, image: Bitmap): String? {
        if (generateSequence(node) { it.parent }.any { SemanticsProperties.Disabled in it.config }) return null
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("")?.takeIf { it.isNotBlank() } ?: return null
        val layout =
            mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.firstOrNull()
                ?: return null
        val bounds = node.boundsInRoot
        var worst: Pair<Double, String>? = null
        // Each line across the text's own width: a line's left and right can be the layout's, wider than the text (centred text).
        for (line in 0 until layout.lineCount) {
            val box = Rect(bounds.left, bounds.top + layout.getLineTop(line), bounds.right, bounds.top + layout.getLineBottom(line))
            val (ratio, colours) = contrast(image, box) ?: continue
            if (worst == null || ratio < worst.first) worst = ratio to colours
        }
        val (ratio, colours) = worst ?: return null
        val style = layout.layoutInput.style
        val bold = (style.fontWeight ?: FontWeight.Normal) >= FontWeight.Bold
        val large = style.fontSize.value >= 24f || (bold && style.fontSize.value >= 18.66f)
        val needed = if (large) 3.0 else 4.5
        return if (ratio >= needed) null else "\"${text.take(40)}\": %.2f:1, needs %.1f:1 ($colours)".format(Locale.ROOT, ratio, needed)
    }

    /**
     * How far the text in [box] stands out: the colour seen most there is the background, the one furthest from it the text's (its glyphs'
     * insides; their edges are blended). Null when nothing of [box] is on screen, or it is one colour (nothing drawn).
     */
    private fun contrast(image: Bitmap, box: Rect): Pair<Double, String>? {
        val left = max(0, box.left.toInt())
        val top = max(0, box.top.toInt())
        val right = min(image.width, box.right.toInt())
        val bottom = min(image.height, box.bottom.toInt())
        if (right - left < 2 || bottom - top < 2) return null
        val pixels = IntArray((right - left) * (bottom - top))
        image.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
        val background = pixels.groupBy { it }.maxBy { it.value.size }.key
        val text = pixels.maxBy { ratio(it, background) }
        if (text == background) return null
        return ratio(text, background) to "#%06X on #%06X".format(text and 0xFFFFFF, background and 0xFFFFFF)
    }

    private fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Look> =
            Previews.all
                .filter { it.previewInfo.name == "light" || (it.previewInfo.name == "dark" && BuildConfig.APPEARANCE == "system") }
                .map(::Look)
    }
}
