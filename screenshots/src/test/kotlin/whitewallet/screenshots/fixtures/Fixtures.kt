package whitewallet.screenshots.fixtures

import dagger.Module
import dagger.Provides
import whitewallet.brand.ui.BrandDesign

// Brand code modules as brands write them (docs/WHITE_LABEL.md, "Custom code"), one per package, for BrandDesignsTest.

object FixtureDesign : BrandDesign

object Strategy

/** `@Provides fun design(): BrandDesign` in an object. */
@Module
object ProvidesModule {
    @Provides fun design(): BrandDesign = FixtureDesign
}
