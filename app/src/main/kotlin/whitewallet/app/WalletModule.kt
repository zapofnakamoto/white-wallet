package whitewallet.app

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import whitewallet.brand.NoteStrategy
import whitewallet.core.model.AmountUnit
import whitewallet.core.wallet.AddressPolicy
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppLockPolicy
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.BackupRequired
import whitewallet.core.wallet.CollectDefaults
import whitewallet.core.wallet.DeveloperOptions
import whitewallet.core.wallet.HistoryFilterOption
import whitewallet.core.wallet.MaintenanceNoticeDefaults
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.MintTrustStore
import whitewallet.core.wallet.NoteAction
import whitewallet.core.wallet.NotePolicies
import whitewallet.core.wallet.NoteStrategies
import whitewallet.core.wallet.NotesView
import whitewallet.core.wallet.RegtestFaucet
import whitewallet.core.wallet.SeedStore
import whitewallet.core.wallet.SingleNoteStrategy
import whitewallet.core.wallet.Timings
import whitewallet.core.wallet.TrustedMints
import whitewallet.core.wallet.Wallet
import whitewallet.core.wallet.WalletStore
import whitewallet.core.wallet.releaseStageFromConfig
import whitewallet.feature.settings.R as SettingsR
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.NetworkConditions
import whitewallet.lnurlcash.client.RequestLog

@Module
@InstallIn(SingletonComponent::class)
object WalletModule {
    /** In-memory log of every request to a mint, shown under Developer options. */
    @Provides @Singleton fun requestLog(): RequestLog = RequestLog()

    /** A bad connection for developers to try the app on (Developer options); unknown until [NetworkSimulation] starts. */
    @Provides @Singleton fun networkConditions(): NetworkConditions = NetworkConditions(initial = null)

    @Provides
    @Singleton
    fun client(log: RequestLog, conditions: NetworkConditions): LnurlcashClient =
        // newBuilder keeps the default client's settings, including retryOnConnectionFailure(false). The log comes
        // first, so it shows a simulated delay or drop as the app saw it.
        LnurlcashClient(
            LnurlcashClient.defaultHttpClient()
                .newBuilder()
                .connectTimeout(BuildConfig.CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .readTimeout(BuildConfig.READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .addInterceptor(log.interceptor())
                .addInterceptor(conditions.interceptor())
                .build()
        )

    @Provides
    @Singleton
    fun wallet(
        @ApplicationContext context: Context,
        store: WalletStore,
        seeds: SeedStore,
        client: LnurlcashClient,
        policies: NotePolicies,
        strategies: NoteStrategies,
        trust: MintTrustStore,
    ): Wallet =
        Wallet(
            store,
            seeds,
            client,
            policies = policies,
            strategies = strategies,
            trust = trust,
            brandTrusted = TrustedMints.parse(BuildConfig.TRUSTED_MINTS),
            maxAccounts = AppDefaults.limit(BuildConfig.MAX_ACCOUNTS),
            requireAddress = AddressPolicy.fromConfig(BuildConfig.LIGHTNING_ADDRESS) == AddressPolicy.Required,
            collectDefaults =
                CollectDefaults.fromConfig(
                    BuildConfig.COLLECT_AT_HOME_MINT,
                    BuildConfig.COLLECT_FEE_CAP,
                    BuildConfig.COLLECT_FEE_CAPS,
                    BuildConfig.COLLECT_RETRY_MILLIS,
                ),
            onWaitingForNetwork = { SyncWorker.scheduleOnce(context) },
            payWaitMillis = BuildConfig.SYNC_PAYMENT_WAIT_MILLIS,
            receivedGiveUpMillis = BuildConfig.RECEIVED_GIVE_UP_MILLIS,
        )

    /** dev/regtest's faucet in debug builds; without a host (release) it pays nothing. */
    @Provides
    @Singleton
    fun regtestFaucet(wallet: Wallet, preferences: AppPreferences, defaults: AppDefaults): RegtestFaucet =
        RegtestFaucet(BuildConfig.LOCAL_DEV_FAUCET.ifEmpty { null }, preferences.developerMode, wallet.homeHost, defaults.localDevMints)

    /** The built-in note strategies. A brand's code module adds its own to the same set (see docs/WHITE_LABEL.md). */
    @Provides
    @IntoSet
    fun singleNote(): NoteStrategy =
        SingleNoteStrategy(SettingsR.string.settings_strategy_single, SettingsR.string.settings_strategy_single_hint)

    @Provides
    @IntoSet
    fun manual(): NoteStrategy = ManualStrategy(SettingsR.string.settings_strategy_manual, SettingsR.string.settings_strategy_manual_hint)

    /** Every registered strategy, narrowed to the brand's `notes.strategies` and its `notes.strategy.default`. */
    @Provides
    @Singleton
    fun noteStrategies(registered: Set<@JvmSuppressWildcards NoteStrategy>): NoteStrategies =
        NoteStrategies(
            registered,
            BuildConfig.NOTE_STRATEGIES.split(',').map(String::trim).filter(String::isNotEmpty),
            BuildConfig.DEFAULT_NOTE_STRATEGY,
        )

    @Provides
    fun defaults(): AppDefaults =
        AppDefaults(
            mintSuggestions = BuildConfig.MINT_SUGGESTIONS.split(',').map(String::trim).filter(String::isNotEmpty).distinct(),
            allowMintChange = BuildConfig.ALLOW_MINT_CHANGE,
            customMints = BuildConfig.CUSTOM_MINTS,
            localDevMints = BuildConfig.LOCAL_DEV_MINTS.split(',').map(String::trim).filter(String::isNotEmpty),
            simulatedFailures = BuildConfig.DEBUG,
            defaultAmountUnit = AmountUnit.fromConfig(BuildConfig.DEFAULT_AMOUNT_UNIT),
            allowUnitChange = BuildConfig.ALLOW_UNIT_CHANGE,
            maxAccounts = AppDefaults.limit(BuildConfig.MAX_ACCOUNTS),
            maxAddresses = AppDefaults.limit(BuildConfig.MAX_ADDRESSES),
            address = AddressPolicy.fromConfig(BuildConfig.LIGHTNING_ADDRESS),
            notesView = NotesView.fromConfig(BuildConfig.NOTES_VIEW),
            historyFilter = HistoryFilterOption.fromConfig(BuildConfig.HISTORY_FILTER),
            maintenanceNotices =
                MaintenanceNoticeDefaults.fromConfig(BuildConfig.MAINTENANCE_NOTICES, BuildConfig.MAINTENANCE_NOTICES_WHEN_NOTES_HIDDEN),
            showMints = BuildConfig.SHOW_MINTS,
            noteActions = NoteAction.fromConfig(BuildConfig.NOTE_ACTIONS, BuildConfig.SHOW_MINTS),
            developerOptions = DeveloperOptions.fromConfig(BuildConfig.DEVELOPER_OPTIONS, debug = BuildConfig.DEBUG),
            releaseStage = releaseStageFromConfig(BuildConfig.RELEASE_STAGE, BuildConfig.VERSION_NAME),
            timings =
                Timings(
                    receivePollMillis = BuildConfig.SYNC_RECEIVE_MILLIS,
                    invoicePollMillis = BuildConfig.SYNC_INVOICE_MILLIS,
                    sharedNotePollMillis = BuildConfig.SYNC_SHARED_NOTE_MILLIS,
                    clipboardClearMillis = BuildConfig.CLIPBOARD_CLEAR_MILLIS,
                    relockAfterMillis = BuildConfig.RELOCK_AFTER_MILLIS,
                ),
            appLock = AppLockPolicy.fromConfig(BuildConfig.APP_LOCK),
            backupRequired = BackupRequired.fromConfig(BuildConfig.BACKUP_REQUIRED),
            backupReminderAboveMsat = BuildConfig.BACKUP_REMINDER_ABOVE_SAT * 1_000,
        )
}
