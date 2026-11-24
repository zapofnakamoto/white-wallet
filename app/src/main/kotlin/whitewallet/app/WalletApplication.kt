package whitewallet.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import whitewallet.core.designsystem.warmHostText
import whitewallet.core.wallet.ReleaseNoteTracker

@HiltAndroidApp
class WalletApplication : Application() {
    @Inject lateinit var events: WalletEventHub
    @Inject lateinit var network: NetworkSimulation
    @Inject lateinit var releaseNotes: ReleaseNoteTracker

    override fun onCreate() {
        super.onCreate()
        network.start()
        applyAppearance(this)
        SyncWorker.schedule(this)
        events.start()
        // Whether there was a wallet before this start, asked before onboarding can make one.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { releaseNotes.start() }
        CoroutineScope(Dispatchers.IO).launch { warmHostText(this@WalletApplication) }
    }
}
