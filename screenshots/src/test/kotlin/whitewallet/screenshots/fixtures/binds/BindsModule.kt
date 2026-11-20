package whitewallet.screenshots.fixtures.binds

import dagger.Binds
import dagger.Module
import whitewallet.brand.ui.BrandDesign
import whitewallet.screenshots.fixtures.FixtureDesign

/** `@Binds` to an object. */
@Module
abstract class BindsModule {
    @Binds abstract fun design(design: FixtureDesign): BrandDesign
}
