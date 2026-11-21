package whitewallet.app

import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import whitewallet.brand.ui.BrandScreen
import whitewallet.brand.ui.BrandScreenEntry

/** Which page of the brand shows between a wallet's setup and the wallet. */
class BrandScreensTest {
    private class Page(override val id: String, override val entry: BrandScreenEntry) : BrandScreen {
        override val title = 0

        @Composable override fun Content(onBack: () -> Unit) = Unit
    }

    @Test
    fun `a page after onboarding shows for the phone's first wallet only`() {
        val screens = BrandScreens(setOf(Page("welcome", BrandScreenEntry.AfterOnboarding), Page("about", BrandScreenEntry.Settings)))
        assertEquals("welcome", screens.afterSetup(firstWallet = true)?.id)
        assertNull(screens.afterSetup(firstWallet = false))
    }

    @Test
    fun `a page after every setup shows for an added wallet too`() {
        val screens = BrandScreens(setOf(Page("welcome", BrandScreenEntry.AfterEverySetup)))
        assertEquals("welcome", screens.afterSetup(firstWallet = true)?.id)
        assertEquals("welcome", screens.afterSetup(firstWallet = false)?.id)
    }

    @Test
    fun `a brand without such a page goes straight to the wallet`() {
        val screens = BrandScreens(setOf(Page("about", BrandScreenEntry.Settings), Page("help", BrandScreenEntry.HomeMenu)))
        assertNull(screens.afterSetup(firstWallet = true))
        assertNull(screens.afterSetup(firstWallet = false))
    }

    @Test
    fun `a brand has at most one page after a setup, of either kind`() {
        assertFailsWith<IllegalStateException> {
            BrandScreens(setOf(Page("a", BrandScreenEntry.AfterOnboarding), Page("b", BrandScreenEntry.AfterEverySetup)))
        }
        assertFailsWith<IllegalStateException> {
            BrandScreens(setOf(Page("a", BrandScreenEntry.AfterEverySetup), Page("b", BrandScreenEntry.AfterEverySetup)))
        }
    }
}
