package whitewallet.core.designsystem

import android.graphics.Typeface
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

// Robolectric's own typefaces have no weight: only Android's do.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandFontTest {
    private val family = BrandTokens.family(Typeface.DEFAULT) as FontListFontFamily

    @Test
    fun `a brand's font family offers every weight, upright and italic`() {
        val offered = family.fonts.map { it.weight.weight to it.style }.toSet()
        for (weight in 100..900 step 100) {
            assertTrue("$weight upright", weight to FontStyle.Normal in offered)
            assertTrue("$weight italic", weight to FontStyle.Italic in offered)
        }
    }

    @Test
    fun `a text's weight reaches the brand's font, which a typeface on its own would drop`() {
        val context = RuntimeEnvironment.getApplication()
        for (weight in listOf(400, 600, 700)) {
            val font = family.fonts.first { it.weight == FontWeight(weight) && it.style == FontStyle.Normal } as AndroidFont
            val loaded = font.typefaceLoader.loadBlocking(context, font)!!
            assertEquals(weight, loaded.weight)
            assertEquals(false, loaded.isItalic)
        }
        val italic = family.fonts.first { it.weight == FontWeight.Bold && it.style == FontStyle.Italic } as AndroidFont
        assertEquals(true, italic.typefaceLoader.loadBlocking(context, italic)!!.isItalic)
    }
}
