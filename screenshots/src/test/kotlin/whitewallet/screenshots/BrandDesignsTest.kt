package whitewallet.screenshots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import whitewallet.screenshots.fixtures.FixtureDesign

class BrandDesignsTest {
    private fun find(fixture: String) = BrandDesigns.find { acceptPackages("whitewallet.screenshots.fixtures.$fixture") }

    @Test
    fun providedByAnObject() =
        assertEquals(FixtureDesign, BrandDesigns.find { acceptPackagesNonRecursive("whitewallet.screenshots.fixtures") })

    @Test fun boundToAnObject() = assertEquals(FixtureDesign, find("binds"))

    @Test fun providedFromACompanion() = assertEquals(FixtureDesign, find("companion"))

    @Test fun codeWithoutADesignDrawsTheDefault() = assertNull(find("nodesign"))

    @Test
    fun aDesignThatNeedsSomethingInjectedSaysSo() {
        val error = assertThrows(IllegalStateException::class.java) { find("injected") }
        assertTrue(error.message, error.message!!.contains("needs something injected"))
    }
}
