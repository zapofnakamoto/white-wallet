package whitewallet.app

import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import whitewallet.brand.ReleaseNote
import whitewallet.brand.ReleaseNotes
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.ReleaseNoteTracker
import whitewallet.core.wallet.Wallet

/** The app's own release notes, newest first. A brand that binds [ReleaseNotes] shows its own instead. */
object DefaultReleaseNotes : ReleaseNotes {
    override fun notes(): List<ReleaseNote> =
        listOf(ReleaseNote("old-keys-removed", DesignR.string.release_note_old_keys_title, DesignR.string.release_note_old_keys_body))
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReleaseNotesModule {
    /** A brand's code module may bind its own release notes (see docs/WHITE_LABEL.md). */
    @BindsOptionalOf abstract fun brandReleaseNotes(): ReleaseNotes

    companion object {
        @Provides
        @Singleton
        fun releaseNoteTracker(brand: Optional<ReleaseNotes>, preferences: AppPreferences, wallet: Wallet): ReleaseNoteTracker =
            ReleaseNoteTracker(brand.orElse(DefaultReleaseNotes).notes(), preferences, hasWallet = { wallet.accounts.first().isNotEmpty() })
    }
}
