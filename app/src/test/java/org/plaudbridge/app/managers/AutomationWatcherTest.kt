package org.plaudbridge.app.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.models.Delivery
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.RoutingRun
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * AutomationWatcher pass semantics with ApiClient faked behind RoutingSource and the
 * notifications behind Notifier:
 *  - a finished hand-off is announced exactly once, even across watches and passes
 *  - a hand-off still working keeps the watch alive; the watch settles once nothing is working
 *  - hand-offs the caller already knew (a rerun's history) and runs clearly older than the watch
 *    are never announced
 *  - a run that matched nothing is announced once as "no automation matched"
 *  - 404 drops the watch, a transient error keeps it and counts as remaining, auth aborts the pass
 *  - watches expire after the TTL, persist across "process death" (object state is in the store)
 *  - kick(): schedules once, polls while something is still working
 */
@RunWith(RobolectricTestRunner::class)
class AutomationWatcherTest {

    private class FakeRouting : AutomationWatcher.RoutingSource {
        val responses = mutableMapOf<String, ArrayDeque<ApiClient.RoutingResult>>()
        val calls = mutableListOf<String>()
        fun script(id: String, vararg results: ApiClient.RoutingResult) {
            responses.getOrPut(id) { ArrayDeque() }.addAll(results)
        }
        override fun fetchRouting(recordingId: String): ApiClient.RoutingResult {
            synchronized(calls) { calls.add(recordingId) }
            val queue = responses[recordingId] ?: error("unexpected routing fetch for $recordingId")
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }
        fun callsSnapshot(): List<String> = synchronized(calls) { calls.toList() }
    }

    private class RecordingNotifier : AutomationWatcher.Notifier {
        val finished = mutableListOf<Pair<String, Delivery>>()
        val skipped = mutableListOf<Pair<String, RoutingRun>>()
        override fun finished(watch: AutomationWatcher.Watch, delivery: Delivery) {
            synchronized(this) { finished.add(watch.serverId to delivery) }
        }
        override fun skipped(watch: AutomationWatcher.Watch, run: RoutingRun) {
            synchronized(this) { skipped.add(watch.serverId to run) }
        }
    }

    private lateinit var context: Context
    private lateinit var routing: FakeRouting
    private lateinit var notifier: RecordingNotifier
    private val scheduleCalls = AtomicInteger()
    private var now = 1_000_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        File(context.filesDir, "recordings.json").delete()
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "test-token"
        routing = FakeRouting()
        AutomationWatcher.routingSource = routing
        notifier = RecordingNotifier()
        AutomationWatcher.notifier = notifier
        scheduleCalls.set(0)
        AutomationWatcher.scheduler = { scheduleCalls.incrementAndGet() }
        AutomationWatcher.clock = { now }
        AutomationWatcher.inAppPollDelayMs = 50L
        // Passes are driven by hand; the kick tests below switch the real loop back on.
        AutomationWatcher.kicker = {}
        listRefreshes.set(0)
        AutomationWatcher.onAnnounced = { listRefreshes.incrementAndGet() }
        AutomationWatcher.resetForTest()
    }

    private val listRefreshes = AtomicInteger()

    @Test
    fun anAnnouncementRefreshesTheListSnapshot() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        routing.script("srv-1", ok(runJson(deliveries = listOf(deliveryJson("d1", resultStatus = "done", summary = "Created: x.md")))))
        AutomationWatcher.runPass()
        assertEquals(1, listRefreshes.get())
        AutomationWatcher.watch("srv-2", null, "Quiet")
        routing.script("srv-2", ok())
        AutomationWatcher.runPass()
        assertEquals("nothing announced, nothing refreshed", 1, listRefreshes.get())
    }

    @After
    fun tearDown() {
        AutomationWatcher.inAppPollDelayMs = 15_000L
        AutomationWatcher.clock = { System.currentTimeMillis() }
        AutomationWatcher.kicker = { AutomationWatcher.kick() }
        AutomationWatcher.resetForTest()
    }

    // MARK: - fixtures

    private fun iso(epochMs: Long): String = java.time.Instant.ofEpochMilli(epochMs).toString()

    private fun deliveryJson(
        id: String, route: String = "Work meetings", status: String = "ok", resultStatus: String? = "queued",
        summary: String? = null, actionType: String = "webhook", createdAt: Long = now, runId: String = "run-1"
    ): String {
        val rs = if (resultStatus == null) "null" else "\"$resultStatus\""
        val sm = if (summary == null) "null" else "\"$summary\""
        return """{"id":"$id","router_run_id":"$runId","route_name":"$route","action_type":"$actionType","status":"$status",
            "attempts":1,"created_at":"${iso(createdAt)}","result_status":$rs,"result_summary":$sm,"result_at":null}"""
    }

    private fun runJson(id: String = "run-1", createdAt: Long = now, routes: List<String> = listOf("Work meetings"), deliveries: List<String>, error: String? = null): String {
        val routesJson = routes.joinToString(",") { """{"name":"$it","reason":"because"}""" }
        val err = if (error == null) "null" else "\"$error\""
        return """{"id":"$id","recording_id":"srv-1","created_at":"${iso(createdAt)}","model":"m","error":$err,
            "decision":{"routes":[$routesJson]},"deliveries":[${deliveries.joinToString(",")}]}"""
    }

    private fun ok(vararg runs: String) = ApiClient.RoutingResult.Ok(RoutingRun.listFromJson("""{"runs":[${runs.joinToString(",")}],"deliveries":[]}"""))

    private fun awaitCondition(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for: $what")
    }

    // MARK: - announcing

    @Test
    fun finishedHandOffIsAnnouncedOnceAndTheWatchSettles() = runBlocking {
        AutomationWatcher.watch("srv-1", "file-1", "Standup")
        routing.script("srv-1", ok(runJson(deliveries = listOf(deliveryJson("d1", resultStatus = "done", summary = "Created: x.md")))))

        val pass = AutomationWatcher.runPass()

        assertEquals(1, pass.announced)
        assertEquals(0, pass.watching)
        assertEquals(0, pass.remaining)
        assertEquals(listOf("srv-1"), notifier.finished.map { it.first })
        assertEquals("Created: x.md", notifier.finished.single().second.resultSummary)
        assertTrue(AutomationWatcher.watched().isEmpty())

        // Watching the same recording again (e.g. the user reopens it) does not repeat it.
        AutomationWatcher.watch("srv-1", "file-1", "Standup")
        val again = AutomationWatcher.runPass()
        assertEquals(0, again.announced)
        assertEquals(1, notifier.finished.size)
    }

    @Test
    fun workingHandOffKeepsWatchingUntilItReports() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        routing.script(
            "srv-1",
            ok(runJson(deliveries = listOf(deliveryJson("d1", resultStatus = "queued")))),
            ok(runJson(deliveries = listOf(deliveryJson("d1", resultStatus = "done", summary = "Filed: y.md"))))
        )

        val first = AutomationWatcher.runPass()
        assertEquals(0, first.announced)
        assertEquals(1, first.watching)
        assertTrue(notifier.finished.isEmpty())

        val second = AutomationWatcher.runPass()
        assertEquals(1, second.announced)
        assertEquals(0, second.watching)
        assertEquals("Filed: y.md", notifier.finished.single().second.resultSummary)
    }

    @Test
    fun twoHandOffsAnnouncedAsEachFinishes() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        routing.script(
            "srv-1",
            ok(runJson(routes = listOf("Work meetings", "Vault notes"), deliveries = listOf(
                deliveryJson("d1", route = "Work meetings", resultStatus = "done", summary = "Created: a.md"),
                deliveryJson("d2", route = "Vault notes", resultStatus = "queued")
            ))),
            ok(runJson(routes = listOf("Work meetings", "Vault notes"), deliveries = listOf(
                deliveryJson("d1", route = "Work meetings", resultStatus = "done", summary = "Created: a.md"),
                deliveryJson("d2", route = "Vault notes", resultStatus = "failed", summary = "Skipped: nothing to file")
            )))
        )
        assertEquals(1, AutomationWatcher.runPass().announced)
        assertEquals(1, AutomationWatcher.runPass().announced)
        assertEquals(listOf("d1", "d2"), notifier.finished.map { it.second.id })
        assertTrue(AutomationWatcher.watched().isEmpty())
    }

    @Test
    fun noRunYetKeepsWatching() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        routing.script("srv-1", ok())
        val pass = AutomationWatcher.runPass()
        assertEquals(0, pass.announced)
        assertEquals(1, pass.watching)
    }

    @Test
    fun nothingMatchedIsAnnouncedOnce() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        routing.script("srv-1", ok(runJson(routes = emptyList(), deliveries = emptyList())))
        val pass = AutomationWatcher.runPass()
        assertEquals(1, pass.announced)
        assertEquals(0, pass.watching)
        assertEquals("run-1", notifier.skipped.single().second.id)
        assertTrue(notifier.finished.isEmpty())

        AutomationWatcher.watch("srv-1", null, "Standup")
        assertEquals(0, AutomationWatcher.runPass().announced)
    }

    @Test
    fun routerErrorIsAnnouncedAsSkippedWithTheError() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        routing.script("srv-1", ok(runJson(routes = emptyList(), deliveries = emptyList(), error = "Model timed out")))
        AutomationWatcher.runPass()
        assertEquals("Model timed out", notifier.skipped.single().second.error)
    }

    @Test
    fun knownHandOffsFromBeforeTheRerunAreNotAnnounced() = runBlocking {
        // The user taps Run automations with d-old (done) on screen; the new run adds d-new.
        AutomationWatcher.watch("srv-1", null, "Standup", knownDeliveryIds = setOf("d-old"))
        routing.script(
            "srv-1",
            ok(
                runJson(id = "run-2", deliveries = listOf(deliveryJson("d-new", resultStatus = "done", summary = "Created: new.md", runId = "run-2"))),
                runJson(id = "run-1", createdAt = now - 60_000, deliveries = listOf(deliveryJson("d-old", resultStatus = "done", summary = "Created: old.md")))
            )
        )
        val pass = AutomationWatcher.runPass()
        assertEquals(1, pass.announced)
        assertEquals(listOf("d-new"), notifier.finished.map { it.second.id })
        assertEquals(0, pass.watching)
    }

    @Test
    fun aKnownRunDoesNotSettleTheWatch() = runBlocking {
        // Re-transcribe two minutes after the previous run: the screen passes that run as known,
        // and the new detached run has not appeared yet. Waiting must continue.
        AutomationWatcher.watch("srv-1", null, "Standup", knownDeliveryIds = setOf("d-old"), knownRunIds = setOf("run-1"))
        val recent = now - 120_000
        routing.script(
            "srv-1",
            ok(runJson(id = "run-1", createdAt = recent, deliveries = listOf(deliveryJson("d-old", resultStatus = "done", summary = "x", createdAt = recent)))),
            ok(
                runJson(id = "run-2", deliveries = listOf(deliveryJson("d-new", resultStatus = "done", summary = "Created: new.md", runId = "run-2"))),
                runJson(id = "run-1", createdAt = recent, deliveries = listOf(deliveryJson("d-old", resultStatus = "done", summary = "x", createdAt = recent)))
            )
        )
        val first = AutomationWatcher.runPass()
        assertEquals(0, first.announced)
        assertEquals(1, first.watching)
        val second = AutomationWatcher.runPass()
        assertEquals(listOf("d-new"), notifier.finished.map { it.second.id })
        assertEquals(0, second.watching)
    }

    @Test
    fun aRunMadeOnlyOfKnownHandOffsIsHistoryEvenWithoutItsId() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup", knownDeliveryIds = setOf("d-old"))
        val recent = now - 120_000
        routing.script("srv-1", ok(runJson(id = "run-1", createdAt = recent, deliveries = listOf(deliveryJson("d-old", resultStatus = "done", summary = "x", createdAt = recent)))))
        val pass = AutomationWatcher.runPass()
        assertEquals(0, pass.announced)
        assertEquals(1, pass.watching)
    }

    @Test
    fun watchAddedDuringAPassSurvivesTheRewrite() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "A")
        // srv-1 finishes; while its fetch is "on the wire" srv-2 is watched and srv-1 re-watched.
        routing.responses["srv-1"] = ArrayDeque()
        AutomationWatcher.routingSource = AutomationWatcher.RoutingSource { id ->
            if (id == "srv-1") {
                now += 1
                AutomationWatcher.watch("srv-2", null, "B")
                AutomationWatcher.watch("srv-1", null, "A again")
            }
            ok(runJson(deliveries = listOf(deliveryJson("d1", resultStatus = "done", summary = "Created: a.md"))))
        }
        val pass = AutomationWatcher.runPass()
        // srv-1's pass verdict (settled) loses to the refresh made meanwhile; srv-2 is new.
        val left = AutomationWatcher.watched().map { it.serverId }.toSet()
        assertEquals(setOf("srv-1", "srv-2"), left)
        assertEquals("A again", AutomationWatcher.watched().first { it.serverId == "srv-1" }.title)
        assertEquals(2, pass.watching)
    }

    @Test
    fun aRetriedHandOffIsAnnouncedAgainForItsNewAttempt() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        val failed = """{"id":"d1","router_run_id":"run-1","route_name":"Work meetings","action_type":"webhook","status":"failed",
            "attempts":1,"last_error":"HTTP 500","created_at":"${iso(now)}","result_status":null,"result_summary":null,"result_at":null}"""
        val retried = """{"id":"d1","router_run_id":"run-1","route_name":"Work meetings","action_type":"webhook","status":"ok",
            "attempts":2,"last_error":null,"created_at":"${iso(now)}","result_status":"done","result_summary":"Filed: a.md","result_at":null}"""
        // First read: the failed attempt. Second read (after the user taps Retry on the detail
        // screen): same delivery id, attempt 2, done.
        routing.script("srv-1", ok(runJson(deliveries = listOf(failed))), ok(runJson(deliveries = listOf(retried))))
        assertEquals(1, AutomationWatcher.runPass().announced)
        assertTrue(AutomationWatcher.watched().isEmpty())

        AutomationWatcher.watch("srv-1", null, "Standup")
        assertEquals(1, AutomationWatcher.runPass().announced)
        assertEquals(listOf(1, 2), notifier.finished.map { it.second.attempts })
    }

    @Test
    fun aRecentRunAlreadyFullyAnnouncedIsHistoryToo() = runBlocking {
        // Re-transcribed two minutes after the previous run, detail screen closed: no known ids,
        // but the previous run's outcome was announced already, so it is not the awaited run.
        RecordingStore.announcedAutomationsJson = """["d:d-old#1"]"""
        AutomationWatcher.watch("srv-1", null, "Standup")
        val recent = now - 120_000
        routing.script(
            "srv-1",
            ok(runJson(id = "run-1", createdAt = recent, deliveries = listOf(deliveryJson("d-old", resultStatus = "done", summary = "x", createdAt = recent)))),
            ok(
                runJson(id = "run-2", deliveries = listOf(deliveryJson("d-new", resultStatus = "done", summary = "Created: new.md", runId = "run-2"))),
                runJson(id = "run-1", createdAt = recent, deliveries = listOf(deliveryJson("d-old", resultStatus = "done", summary = "x", createdAt = recent)))
            )
        )
        val first = AutomationWatcher.runPass()
        assertEquals(0, first.announced)
        assertEquals(1, first.watching)
        val second = AutomationWatcher.runPass()
        assertEquals(listOf("d-new"), notifier.finished.map { it.second.id })
        assertEquals(0, second.watching)
    }

    @Test
    fun aMatchedRouteWithoutItsRowYetKeepsTheWatchOpen() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        val two = listOf("Vault notes", "Ask Claude")
        routing.script(
            "srv-1",
            ok(runJson(routes = two, deliveries = listOf(deliveryJson("d1", route = "Vault notes", resultStatus = "done", summary = "Filed: a.md")))),
            ok(runJson(routes = two, deliveries = listOf(
                deliveryJson("d1", route = "Vault notes", resultStatus = "done", summary = "Filed: a.md"),
                deliveryJson("d2", route = "Ask Claude", resultStatus = "done", summary = "Started")
            )))
        )
        val first = AutomationWatcher.runPass()
        assertEquals(1, first.announced)
        assertEquals("Ask Claude has no hand-off row yet", 1, first.watching)
        val second = AutomationWatcher.runPass()
        assertEquals(listOf("d1", "d2"), notifier.finished.map { it.second.id })
        assertEquals(0, second.watching)
    }

    @Test
    fun aLegacyHandOffWithoutARunIsFollowedButNeverReportedAsARun() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        val legacy = """{"id":"d-legacy","router_run_id":null,"route_name":"inbox","action_type":"webhook","status":"ok",
            "attempts":2,"created_at":"${iso(now)}","result_status":"done","result_summary":"Filed: legacy.md","result_at":null}"""
        routing.script("srv-1", ApiClient.RoutingResult.Ok(RoutingRun.listFromJson("""{"runs":[],"deliveries":[$legacy]}""")))
        val pass = AutomationWatcher.runPass()
        assertEquals(1, pass.announced)
        assertEquals(listOf("d-legacy"), notifier.finished.map { it.second.id })
        assertTrue(notifier.skipped.isEmpty())
        assertEquals(0, pass.watching)
    }

    @Test
    fun retryingAKnownHandOffTakesItOutOfTheKnownSets() {
        AutomationWatcher.watch("srv-1", null, "A", knownDeliveryIds = setOf("d1", "d2"), knownRunIds = setOf("run-1"))
        AutomationWatcher.watch("srv-1", null, "A", knownDeliveryIds = setOf("d2"), forgetDeliveryIds = setOf("d1"), forgetRunIds = setOf("run-1"))
        val w = AutomationWatcher.watched().single()
        assertEquals(setOf("d2"), w.knownDeliveryIds)
        assertTrue(w.knownRunIds.isEmpty())
    }

    @Test
    fun unwatchDropsOnlyThatRecording() {
        AutomationWatcher.watch("srv-1", null, "A")
        AutomationWatcher.watch("srv-2", null, "B")
        AutomationWatcher.unwatch("srv-1")
        assertEquals(listOf("srv-2"), AutomationWatcher.watched().map { it.serverId })
        AutomationWatcher.unwatch("srv-9") // unknown: no-op
        assertEquals(1, AutomationWatcher.watched().size)
    }

    @Test
    fun anOldRunAlreadyAnnouncedIsHistoryAndTheWatchKeepsWaiting() = runBlocking {
        // A re-transcribe: the earlier transcription's run is an hour old and was announced then.
        RecordingStore.announcedAutomationsJson = """["d:d-old#1"]"""
        AutomationWatcher.watch("srv-1", null, "Standup")
        val old = now - 60 * 60 * 1000L
        routing.script("srv-1", ok(runJson(id = "run-old", createdAt = old, deliveries = listOf(deliveryJson("d-old", resultStatus = "done", summary = "x", createdAt = old, runId = "run-old")))))
        val pass = AutomationWatcher.runPass()
        assertEquals(0, pass.announced)
        assertEquals("the new run has not shown up yet", 1, pass.watching)
    }

    @Test
    fun anOldRunWithUnannouncedNewsIsTheRunBeingWaitedFor() = runBlocking {
        // The phone was offline for hours; the transcript (and its run) arrive late together.
        AutomationWatcher.watch("srv-1", null, "Standup")
        val old = now - 3 * 60 * 60 * 1000L
        routing.script("srv-1", ok(runJson(id = "run-old", createdAt = old, deliveries = listOf(deliveryJson("d-late", resultStatus = "done", summary = "Filed: late.md", createdAt = old, runId = "run-old")))))
        val pass = AutomationWatcher.runPass()
        assertEquals(1, pass.announced)
        assertEquals(listOf("d-late"), notifier.finished.map { it.second.id })
        assertEquals(0, pass.watching)
    }

    @Test
    fun matchedRoutesWithoutHandOffRowsYetAreStillWorking() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        routing.script(
            "srv-1",
            ok(runJson(routes = listOf("Vault notes"), deliveries = emptyList())),
            ok(runJson(routes = listOf("Vault notes"), deliveries = listOf(deliveryJson("d1", route = "Vault notes", resultStatus = "done", summary = "Filed: a.md"))))
        )
        val first = AutomationWatcher.runPass()
        assertEquals(0, first.announced)
        assertTrue(notifier.skipped.isEmpty())
        assertEquals(1, first.watching)
        val second = AutomationWatcher.runPass()
        assertEquals(1, second.announced)
        assertEquals(0, second.watching)
    }

    @Test
    fun runSlightlyOlderThanTheWatchIsTheRunBeingWaitedFor() = runBlocking {
        // The server routed right after transcription, one poll before the phone noticed.
        AutomationWatcher.watch("srv-1", null, "Standup")
        val recent = now - 30_000
        routing.script("srv-1", ok(runJson(createdAt = recent, deliveries = listOf(deliveryJson("d1", resultStatus = "done", summary = "Created: a.md", createdAt = recent)))))
        assertEquals(1, AutomationWatcher.runPass().announced)
    }

    @Test
    fun serverSideActionsWithoutAReportCountAsDone() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        routing.script("srv-1", ok(runJson(deliveries = listOf(deliveryJson("d1", resultStatus = null, actionType = "markdown", summary = null)))))
        val pass = AutomationWatcher.runPass()
        assertEquals(1, pass.announced)
        assertEquals(0, pass.watching)
    }

    @Test
    fun webhookAcceptedButNotYetReportedIsStillWorking() {
        val d = Delivery("d", "r", "Ask Claude", "webhook", "ok", 1, null, 1L, null, null, null)
        assertFalse(AutomationWatcher.isTerminal(d))
        assertTrue(AutomationWatcher.isTerminal(d.copy(resultStatus = "done")))
        assertTrue(AutomationWatcher.isTerminal(d.copy(resultStatus = "unknown")))
        assertTrue(AutomationWatcher.isTerminal(d.copy(status = "failed")))
        assertFalse(AutomationWatcher.isTerminal(d.copy(status = "pending")))
    }

    // MARK: - fetch outcomes

    @Test
    fun notFoundDropsTheWatch() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        routing.script("srv-1", ApiClient.RoutingResult.NotFound)
        val pass = AutomationWatcher.runPass()
        assertEquals(0, pass.remaining)
        assertTrue(AutomationWatcher.watched().isEmpty())
    }

    @Test
    fun transientErrorKeepsTheWatchAndCountsAsRemaining() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        routing.script("srv-1", ApiClient.RoutingResult.Error("boom"))
        val pass = AutomationWatcher.runPass()
        assertEquals(1, pass.failed)
        assertEquals(1, pass.watching)
        assertEquals(1, AutomationWatcher.watched().size)
    }

    @Test
    fun authErrorAbortsThePassAndKeepsEveryWatch() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "A")
        AutomationWatcher.watch("srv-2", null, "B")
        routing.script("srv-1", ApiClient.RoutingResult.AuthError(401))
        routing.script("srv-2", ApiClient.RoutingResult.AuthError(401))
        val pass = AutomationWatcher.runPass()
        assertEquals(1, routing.callsSnapshot().size)
        assertEquals(2, pass.watching)
        assertEquals(2, AutomationWatcher.watched().size)
    }

    @Test
    fun expiredWatchesAreDroppedWithoutAFetch() = runBlocking {
        AutomationWatcher.watch("srv-1", null, "Standup")
        now += AutomationWatcher.WATCH_TTL_MS + 1
        val pass = AutomationWatcher.runPass()
        assertTrue(routing.callsSnapshot().isEmpty())
        assertEquals(0, pass.remaining)
        assertTrue(AutomationWatcher.watched().isEmpty())
    }

    @Test
    fun watchPersistsInTheStoreAndMergesKnownIds() {
        AutomationWatcher.watch("srv-1", "file-1", "Standup", setOf("a"))
        now += 5
        AutomationWatcher.watch("srv-1", null, null, setOf("b"))
        val w = AutomationWatcher.watched().single()
        assertEquals("file-1", w.fileId)
        assertEquals("Standup", w.title)
        assertEquals(setOf("a", "b"), w.knownDeliveryIds)
        assertEquals(now, w.since)
        assertTrue(RecordingStore.watchedAutomationsJson!!.contains("srv-1"))
    }

    @Test
    fun switchingServersForgetsWatchesAndAnnouncements() {
        AutomationWatcher.watch("srv-1", null, "Standup")
        RecordingStore.announcedAutomationsJson = """["d:x"]"""
        RecordingStore.clearServerState()
        assertTrue(AutomationWatcher.watched().isEmpty())
        assertEquals(null, RecordingStore.announcedAutomationsJson)
    }

    @Test
    fun currentTitlePrefersTheRecordsPresentName() {
        RecordingStore.addFiles(listOf(RecordingFile(sessionId = 1, deviceSN = "SN-A", name = "Untitled Recording", duration = 5, createdAt = 1000)))
        val rec = RecordingStore.allFiles.single()
        RecordingStore.markAsUploaded("SN-A", 1, "srv-1")
        RecordingStore.updateTranscript(rec.id, """{"title":"Renamed later"}""")
        val w = AutomationWatcher.Watch("srv-1", rec.id, "Untitled Recording", now, emptySet())
        assertEquals("Renamed later", AutomationWatcher.currentTitle(w))
        assertEquals("Fallback", AutomationWatcher.currentTitle(AutomationWatcher.Watch("srv-9", null, "Fallback", now, emptySet())))
    }

    // MARK: - kick

    @Test
    fun kickSchedulesOnceAndPollsUntilSettled() {
        AutomationWatcher.kicker = { AutomationWatcher.kick() }
        routing.script(
            "srv-1",
            ok(runJson(deliveries = listOf(deliveryJson("d1", resultStatus = "queued")))),
            ok(runJson(deliveries = listOf(deliveryJson("d1", resultStatus = "queued")))),
            ok(runJson(deliveries = listOf(deliveryJson("d1", resultStatus = "done", summary = "Created: z.md"))))
        )
        AutomationWatcher.watch("srv-1", null, "Standup") // kicks

        awaitCondition("announcement") { synchronized(notifier) { notifier.finished.size == 1 } }
        awaitCondition("watch settled") { AutomationWatcher.watched().isEmpty() }
        assertEquals(1, scheduleCalls.get())
        assertTrue(routing.callsSnapshot().size >= 3)
    }

    @Test
    fun repeatedKicksShareOnePollingLoop() {
        AutomationWatcher.kicker = { AutomationWatcher.kick() }
        AutomationWatcher.inAppPollDelayMs = 200L
        routing.script("srv-1", ok(runJson(deliveries = listOf(deliveryJson("d1", resultStatus = "queued")))))
        routing.script("srv-2", ok(runJson(deliveries = listOf(deliveryJson("d2", resultStatus = "queued")))))
        AutomationWatcher.watch("srv-1", null, "A")
        AutomationWatcher.watch("srv-2", null, "B")
        AutomationWatcher.kick()
        AutomationWatcher.kick()
        Thread.sleep(700)
        // Three or four polls in 700 ms at 200 ms apart: one loop, not several interleaved ones.
        val calls = routing.callsSnapshot()
        val perRecording = calls.count { it == "srv-1" }
        assertTrue("srv-1 polled $perRecording times; several loops would poll many more", perRecording in 2..5)
        assertEquals("scheduler is cheap and idempotent, so every kick may call it", 4, scheduleCalls.get())
    }

    @Test
    fun kickWithNothingWatchedTouchesNothing() {
        AutomationWatcher.kick()
        Thread.sleep(100)
        assertEquals(0, scheduleCalls.get())
        assertTrue(routing.callsSnapshot().isEmpty())
    }
}
