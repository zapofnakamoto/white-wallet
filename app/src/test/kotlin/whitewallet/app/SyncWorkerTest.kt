package whitewallet.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The sync the wallet asks for when a received note waits for its mint: once, when the device is online. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @Test
    fun `one sync waits for a network, and asking again while it waits keeps that one`() {
        SyncWorker.scheduleOnce(context)
        val first = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncWorker.ONCE).get().single()
        SyncWorker.scheduleOnce(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncWorker.ONCE).get()

        assertEquals(listOf(first.id), infos.map { it.id })
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state, "not run without a network")
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
    }
}
