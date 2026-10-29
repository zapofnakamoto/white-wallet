package whitewallet.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WalletApplication : Application() {
    @Inject lateinit var events: WalletEventHub
    @Inject lateinit var network: NetworkSimulation

    override fun onCreate() {
        super.onCreate()
        network.start()
        applyAppearance(this)
        SyncWorker.schedule(this)
        events.start()
    }
}
