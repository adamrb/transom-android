package org.plaudbridge.app.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/**
 * UploadManager queue + delete-after-upload safety, with the BLE SDK faked out behind
 * UploadManager.DeviceLink:
 *  - dirty-flag loop: a kick() arriving mid-run is never lost (finding 8)
 *  - blank device SN: uploaded, but NEVER device-deleted (finding 2)
 *  - SN mismatch: no delete on the wrong device; deferred via deletePendingOnDevice and
 *    retried once the matching device is connected (findings 2 + 7)
 *  - durable retry: kick() schedules the WorkManager request exactly once per call, and
 *    runPass() (the worker's entry point) classifies its outcome by counts
 */
@RunWith(RobolectricTestRunner::class)
class UploadManagerTest {

    private class FakeDeviceLink(@Volatile var sn: String? = null) : UploadManager.DeviceLink {
        val deleted = mutableListOf<Long>()
        override fun connectedDeviceSN(): String? = sn
        override fun deleteFile(sessionId: Long) {
            synchronized(deleted) { deleted.add(sessionId) }
        }
        fun deletedSnapshot(): List<Long> = synchronized(deleted) { deleted.toList() }
    }

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var fakeLink: FakeDeviceLink
    private val scheduleCalls = AtomicInteger()
    private val uploadsCompletedCalls = AtomicInteger()
    private val titlePushes = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    @Volatile private var titlePushResult: () -> org.plaudbridge.app.net.ApiClient.RecordingResult =
        { org.plaudbridge.app.net.ApiClient.RecordingResult.Error("not scripted") }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        File(context.filesDir, "recordings.json").delete()

        server = MockWebServer()
        server.start()
        RecordingStore.serverBaseUrl = server.url("/").toString().trimEnd('/')
        RecordingStore.serverAuthToken = "test-token"

        fakeLink = FakeDeviceLink()
        UploadManager.deviceLink = fakeLink
        UploadManager.onFilesChanged = {} // SyncManager touches the real SDK — not under test
        scheduleCalls.set(0)
        UploadManager.scheduler = { scheduleCalls.incrementAndGet() } // no real WorkManager here
        uploadsCompletedCalls.set(0)
        UploadManager.onUploadsCompleted = { uploadsCompletedCalls.incrementAndGet() } // TitleSyncManager not under test
        titlePushes.clear()
        UploadManager.titlePusher = UploadManager.TitlePusher { id, title ->
            titlePushes.add(id to title)
            titlePushResult()
        }
    }

    @After
    fun tearDown() {
        // Wait for any in-flight run to drain before shutting the server down.
        awaitCondition("upload runs drained") {
            UploadManager.state.value !is UploadState.Uploading
        }
        server.shutdown()
    }

    private fun addSyncedRecording(sn: String, session: Long) {
        val audio = File(context.filesDir, "rec-$session.mp3").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        RecordingStore.addFiles(
            listOf(
                RecordingFile(
                    sessionId = session,
                    deviceSN = sn,
                    name = "rec-$session",
                    duration = 5,
                    createdAt = session * 1000
                )
            )
        )
        RecordingStore.markAsSynced(sn, session, audio.absolutePath, 5)
    }

    private fun okUploadResponse(id: String): MockResponse =
        MockResponse().setResponseCode(201).setBody("""{"id":"$id","duplicate":false}""")

    /** Poll (the queue runs on real IO threads) until [condition] holds, or fail. */
    private fun awaitCondition(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for: $what")
    }

    @Test
    fun validatedUploadMarksRecordingUploaded() {
        addSyncedRecording("SN-A", 1)
        server.enqueue(okUploadResponse("srv-1"))

        UploadManager.kick()

        awaitCondition("recording uploaded") { RecordingStore.allFiles.single().uploaded }
        assertEquals("srv-1", RecordingStore.allFiles.single().serverId)
    }

    @Test
    fun rejectedUploadIsNotMarkedUploaded() {
        addSyncedRecording("SN-A", 2)
        // 200 HTML "success" (proxy login page) — strict validation must reject it.
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))

        UploadManager.kick()

        awaitCondition("run finished") { UploadManager.state.value is UploadState.Failed }
        assertFalse(RecordingStore.allFiles.single().uploaded)
        assertTrue(fakeLink.deletedSnapshot().isEmpty())
    }

    @Test
    fun kickDuringRunIsNotLost() {
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val counter = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = counter.incrementAndGet()
                if (n == 1) {
                    firstRequestStarted.countDown()
                    releaseFirst.await(10, TimeUnit.SECONDS)
                }
                return MockResponse().setResponseCode(201)
                    .setBody("""{"id":"srv-$n","duplicate":false}""")
            }
        }

        addSyncedRecording("SN-A", 10)
        UploadManager.kick()
        assertTrue(firstRequestStarted.await(10, TimeUnit.SECONDS))

        // New work + kick arrive while the first run is blocked mid-upload.
        addSyncedRecording("SN-A", 11)
        UploadManager.kick()
        releaseFirst.countDown()

        awaitCondition("both recordings uploaded") {
            RecordingStore.allFiles.count { it.uploaded } == 2
        }
    }

    @Test
    fun blankDeviceSnIsUploadedButNeverDeviceDeleted() {
        RecordingStore.deleteAfterUpload = true
        fakeLink.sn = "SN-CONNECTED" // some device IS connected — must still not be deleted from
        addSyncedRecording("", 20)
        server.enqueue(okUploadResponse("srv-20"))

        UploadManager.kick()

        awaitCondition("recording uploaded") { RecordingStore.allFiles.single().uploaded }
        awaitCondition("run finished") { UploadManager.state.value is UploadState.Idle }
        assertTrue("blank-SN recording must never be device-deleted",
            fakeLink.deletedSnapshot().isEmpty())
        assertFalse(RecordingStore.allFiles.single().deletePendingOnDevice)
    }

    @Test
    fun snMismatchDefersDeleteUntilMatchingDeviceConnects() {
        RecordingStore.deleteAfterUpload = true
        fakeLink.sn = "SN-B" // a DIFFERENT device is connected
        addSyncedRecording("SN-A", 30)
        server.enqueue(okUploadResponse("srv-30"))

        UploadManager.kick()

        awaitCondition("recording uploaded") { RecordingStore.allFiles.single().uploaded }
        awaitCondition("delete deferred") {
            RecordingStore.pendingDeviceDeletes("SN-A").size == 1
        }
        awaitCondition("run finished") { UploadManager.state.value is UploadState.Idle }
        assertTrue("must not delete session 30 from SN-B",
            fakeLink.deletedSnapshot().isEmpty())

        // The matching device reconnects: the pending delete now goes through.
        fakeLink.sn = "SN-A"
        UploadManager.kick()
        awaitCondition("deferred delete issued") { 30L in fakeLink.deletedSnapshot() }

        // Device confirms (bleDeleteFile status 0) -> pending flag cleared.
        UploadManager.handleDeviceDeleteResult(30L, 0)
        awaitCondition("pending flag cleared") {
            RecordingStore.pendingDeviceDeletes("SN-A").isEmpty()
        }
    }

    @Test
    fun failedDeviceDeleteKeepsPendingFlagForRetry() {
        RecordingStore.deleteAfterUpload = true
        fakeLink.sn = "SN-A"
        addSyncedRecording("SN-A", 40)
        server.enqueue(okUploadResponse("srv-40"))

        UploadManager.kick()
        awaitCondition("delete issued") { 40L in fakeLink.deletedSnapshot() }
        awaitCondition("run finished") { UploadManager.state.value is UploadState.Idle }

        // Device reports failure -> flag stays set so a later kick retries.
        UploadManager.handleDeviceDeleteResult(40L, 1)
        assertEquals(1, RecordingStore.pendingDeviceDeletes("SN-A").size)

        // Next kick re-issues the (deferred) delete; a correlated success then clears it.
        UploadManager.kick()
        awaitCondition("delete re-issued") { fakeLink.deletedSnapshot().count { it == 40L } == 2 }
        UploadManager.handleDeviceDeleteResult(40L, 0)
        assertTrue(RecordingStore.pendingDeviceDeletes("SN-A").isEmpty())
    }

    @Test
    fun exactlyOneDeleteCommandPerUploadedSession() {
        // processQueue issues the delete AND processPendingDeletes runs in the same kick — the
        // in-flight registry must ensure exactly ONE command goes to the device.
        RecordingStore.deleteAfterUpload = true
        fakeLink.sn = "SN-A"
        addSyncedRecording("SN-A", 60)
        server.enqueue(okUploadResponse("srv-60"))

        UploadManager.kick()

        awaitCondition("delete issued") { 60L in fakeLink.deletedSnapshot() }
        awaitCondition("run finished") { UploadManager.state.value is UploadState.Idle }
        Thread.sleep(200) // grace period: any duplicate issuance would land here
        assertEquals(listOf(60L), fakeLink.deletedSnapshot())
    }

    @Test
    fun deleteCallbackAfterDeviceSwitchIsNotMisattributed() {
        // Sessions can collide across devices. SN-B has its own (already pending) session 50;
        // SN-A's delete command for ITS session 50 completes only after the user switched to
        // SN-B. The callback must correlate with the CAPTURED target (SN-A), treat the outcome
        // as unknown (device changed), and must not clear SN-B's pending state.
        RecordingStore.deleteAfterUpload = true
        addSyncedRecording("SN-A", 50)
        addSyncedRecording("SN-B", 50)
        RecordingStore.markAsUploaded("SN-B", 50, "sid-b")
        RecordingStore.setDeletePendingOnDevice("SN-B", 50, true)

        fakeLink.sn = "SN-A"
        server.enqueue(okUploadResponse("srv-50a"))
        UploadManager.kick()
        awaitCondition("delete issued to SN-A") { 50L in fakeLink.deletedSnapshot() }
        awaitCondition("run finished") { UploadManager.state.value is UploadState.Idle }

        // Device switch between issuing the command and receiving its callback.
        fakeLink.sn = "SN-B"
        UploadManager.handleDeviceDeleteResult(50L, 0)

        // Outcome unknown for SN-A -> its pending flag stays; SN-B's state is untouched.
        assertEquals(1, RecordingStore.pendingDeviceDeletes("SN-A").size)
        assertEquals(1, RecordingStore.pendingDeviceDeletes("SN-B").size)
    }

    @Test
    fun serverConfigChangeMidUploadDiscardsResult() {
        // The old server accepted the upload while the user was switching servers — the result
        // must be discarded: not marked uploaded (and so never eligible for device deletion).
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                firstRequestStarted.countDown()
                releaseFirst.await(10, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(201)
                    .setBody("""{"id":"old-server-id","duplicate":false}""")
            }
        }

        addSyncedRecording("SN-A", 70)
        UploadManager.kick()
        assertTrue(firstRequestStarted.await(10, TimeUnit.SECONDS))

        // Server switch while the request is in flight (bumps the config generation).
        RecordingStore.serverAuthToken = "different-token"
        releaseFirst.countDown()

        awaitCondition("run finished") { UploadManager.state.value is UploadState.Failed }
        assertFalse(RecordingStore.allFiles.single().uploaded)
        assertTrue(fakeLink.deletedSnapshot().isEmpty())
    }

    // MARK: - Button-press marks in the upload metadata

    private fun metadataOf(request: RecordedRequest): org.json.JSONObject {
        val line = request.body.readUtf8().lines().first { it.trimStart().startsWith("{") && it.contains("session_id") }
        return org.json.JSONObject(line)
    }

    @Test
    fun knownMarksTravelInMetadataAndAreFlaggedSynced() = runBlocking {
        addSyncedRecording("SN-A", 100)
        val rec = RecordingStore.allFiles.single()
        RecordingStore.updateMarks(rec.id, listOf(6.0, 125.5))
        server.enqueue(okUploadResponse("srv-100"))

        val result = UploadManager.runPass()

        assertEquals(1, result.uploaded)
        assertEquals("[6,125.5]", metadataOf(server.takeRequest()).getJSONArray("marks").toString())
        val after = RecordingStore.allFiles.single()
        assertTrue(after.uploaded)
        assertTrue("server has the marks; no PATCH needed", after.marksSynced)
        assertTrue(RecordingStore.awaitingMarksSync.isEmpty())
    }

    @Test
    fun unknownMarksAreOmittedAndLeftForThePatchPath() = runBlocking {
        addSyncedRecording("SN-A", 101)
        server.enqueue(okUploadResponse("srv-101"))

        UploadManager.runPass()

        assertFalse(metadataOf(server.takeRequest()).has("marks"))
        val after = RecordingStore.allFiles.single()
        assertTrue(after.uploaded)
        assertFalse(after.marksSynced)
        // Marks read later become PATCH work against the new serverId.
        RecordingStore.updateMarks(after.id, listOf(3.0))
        assertEquals(listOf(after.id), RecordingStore.awaitingMarksSync.map { it.id })
    }

    @Test
    fun marksChangedDuringUploadAreNotFlaggedSynced() = runBlocking {
        addSyncedRecording("SN-A", 102)
        val rec = RecordingStore.allFiles.single()
        RecordingStore.updateMarks(rec.id, listOf(6.0))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // A (re)read lands while the request is in flight with a different answer.
                RecordingStore.updateMarks(rec.id, listOf(6.0, 9.0))
                return okUploadResponse("srv-102")
            }
        }

        UploadManager.runPass()

        val after = RecordingStore.allFiles.single()
        assertTrue(after.uploaded)
        assertEquals(listOf(6.0, 9.0), after.marks)
        assertFalse("the server only has [6.0]; the new list must still be PATCHed", after.marksSynced)
    }

    // MARK: - Durable retry (WorkManager seam + runPass classification)

    @Test
    fun kickSchedulesDurableRetryExactlyOnce() {
        addSyncedRecording("SN-A", 80)
        server.enqueue(okUploadResponse("srv-80"))

        UploadManager.kick()

        awaitCondition("recording uploaded") { RecordingStore.allFiles.single().uploaded }
        awaitCondition("run finished") { UploadManager.state.value is UploadState.Idle }
        Thread.sleep(200) // any re-kick from the dirty-flag tail would land here
        assertEquals(1, scheduleCalls.get())
    }

    @Test
    fun kickWithoutServerConfigDoesNotSchedule() {
        RecordingStore.serverBaseUrl = null
        UploadManager.kick()
        assertEquals(0, scheduleCalls.get())
    }

    @Test
    fun ensureScheduledSchedulesWithoutUploading() {
        addSyncedRecording("SN-A", 81)

        UploadManager.ensureScheduled()

        Thread.sleep(200)
        assertEquals(1, scheduleCalls.get())
        assertEquals(0, server.requestCount)
        assertFalse(RecordingStore.allFiles.single().uploaded)
    }

    @Test
    fun runPassAllUploadedReportsNothingRemaining() = runBlocking {
        addSyncedRecording("SN-A", 90)
        addSyncedRecording("SN-A", 91)
        server.enqueue(okUploadResponse("srv-90"))
        server.enqueue(okUploadResponse("srv-91"))

        val result = UploadManager.runPass()

        assertFalse(result.alreadyRunning)
        assertEquals(2, result.uploaded)
        assertEquals(0, result.failed)
        assertEquals(0, result.remaining)
        assertTrue(RecordingStore.allFiles.all { it.uploaded })
        assertEquals(0, scheduleCalls.get()) // runPass itself never schedules; kick does
        // One title fetch is started per pass that uploaded something, not per file.
        assertEquals(1, uploadsCompletedCalls.get())
    }

    // MARK: - Manual rename before upload

    private fun renamedServerRecording(id: String, title: String) = org.plaudbridge.app.net.ApiClient.RecordingResult.Ok(
        org.plaudbridge.app.models.ServerRecording.fromJson(
            org.json.JSONObject("""{"id":"$id","device_sn":"SN-A","session_id":1,"filename":"$id.mp3","status":"done","title":"$title"}""")
        )
    )

    @Test
    fun renameMadeBeforeUploadIsPushedToTheServerOnceAfterIt() = runBlocking {
        addSyncedRecording("SN-A", 70)
        RecordingStore.renameFile(RecordingStore.allFiles.single(), "Walk with Sam")
        server.enqueue(okUploadResponse("srv-70"))
        titlePushResult = { renamedServerRecording("srv-70", "Walk with Sam") }

        val result = UploadManager.runPass()

        assertEquals(1, result.uploaded)
        assertEquals(listOf("srv-70" to "Walk with Sam"), titlePushes.toList())
        assertEquals(1, server.requestCount) // the PATCH went through the seam, not the wire
        // Nothing pending: a second pass pushes nothing again.
        UploadManager.runPass()
        assertEquals(1, titlePushes.size)
    }

    @Test
    fun renameTypedWhileThePushIsInFlightIsSentToo() = runBlocking {
        addSyncedRecording("SN-A", 73)
        RecordingStore.renameFile(RecordingStore.allFiles.single(), "First name")
        server.enqueue(okUploadResponse("srv-73"))
        titlePushResult = {
            // The list on screen has not learned the server id yet, so this rename is local only.
            val file = RecordingStore.allFiles.single()
            if (file.name == "First name") RecordingStore.renameFile(file, "Second name")
            renamedServerRecording("srv-73", file.name)
        }

        UploadManager.runPass()

        assertEquals(listOf("srv-73" to "First name", "srv-73" to "Second name"), titlePushes.toList())
    }

    @Test
    fun titlePushStopsOnceTheServerConfigurationChanged() = runBlocking {
        // The id came from the OLD server; a PATCH with it against the new one could rename an
        // unrelated recording there. The first round goes out (same generation); the switch that
        // lands during it must stop the second round even though the name changed again.
        addSyncedRecording("SN-A", 74)
        RecordingStore.renameFile(RecordingStore.allFiles.single(), "First name")
        server.enqueue(okUploadResponse("srv-74"))
        titlePushResult = {
            RecordingStore.renameFile(RecordingStore.allFiles.single(), "Second name")
            RecordingStore.serverAuthToken = "rotated"
            renamedServerRecording("srv-74", "First name")
        }

        UploadManager.runPass()

        assertEquals(listOf("srv-74" to "First name"), titlePushes.toList())
    }

    @Test
    fun unrenamedUploadPushesNoTitle() = runBlocking {
        addSyncedRecording("SN-A", 71)
        server.enqueue(okUploadResponse("srv-71"))

        UploadManager.runPass()

        assertTrue(titlePushes.isEmpty())
    }

    @Test
    fun failedTitlePushDoesNotFailTheUpload() = runBlocking {
        addSyncedRecording("SN-A", 72)
        RecordingStore.renameFile(RecordingStore.allFiles.single(), "Standup")
        server.enqueue(okUploadResponse("srv-72"))
        titlePushResult = { throw IllegalStateException("PATCH exploded") }

        val result = UploadManager.runPass()

        assertEquals(1, result.uploaded)
        assertEquals(0, result.failed)
        val stored = RecordingStore.allFiles.single()
        assertTrue(stored.uploaded)
        assertEquals("srv-72", stored.serverId)
        assertEquals("Standup", stored.displayName) // the phone shows the pinned name regardless
    }

    @Test
    fun runPassServerErrorCountsFailureAndKeepsPending() = runBlocking {
        addSyncedRecording("SN-A", 92)
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))

        val result = UploadManager.runPass()

        assertEquals(0, result.uploaded)
        assertEquals(1, result.failed)
        assertEquals(1, result.remaining)
        assertFalse(RecordingStore.allFiles.single().uploaded)
        assertTrue(UploadManager.state.value is UploadState.Failed)
        assertEquals("no upload, no title fetch", 0, uploadsCompletedCalls.get())
    }

    @Test
    fun runPassWithNothingPendingMakesNoNetworkCalls() = runBlocking {
        val result = UploadManager.runPass()

        assertEquals(UploadManager.PassResult(uploaded = 0, failed = 0, remaining = 0), result)
        assertEquals(0, server.requestCount)
        assertTrue(UploadManager.state.value is UploadState.Idle)
    }

    @Test
    fun runPassWhileAnotherPassRunsReportsAlreadyRunning() = runBlocking {
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                firstRequestStarted.countDown()
                releaseFirst.await(10, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(201)
                    .setBody("""{"id":"srv-93","duplicate":false}""")
            }
        }
        addSyncedRecording("SN-A", 93)
        UploadManager.kick()
        assertTrue(firstRequestStarted.await(10, TimeUnit.SECONDS))

        // Second pass (what the worker would do) must not touch the file mid-upload.
        val result = UploadManager.runPass()
        assertTrue(result.alreadyRunning)
        assertEquals(0, result.uploaded)
        assertEquals(1, result.remaining)

        releaseFirst.countDown()
        awaitCondition("recording uploaded") { RecordingStore.allFiles.single().uploaded }
        assertEquals("exactly one upload request", 1, server.requestCount)
    }
}
