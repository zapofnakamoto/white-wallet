package whitewallet.core.data

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.MintTrustStore
import whitewallet.core.wallet.NotePolicies
import whitewallet.core.wallet.SeedStore
import whitewallet.core.wallet.WalletStore

private val Context.secretsDataStore by preferencesDataStore(name = "secrets")
private val Context.settingsDataStore by preferencesDataStore(name = "settings", produceMigrations = { DataStorePreferences.MIGRATIONS })

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    private const val DATABASE = "wallet.db"

    private fun encrypted(context: Context): SupportOpenHelperFactory {
        System.loadLibrary("sqlcipher")
        return SupportOpenHelperFactory(DatabaseKey.passphrase(context, DATABASE))
    }

    @Provides
    @Singleton
    internal fun database(@ApplicationContext context: Context): WalletDatabase =
        // Since v0.3.0 (version 2) every schema change has a migration. Older development installs (version 1, or
        // before the reset to it) start empty; seeds are kept.
        Room.databaseBuilder(context, WalletDatabase::class.java, DATABASE)
            .openHelperFactory(encrypted(context))
            .addMigrations(*WalletDatabase.MIGRATIONS)
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    internal fun walletStore(db: WalletDatabase): WalletStore = RoomWalletStore(db)

    @Provides
    @Singleton
    fun seedStore(@ApplicationContext context: Context): SeedStore = KeystoreSeedStore(context.secretsDataStore)

    @Provides
    @Singleton
    internal fun preferences(@ApplicationContext context: Context): DataStorePreferences {
        // The app's own build type (a library's BuildConfig.DEBUG would not tell): debug builds start in developer mode.
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return DataStorePreferences(context.settingsDataStore, developerModeByDefault = debuggable)
    }

    @Provides
    internal fun appPreferences(preferences: DataStorePreferences): AppPreferences = preferences

    @Provides
    internal fun notePolicies(preferences: DataStorePreferences): NotePolicies = preferences

    @Provides
    internal fun mintTrust(preferences: DataStorePreferences): MintTrustStore = preferences
}
