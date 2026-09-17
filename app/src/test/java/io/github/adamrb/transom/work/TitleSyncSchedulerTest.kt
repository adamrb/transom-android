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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * TitleSyncScheduler enqueue policy: one unique request, CONNECTED constraint, exponential
 * backoff, and a unique name distinct from the upload queue so the two never collapse into each
 * other. WorkManager is the test-mode instance (in-memory DB, synchronous executor).
 */
@RunWith(RobolectricTestRunner::class)
class TitleSyncSchedulerTest {

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
        TitleSyncScheduler.enqueue(context)

        val infos = workManager.getWorkInfosForUniqueWork(TitleSyncScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(info.tags.contains(TitleSyncScheduler.UNIQUE_WORK_NAME))
        assertTrue(info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING)
    }

    @Test
    fun repeatedEnqueueKeepsTheExistingRequest() {
        TitleSyncScheduler.enqueue(context)
        val first = workManager.getWorkInfosForUniqueWork(TitleSyncScheduler.UNIQUE_WORK_NAME).get().single().id

        TitleSyncScheduler.enqueue(context)
        TitleSyncScheduler.enqueue(context)

        val infos = workManager.getWorkInfosForUniqueWork(TitleSyncScheduler.UNIQUE_WORK_NAME).get()
        assertEquals("KEEP must collapse duplicates", 1, infos.size)
        assertEquals(first, infos.single().id)
    }

    @Test
    fun requestUsesExponentialBackoffFromThirtySeconds() {
        val spec = TitleSyncScheduler.buildRequest().workSpec
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(TitleSyncScheduler.INITIAL_BACKOFF_SECONDS), spec.backoffDelayDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(TitleSyncWorker::class.java.name, spec.workerClassName)
    }

    @Test
    fun titleWorkDoesNotCollideWithUploadWork() {
        assertNotEquals(UploadScheduler.UNIQUE_WORK_NAME, TitleSyncScheduler.UNIQUE_WORK_NAME)

        UploadScheduler.enqueue(context)
        TitleSyncScheduler.enqueue(context)

        assertEquals(1, workManager.getWorkInfosForUniqueWork(UploadScheduler.UNIQUE_WORK_NAME).get().size)
        assertEquals(1, workManager.getWorkInfosForUniqueWork(TitleSyncScheduler.UNIQUE_WORK_NAME).get().size)
    }

    @Test
    fun enqueueSurvivesUninitializedWorkManager() {
        val broken = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = throw IllegalStateException("no WorkManager")
        }
        TitleSyncScheduler.enqueue(broken) // must not throw
    }
}
