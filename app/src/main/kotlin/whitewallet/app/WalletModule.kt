package whitewallet.app

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import whitewallet.brand.NoteStrategy
import whitewallet.core.model.AmountUnit
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.CollectDefaults
import whitewallet.core.wallet.DeveloperOptions
import whitewallet.core.wallet.MintTrustStore
import whitewallet.core.wallet.RegtestFaucet
import whitewallet.core.wallet.TrustedMints
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.NotePolicies
import whitewallet.core.wallet.NoteStrategies
import whitewallet.core.wallet.NotesView
import whitewallet.core.wallet.SeedStore
import whitewallet.core.wallet.SingleNoteStrategy
import whitewallet.core.wallet.Wallet
import whitewallet.core.wallet.WalletStore
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.feature.settings.R as SettingsR
import whitewallet.lnurlcash.client.RequestLog

@Module
@InstallIn(SingletonComponent::class)
object WalletModule {
    /** In-memory log of every request to a service, shown under Developer options. */
    @Provides
    @Singleton
    fun requestLog(): RequestLog = RequestLog()

    @Provides
    @Singleton
    fun client(log: RequestLog): LnurlcashClient =
        // newBuilder keeps the default client's settings, including retryOnConnectionFailure(false).
        LnurlcashClient(LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(log.interceptor()).build())

    @Provides
    @Singleton
    fun wallet(store: WalletStore, seeds: SeedStore, client: LnurlcashClient, policies: NotePolicies, strategies: NoteStrategies, trust: MintTrustStore): Wallet =
        Wallet(
            store, seeds, client, policies = policies, strategies = strategies, trust = trust,
            brandTrusted = TrustedMints.parse(BuildConfig.TRUSTED_MINTS),
            maxAccounts = AppDefaults.limit(BuildConfig.MAX_ACCOUNTS),
            requireAddress = BuildConfig.REQUIRE_ADDRESS,
            collectDefaults = CollectDefaults.fromConfig(BuildConfig.COLLECT_AT_HOME_MINT, BuildConfig.COLLECT_FEE_CAP),
        )

    /** dev/regtest's faucet in debug builds; without a host (release) it pays nothing. */
    @Provides
    @Singleton
    fun regtestFaucet(wallet: Wallet, preferences: AppPreferences, defaults: AppDefaults): RegtestFaucet =
        RegtestFaucet(BuildConfig.LOCAL_DEV_FAUCET.ifEmpty { null }, preferences.developerMode, wallet.homeHost, defaults.localDevServices)

    /** The built-in note strategies. A brand's code module adds its own to the same set (see docs/WHITE_LABEL.md). */
    @Provides
    @IntoSet
    fun singleNote(): NoteStrategy = SingleNoteStrategy(SettingsR.string.settings_strategy_single, SettingsR.string.settings_strategy_single_hint)

    @Provides
    @IntoSet
    fun manual(): NoteStrategy = ManualStrategy(SettingsR.string.settings_strategy_manual, SettingsR.string.settings_strategy_manual_hint)

    /** Every registered strategy, narrowed to the brand's `noteStrategies` and its `defaultNoteStrategy`. */
    @Provides
    @Singleton
    fun noteStrategies(registered: Set<@JvmSuppressWildcards NoteStrategy>): NoteStrategies = NoteStrategies(
        registered,
        BuildConfig.NOTE_STRATEGIES.split(',').map(String::trim).filter(String::isNotEmpty),
        BuildConfig.DEFAULT_NOTE_STRATEGY,
    )

    @Provides
    fun defaults(): AppDefaults = AppDefaults(
        defaultServiceHost = BuildConfig.DEFAULT_SERVICE_HOST,
        allowServiceChange = BuildConfig.ALLOW_SERVICE_CHANGE,
        serviceSuggestions = (listOf(BuildConfig.DEFAULT_SERVICE_HOST) + BuildConfig.SERVICE_SUGGESTIONS.split(','))
            .map(String::trim).filter(String::isNotEmpty).distinct(),
        localDevServices = BuildConfig.LOCAL_DEV_SERVICES.split(',').map(String::trim).filter(String::isNotEmpty),
        defaultAmountUnit = AmountUnit.fromConfig(BuildConfig.DEFAULT_AMOUNT_UNIT),
        allowUnitChange = BuildConfig.ALLOW_UNIT_CHANGE,
        maxAccounts = AppDefaults.limit(BuildConfig.MAX_ACCOUNTS),
        maxAddresses = AppDefaults.limit(BuildConfig.MAX_ADDRESSES),
        requireAddress = BuildConfig.REQUIRE_ADDRESS,
        notesView = NotesView.fromConfig(BuildConfig.NOTES_VIEW),
        showMints = BuildConfig.SHOW_MINTS,
        developerOptions = DeveloperOptions.fromConfig(BuildConfig.DEVELOPER_OPTIONS, debug = BuildConfig.DEBUG),
    )
}
