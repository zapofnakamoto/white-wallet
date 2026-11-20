package whitewallet.screenshots.fixtures.nodesign

import dagger.Module
import dagger.Provides
import whitewallet.screenshots.fixtures.Strategy

/** Code of its own, but no design: the app draws the default one. */
@Module
object NoDesignModule {
    @Provides fun strategy(): Strategy = Strategy
}
