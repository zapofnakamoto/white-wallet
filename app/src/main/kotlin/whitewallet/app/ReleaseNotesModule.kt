package whitewallet.app

import android.content.Context
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Singleton
import whitewallet.brand.ReleaseNote
import whitewallet.brand.ReleaseNotes
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.ReleaseNoteTracker

/** The app's own release notes, newest first. A brand that binds [ReleaseNotes] shows its own instead. */
object DefaultReleaseNotes : ReleaseNotes {
    override fun notes(): List<ReleaseNote> = emptyList()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReleaseNotesModule {
    /** A brand's code module may bind its own release notes (see docs/WHITE_LABEL.md). */
    @BindsOptionalOf
    abstract fun brandReleaseNotes(): ReleaseNotes

    companion object {
        @Provides
        @Singleton
        fun releaseNoteTracker(@ApplicationContext context: Context, brand: Optional<ReleaseNotes>, preferences: AppPreferences): ReleaseNoteTracker {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return ReleaseNoteTracker(brand.orElse(DefaultReleaseNotes).notes(), preferences, freshInstall = info.firstInstallTime == info.lastUpdateTime)
        }
    }
}
