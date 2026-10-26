package whitewallet.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WalletApplication : Application() {
    @Inject lateinit var events: WalletEventHub

    override fun onCreate() {
        super.onCreate()
        applyAppearance(this)
        SyncWorker.schedule(this)
        events.start()
    }
}
