package io.github.adamrb.transom.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * UploadScheduler enqueue policy: one unique request, CONNECTED constraint, exponential backoff.
 * WorkManager is the test-mode instance (in-memory DB, synchronous executor), so no worker runs.
 */
@RunWith(RobolectricTestRunner::class)
class UploadSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get()
    }

    @Test
    fun enqueueProducesUniqueWorkWithConnectedConstraint() {
        UploadScheduler.enqueue(context)

        val infos = workManager.getWorkInfosForUniqueWork(UploadScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(info.tags.contains(UploadScheduler.UNIQUE_WORK_NAME))
        assertTrue(info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING)
    }

    @Test
    fun repeatedEnqueueKeepsTheExistingRequest() {
        UploadScheduler.enqueue(context)
        val first = workManager.getWorkInfosForUniqueWork(UploadScheduler.UNIQUE_WORK_NAME).get().single().id

        UploadScheduler.enqueue(context)
        UploadScheduler.enqueue(context)

        val infos = workManager.getWorkInfosForUniqueWork(UploadScheduler.UNIQUE_WORK_NAME).get()
        assertEquals("KEEP must collapse duplicates", 1, infos.size)
        assertEquals(first, infos.single().id)
    }

    @Test
    fun requestUsesExponentialBackoffFromThirtySeconds() {
        val spec = UploadScheduler.buildRequest().workSpec
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(UploadScheduler.INITIAL_BACKOFF_SECONDS), spec.backoffDelayDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(UploadWorker::class.java.name, spec.workerClassName)
    }

    @Test
    fun enqueueSurvivesUninitializedWorkManager() {
        // The default scheduler seam runs from BLE callbacks; a WorkManager problem must never
        // propagate. Simulate by handing it a context whose WorkManager lookup fails.
        val broken = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = throw IllegalStateException("no WorkManager")
        }
        UploadScheduler.enqueue(broken) // must not throw
    }
}
