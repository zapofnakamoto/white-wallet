package whitewallet.app

import android.app.UiModeManager
import android.content.Context
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import whitewallet.brand.WalletEvent
import whitewallet.brand.WalletEventListener
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.BrandScreen
import whitewallet.core.wallet.Wallet
import whitewallet.core.wallet.WalletEvents

/** What a brand's code module may provide for its design (docs/WHITE_LABEL.md, "Design"); all of it optional. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DesignModule {
    @BindsOptionalOf
    abstract fun design(): BrandDesign

    @Multibinds
    abstract fun eventListeners(): Set<WalletEventListener>

    @Multibinds
    abstract fun screens(): Set<BrandScreen>

    companion object {
        @Provides
        @Singleton
        fun walletEvents(wallet: Wallet): WalletEvents = WalletEvents(wallet)
    }
}

/**
 * Hands every [WalletEvent] to the brand's listeners and to the UI ([events]), for as long as the app runs. Listeners
 * are called on the main thread; one that throws is the brand's bug and must not take the wallet down with it.
 */
@Singleton
class WalletEventHub @Inject constructor(
    private val source: WalletEvents,
    private val listeners: Set<@JvmSuppressWildcards WalletEventListener>,
) {
    private val _events = MutableSharedFlow<WalletEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<WalletEvent> = _events

    fun start() {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            source.events.collect { event ->
                listeners.forEach { listener -> runCatching { listener.onEvent(event) } }
                _events.tryEmit(event)
            }
        }
    }
}

/** The brand's `app.appearance`: light or dark whatever the system says, or following it. */
internal fun applyAppearance(context: Context) {
    val mode = when (BuildConfig.APPEARANCE) {
        "light" -> UiModeManager.MODE_NIGHT_NO
        "dark" -> UiModeManager.MODE_NIGHT_YES
        else -> UiModeManager.MODE_NIGHT_AUTO
    }
    context.getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
}
