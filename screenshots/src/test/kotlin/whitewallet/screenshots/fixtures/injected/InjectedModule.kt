package whitewallet.screenshots.fixtures.injected

import dagger.Module
import dagger.Provides
import whitewallet.brand.ui.BrandDesign
import whitewallet.screenshots.fixtures.FixtureDesign
import whitewallet.screenshots.fixtures.Strategy

/** A design made from something injected: screenshots can't make it. */
@Module
object InjectedModule {
    @Provides fun design(strategy: Strategy): BrandDesign = FixtureDesign
}
