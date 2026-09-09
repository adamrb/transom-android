package org.plaudbridge.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.managers.AutomationWatcher
import org.plaudbridge.app.models.Delivery
import org.plaudbridge.app.models.RoutingRun
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit

/** AutomationWatchWorker result classification and the scheduler's request shape. */
@RunWith(RobolectricTestRunner::class)
class AutomationWatchWorkerTest {

    private lateinit var context: Context
    private var response: ApiClient.RoutingResult = ApiClient.RoutingResult.Ok(emptyList())

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        File(context.filesDir, "recordings.json").delete()
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "test-token"
        AutomationWatcher.resetForTest()
        AutomationWatcher.routingSource = AutomationWatcher.RoutingSource { response }
        AutomationWatcher.scheduler = {}
        AutomationWatcher.kicker = {} // the worker IS the pass under test
        AutomationWatcher.onAnnounced = {}
        AutomationWatcher.clock = { java.time.Instant.parse("2026-09-09T00:00:30Z").toEpochMilli() }
        AutomationWatcher.notifier = object : AutomationWatcher.Notifier {
            override fun finished(watch: AutomationWatcher.Watch, delivery: Delivery) {}
            override fun skipped(watch: AutomationWatcher.Watch, run: RoutingRun) {}
        }
    }

    private fun runWorker(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<AutomationWatchWorker>(context).build().doWork()
    }

    private fun runs(json: String) = ApiClient.RoutingResult.Ok(RoutingRun.listFromJson(json))

    @Test
    fun noServerIsFailure() {
        RecordingStore.serverBaseUrl = null
        RecordingStore.serverAuthToken = null
        assertEquals(ListenableWorker.Result.failure(), runWorker())
    }

    @Test
    fun nothingWatchedIsSuccess() {
        assertEquals(ListenableWorker.Result.success(), runWorker())
    }

    @Test
    fun stillWorkingIsRetry() {
        AutomationWatcher.watch("srv-1", null, "T")
        response = runs("""{"runs":[{"id":"r1","created_at":"2026-09-09T00:00:00Z","decision":{"routes":[{"name":"Ask Claude"}]},
            "deliveries":[{"id":"d1","router_run_id":"r1","route_name":"Ask Claude","action_type":"webhook","status":"ok","result_status":"queued"}]}],"deliveries":[]}""")
        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    @Test
    fun settledIsSuccess() {
        AutomationWatcher.watch("srv-1", null, "T")
        response = runs("""{"runs":[{"id":"r1","created_at":"2026-09-09T00:00:00Z","decision":{"routes":[{"name":"Ask Claude"}]},
            "deliveries":[{"id":"d1","router_run_id":"r1","route_name":"Ask Claude","action_type":"webhook","status":"ok","result_status":"done","result_summary":"Filed: x"}]}],"deliveries":[]}""")
        assertEquals(ListenableWorker.Result.success(), runWorker())
    }

    @org.junit.After
    fun tearDown() {
        AutomationWatcher.clock = { System.currentTimeMillis() }
        AutomationWatcher.kicker = { AutomationWatcher.kick() }
        AutomationWatcher.resetForTest()
    }

    @Test
    fun transientErrorIsRetry() {
        AutomationWatcher.watch("srv-1", null, "T")
        response = ApiClient.RoutingResult.Error("boom")
        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    @Test
    fun requestNeedsNetworkAndBacksOffExponentially() {
        val request = AutomationWatchScheduler.buildRequest()
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(AutomationWatchScheduler.INITIAL_BACKOFF_SECONDS), request.workSpec.backoffDelayDuration)
        assertTrue(request.tags.contains(AutomationWatchScheduler.UNIQUE_WORK_NAME))
    }
}
