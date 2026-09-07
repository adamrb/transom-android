package org.plaudbridge.app.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * TitleSyncManager pass semantics with ApiClient faked out behind TranscriptSource:
 *  - Ready: transcript stored, title captured, list observers notified
 *  - Pending (409): counted as remaining so the worker retries
 *  - Error / non-JSON 200: counted as remaining (transient), nothing stored
 *  - NotFound / AuthError: skipped, NOT remaining (retrying cannot change the answer); AuthError
 *    aborts the pass so one bad token does not produce N rejected requests
 *  - only records with a serverId and no cached transcript are fetched
 *  - kick(): schedules once, polls while pending, no-op without server config or work
 */
@RunWith(RobolectricTestRunner::class)
class TitleSyncManagerTest {

    /** Scripted responses per recording id; anything unscripted is a hard failure. */
    private class FakeSource : TitleSyncManager.TranscriptSource {
        val responses = mutableMapOf<String, ArrayDeque<ApiClient.TranscriptResult>>()
        val calls = mutableListOf<String>()

        fun script(id: String, vararg results: ApiClient.TranscriptResult) {
            responses.getOrPut(id) { ArrayDeque() }.addAll(results)
        }

        override fun fetchTranscript(recordingId: String): ApiClient.TranscriptResult {
            synchronized(calls) { calls.add(recordingId) }
            val queue = responses[recordingId] ?: error("unexpected fetch for $recordingId")
            // The last scripted result repeats (a Ready stays Ready, a 404 stays 404).
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }

        fun callsSnapshot(): List<String> = synchronized(calls) { calls.toList() }
    }

    private lateinit var context: Context
    private lateinit var source: FakeSource
    private val filesChanged = AtomicInteger()
    private val scheduleCalls = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        File(context.filesDir, "recordings.json").delete()
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "test-token"

        source = FakeSource()
        TitleSyncManager.transcriptSource = source
        filesChanged.set(0)
        TitleSyncManager.onFilesChanged = { filesChanged.incrementAndGet() }
        scheduleCalls.set(0)
        TitleSyncManager.scheduler = { scheduleCalls.incrementAndGet() }
        TitleSyncManager.inAppPollDelayMs = 50L
    }

    @After
    fun tearDown() {
        TitleSyncManager.inAppPollDelayMs = 20_000L
    }

    private fun addRecording(session: Long, serverId: String?, transcript: String? = null): RecordingFile {
        RecordingStore.addFiles(
            listOf(RecordingFile(sessionId = session, deviceSN = "SN-A", name = "Untitled Recording", duration = 5, createdAt = session * 1000))
        )
        val rec = RecordingStore.allFiles.first { it.sessionId == session }
        if (serverId != null) RecordingStore.markAsUploaded("SN-A", session, serverId)
        if (transcript != null) RecordingStore.updateTranscript(rec.id, transcript)
        return RecordingStore.allFiles.first { it.sessionId == session }
    }

    private fun ready(title: String?): ApiClient.TranscriptResult.Ready {
        val titleJson = if (title == null) "null" else "\"$title\""
        return ApiClient.TranscriptResult.Ready("""{"text":"hello","segments":[],"summary":"s","title":$titleJson}""")
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for: $what")
    }

    // MARK: - runPass classification

    @Test
    fun readyStoresTranscriptAndTitleAndNotifies() = runBlocking {
        addRecording(1, "srv-1")
        source.script("srv-1", ready("Budget planning call"))

        val result = TitleSyncManager.runPass()

        assertEquals(TitleSyncManager.PassResult(stored = 1, pending = 0, failed = 0, skipped = 0), result)
        assertEquals(0, result.remaining)
        val rec = RecordingStore.allFiles.single()
        assertEquals("Budget planning call", rec.serverTitle)
        assertEquals("Budget planning call", rec.displayName)
        assertTrue(rec.transcriptJSON!!.contains("\"text\":\"hello\""))
        assertEquals(1, filesChanged.get())
        assertTrue(RecordingStore.awaitingTranscript.isEmpty())
    }

    @Test
    fun readyWithoutTitleStillStoresTranscript() = runBlocking {
        // Old/unsummarized recording: the server sends "title": null. The transcript is cached
        // (so the file leaves the work list) and the name stays "Untitled Recording".
        addRecording(1, "srv-1")
        source.script("srv-1", ready(null))

        val result = TitleSyncManager.runPass()

        assertEquals(1, result.stored)
        val rec = RecordingStore.allFiles.single()
        assertNull(rec.serverTitle)
        assertEquals("Untitled Recording", rec.displayName)
        assertTrue(rec.transcriptJSON != null)
    }

    @Test
    fun pendingCountsAsRemainingAndStoresNothing() = runBlocking {
        addRecording(1, "srv-1")
        source.script("srv-1", ApiClient.TranscriptResult.Pending)

        val result = TitleSyncManager.runPass()

        assertEquals(TitleSyncManager.PassResult(stored = 0, pending = 1, failed = 0, skipped = 0), result)
        assertEquals(1, result.remaining)
        assertNull(RecordingStore.allFiles.single().transcriptJSON)
        assertEquals(0, filesChanged.get())
    }

    @Test
    fun transientErrorCountsAsRemaining() = runBlocking {
        addRecording(1, "srv-1")
        source.script("srv-1", ApiClient.TranscriptResult.Error("HTTP 503"))

        val result = TitleSyncManager.runPass()

        assertEquals(1, result.failed)
        assertEquals(1, result.remaining)
        assertNull(RecordingStore.allFiles.single().transcriptJSON)
    }

    @Test
    fun nonJsonSuccessBodyIsNotStoredAndRetriedLater() = runBlocking {
        // A captive portal answering 200 with HTML must not be cached as "the transcript";
        // that would end the retries for this file with garbage on disk.
        addRecording(1, "srv-1")
        source.script("srv-1", ApiClient.TranscriptResult.Ready("<html>login</html>"))

        val result = TitleSyncManager.runPass()

        assertEquals(1, result.failed)
        assertEquals(0, result.stored)
        assertNull(RecordingStore.allFiles.single().transcriptJSON)
        assertEquals(1, RecordingStore.awaitingTranscript.size)
    }

    @Test
    fun notFoundIsSkippedNotRemaining() = runBlocking {
        // The server does not know this id (stale/foreign id). Repeating the request cannot
        // help, so it must not keep the worker retrying; the record is left untouched for the
        // detail screen's lookup path.
        addRecording(1, "srv-gone")
        source.script("srv-gone", ApiClient.TranscriptResult.NotFound)

        val result = TitleSyncManager.runPass()

        assertEquals(TitleSyncManager.PassResult(stored = 0, pending = 0, failed = 0, skipped = 1), result)
        assertEquals(0, result.remaining)
        val rec = RecordingStore.allFiles.single()
        assertEquals("srv-gone", rec.serverId)
        assertNull(rec.transcriptJSON)
    }

    @Test
    fun authErrorAbortsThePassAndIsNotRemaining() = runBlocking {
        addRecording(1, "srv-1")
        addRecording(2, "srv-2")
        addRecording(3, "srv-3")
        source.script("srv-1", ApiClient.TranscriptResult.AuthError(401))
        source.script("srv-2", ApiClient.TranscriptResult.AuthError(401))
        source.script("srv-3", ApiClient.TranscriptResult.AuthError(401))

        val result = TitleSyncManager.runPass()

        assertEquals(1, result.skipped)
        assertEquals(0, result.remaining)
        assertEquals("one rejected request is enough", 1, source.callsSnapshot().size)
    }

    @Test
    fun mixedOutcomesAreCountedPerFile() = runBlocking {
        addRecording(1, "srv-ready")
        addRecording(2, "srv-pending")
        addRecording(3, "srv-missing")
        addRecording(4, "srv-down")
        source.script("srv-ready", ready("Titled"))
        source.script("srv-pending", ApiClient.TranscriptResult.Pending)
        source.script("srv-missing", ApiClient.TranscriptResult.NotFound)
        source.script("srv-down", ApiClient.TranscriptResult.Error("timeout"))

        val result = TitleSyncManager.runPass()

        assertEquals(TitleSyncManager.PassResult(stored = 1, pending = 1, failed = 1, skipped = 1), result)
        assertEquals(2, result.remaining)
        assertEquals("Titled", RecordingStore.allFiles.first { it.sessionId == 1L }.displayName)
        assertEquals(3, RecordingStore.awaitingTranscript.size)
    }

    // MARK: - Work list

    @Test
    fun onlyUploadedRecordingsWithoutTranscriptAreFetched() = runBlocking {
        addRecording(1, serverId = null)                                  // not uploaded
        addRecording(2, serverId = "srv-cached", transcript = """{"text":"t","title":"Old"}""") // done
        addRecording(3, serverId = "srv-new")                             // work
        source.script("srv-new", ready("New title"))

        val result = TitleSyncManager.runPass()

        assertEquals(1, result.stored)
        assertEquals(listOf("srv-new"), source.callsSnapshot())
        assertEquals("Old", RecordingStore.allFiles.first { it.sessionId == 2L }.displayName)
    }

    @Test
    fun manualRenameSurvivesAnArrivingTitle() = runBlocking {
        val rec = addRecording(1, "srv-1")
        RecordingStore.renameFile(rec, "Call with Sam")
        source.script("srv-1", ready("Budget planning call"))

        TitleSyncManager.runPass()

        val stored = RecordingStore.allFiles.single()
        assertEquals("Budget planning call", stored.serverTitle) // remembered...
        assertEquals("Call with Sam", stored.displayName)        // ...but never shown over the rename
    }

    @Test
    fun nothingAwaitingMakesNoFetches() = runBlocking {
        addRecording(1, serverId = null)

        val result = TitleSyncManager.runPass()

        assertEquals(TitleSyncManager.PassResult(stored = 0, pending = 0, failed = 0, skipped = 0), result)
        assertTrue(source.callsSnapshot().isEmpty())
        assertEquals(0, filesChanged.get())
    }

    @Test
    fun serverConfigChangeMidFetchDiscardsTheDocument() = runBlocking {
        addRecording(1, "srv-1")
        TitleSyncManager.transcriptSource = TitleSyncManager.TranscriptSource {
            RecordingStore.serverAuthToken = "rotated"
            ready("Old server title")
        }

        val result = TitleSyncManager.runPass()

        assertEquals(1, result.failed)
        assertNull(RecordingStore.allFiles.single().transcriptJSON)
        assertNull(RecordingStore.allFiles.single().serverTitle)
    }

    // MARK: - Guard

    @Test
    fun runPassWhileAnotherPassRunsReportsAlreadyRunning() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        addRecording(1, "srv-1")
        TitleSyncManager.transcriptSource = TitleSyncManager.TranscriptSource {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            ready("Late title")
        }

        TitleSyncManager.kick()
        assertTrue(started.await(10, TimeUnit.SECONDS))

        val result = TitleSyncManager.runPass()
        assertTrue(result.alreadyRunning)
        assertEquals(1, result.pending)

        release.countDown()
        awaitCondition("title stored") { RecordingStore.allFiles.single().serverTitle == "Late title" }
    }

    // MARK: - kick()

    @Test
    fun kickSchedulesOnceAndPollsUntilReady() {
        addRecording(1, "srv-1")
        source.script("srv-1", ApiClient.TranscriptResult.Pending, ApiClient.TranscriptResult.Pending, ready("Finally"))

        TitleSyncManager.kick()

        awaitCondition("title stored after polling") { RecordingStore.allFiles.single().serverTitle == "Finally" }
        awaitCondition("scheduled") { scheduleCalls.get() == 1 }
        assertEquals(3, source.callsSnapshot().size)
        Thread.sleep(150) // any extra poll after Ready would land here
        assertEquals(3, source.callsSnapshot().size)
        assertEquals(1, scheduleCalls.get())
    }

    @Test
    fun kickStopsPollingAfterTheCap() {
        addRecording(1, "srv-slow")
        source.script("srv-slow", ApiClient.TranscriptResult.Pending)

        TitleSyncManager.kick()

        awaitCondition("polls exhausted") { source.callsSnapshot().size == TitleSyncManager.MAX_IN_APP_POLLS + 1 }
        Thread.sleep(200)
        assertEquals(TitleSyncManager.MAX_IN_APP_POLLS + 1, source.callsSnapshot().size)
        // WorkManager owns the retry from here (the scheduler was asked exactly once).
        assertEquals(1, scheduleCalls.get())
    }

    @Test
    fun kickWithoutServerConfigDoesNothing() {
        addRecording(1, "srv-1")
        source.script("srv-1", ready("x"))
        RecordingStore.serverBaseUrl = null

        TitleSyncManager.kick()

        Thread.sleep(200)
        assertEquals(0, scheduleCalls.get())
        assertTrue(source.callsSnapshot().isEmpty())
    }

    @Test
    fun kickWithNothingAwaitingDoesNotSchedule() {
        addRecording(1, serverId = "srv-done", transcript = """{"text":"t","title":"Done"}""")

        TitleSyncManager.kick()

        Thread.sleep(200)
        assertEquals(0, scheduleCalls.get())
        assertTrue(source.callsSnapshot().isEmpty())
    }

    // MARK: - storeTranscript (shared with the detail screen)

    @Test
    fun storeTranscriptCapturesTitleAndNotifies() {
        val rec = addRecording(1, "srv-1")

        TitleSyncManager.storeTranscript(rec.id, """{"text":"t","segments":[],"title":"From detail"}""")

        assertEquals("From detail", RecordingStore.allFiles.single().displayName)
        assertEquals(1, filesChanged.get())
    }

    @Test
    fun looksLikeTranscriptAcceptsObjectsAndArraysOnly() {
        assertTrue(TitleSyncManager.looksLikeTranscript("""{"text":"t"}"""))
        assertTrue(TitleSyncManager.looksLikeTranscript("""  [{"text":"seg"}]"""))
        assertFalse(TitleSyncManager.looksLikeTranscript("<html>login</html>"))
        assertFalse(TitleSyncManager.looksLikeTranscript(""))
        assertFalse(TitleSyncManager.looksLikeTranscript("{not json"))
    }
}
