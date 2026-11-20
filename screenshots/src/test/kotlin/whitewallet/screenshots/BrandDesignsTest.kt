package whitewallet.screenshots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import whitewallet.brand.ui.DefaultBrandDesign

class BrandDesignsTest {
    @Test
    fun theBrandsOwnDesignWhenItsCodeProvidesOneElseTheDefault() {
        if (System.getProperty("screenshots.brandCode").toBoolean()) {
            assertNotEquals(DefaultBrandDesign, BrandDesigns.design)
        } else {
            assertEquals(DefaultBrandDesign, BrandDesigns.design)
        }
    }
}
