package whitewallet.screenshots.fixtures.companion

import dagger.Module
import dagger.Provides
import whitewallet.brand.ui.BrandDesign
import whitewallet.screenshots.fixtures.FixtureDesign

/** `@Provides` in the companion of an abstract module that also has `@Binds`. */
@Module
abstract class CompanionModule {
    companion object {
        @Provides fun design(): BrandDesign = FixtureDesign
    }
}
