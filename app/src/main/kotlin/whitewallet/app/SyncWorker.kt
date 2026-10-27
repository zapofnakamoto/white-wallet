package whitewallet.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import whitewallet.core.wallet.Wallet

/**
 * Background sync (brief §5 "Sync"): every 15 minutes or more, with a network, the same refresh as pull-to-refresh:
 * reconcile the journal (pending payments, moves, claims), scan the address keys for new payments, settle sent
 * notes, apply the note policies. Runs in the app's process, so it shares the wallet's lock with the UI.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun wallet(): Wallet
    }

    override suspend fun doWork(): Result {
        val wallet = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).wallet()
        if (!wallet.isSetUp()) return Result.success()
        return try {
            wallet.refresh()
            Result.success()
        } catch (e: Exception) {
            // Offline, a mint down, a pinned key that changed: try again next period; the UI shows the details.
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
