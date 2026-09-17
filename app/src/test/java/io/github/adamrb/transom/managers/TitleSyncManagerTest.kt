package io.github.adamrb.transom.managers

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
import io.github.adamrb.transom.models.RecordingFile
import io.github.adamrb.transom.net.ApiClient
import io.github.adamrb.transom.storage.RecordingStore
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
 *  - AuthError: skipped, NOT remaining (retrying cannot change the answer) and aborts the pass so
 *    one bad token does not produce N rejected requests
 *  - NotFound: one lookup by device_sn + session_id; a new id is stored and fetched next pass,
 *    no id clears the stale one so the file leaves the work list for good
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

    /** Scripted lookup answers per (SN, session); anything unscripted is a hard failure. */
    private class FakeLookup : TitleSyncManager.IdLookup {
        val responses = mutableMapOf<Pair<String, Long>, ApiClient.LookupResult>()
        val calls = mutableListOf<Pair<String, Long>>()
        override fun lookup(deviceSN: String, sessionId: Long): ApiClient.LookupResult {
            synchronized(calls) { calls.add(deviceSN to sessionId) }
            return responses[deviceSN to sessionId] ?: error("unexpected lookup for $deviceSN/$sessionId")
        }
    }

    private lateinit var context: Context
    private lateinit var source: FakeSource
    private lateinit var lookup: FakeLookup
    private val filesChanged = AtomicInteger()
    private val scheduleCalls = AtomicInteger()
    private val repairKicks = AtomicInteger()
    /** File ids handed to the transcript-stored seam (the notification hook). */
    private val transcriptsStored = mutableListOf<String>()

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
        lookup = FakeLookup()
        TitleSyncManager.idLookup = lookup
        repairKicks.set(0)
        TitleSyncManager.onServerIdRepaired = { repairKicks.incrementAndGet() } // MarksSyncManager not under test
        filesChanged.set(0)
        TitleSyncManager.onFilesChanged = { filesChanged.incrementAndGet() }
        scheduleCalls.set(0)
        TitleSyncManager.scheduler = { scheduleCalls.incrementAndGet() }
        transcriptsStored.clear()
        TitleSyncManager.onTranscriptStored = { file, _ -> synchronized(transcriptsStored) { transcriptsStored.add(file.id) } }
        TitleSyncManager.inAppPollDelayMs = 50L
        TitleSyncManager.resetLegacyAttemptsForTest()
        io.github.adamrb.transom.ui.recordings.RecordingsRepository.reset()
    }

    @After
    fun tearDown() {
        TitleSyncManager.inAppPollDelayMs = 20_000L
        io.github.adamrb.transom.ui.recordings.RecordingsRepository.reset()
    }

    /** Put server rows into the shared list snapshot, as a Recordings tab refresh would. */
    private fun seedServerList(vararg ids: String) = runBlocking {
        val repo = io.github.adamrb.transom.ui.recordings.RecordingsRepository
        repo.listSource = io.github.adamrb.transom.ui.recordings.RecordingsRepository.ListSource {
            ApiClient.ListResult.Ok(ids.map {
                io.github.adamrb.transom.models.ServerRecording.fromJson(
                    org.json.JSONObject("""{"id":"$it","device_sn":"SN-A","session_id":1,"filename":"$it.mp3","status":"done","title":"$it"}""")
                )
            })
        }
        repo.refresh()
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

    // MARK: - transcript-stored hook (notifications)

    @Test
    fun awaitingTranscriptStoredFiresHookWithTitledFile() = runBlocking {
        val rec = addRecording(1, "srv-1")
        var seenTitle: String? = null
        TitleSyncManager.onTranscriptStored = { file, raw ->
            synchronized(transcriptsStored) { transcriptsStored.add(file.id) }
            seenTitle = file.displayName
            assertTrue(raw.contains("Fresh AI title"))
        }
        source.script("srv-1", ready("Fresh AI title"))

        val pass = TitleSyncManager.runPass()

        assertEquals(1, pass.stored)
        assertEquals(listOf(rec.id), transcriptsStored)
        // The hook sees the record AFTER the title was captured, so a notification can use it.
        assertEquals("Fresh AI title", seenTitle)
    }

    @Test
    fun legacyRefetchDoesNotFireTranscriptStoredHook() = runBlocking {
        addRecording(1, "srv-1", transcript = """{"text":"old","segments":[]}""")
        source.script("srv-1", ready("Fresh AI title"))

        val pass = TitleSyncManager.runPass()

        assertEquals(1, pass.stored)
        assertTrue("a pre-title refetch is not news to the user", transcriptsStored.isEmpty())
    }

    @Test
    fun transcriptStoredHookFailureDoesNotFailThePass() = runBlocking {
        addRecording(1, "srv-1")
        TitleSyncManager.onTranscriptStored = { _, _ -> error("notification plumbing broke") }
        source.script("srv-1", ready("Fresh AI title"))

        val pass = TitleSyncManager.runPass()

        assertEquals(1, pass.stored)
        assertEquals(0, pass.failed)
        assertEquals("Fresh AI title", RecordingStore.allFiles.first().serverTitle)
    }

    // MARK: - pre-title cached transcripts

    @Test
    fun cachedTranscriptWithoutTitleKeyIsRefetchedOnceAndTitled() = runBlocking {
        // Transcript cached before the server produced titles: no "title" key at all.
        val rec = addRecording(1, "srv-1", transcript = """{"text":"old","segments":[]}""")
        assertNull(rec.serverTitle)
        source.script("srv-1", ready("Fresh AI title"))

        val first = TitleSyncManager.runPass()
        assertEquals(1, first.stored)
        assertEquals("Fresh AI title", RecordingStore.allFiles.first().serverTitle)
        assertEquals("Fresh AI title", RecordingStore.allFiles.first().displayName)

        // Now current: has a title key, so it is no longer a candidate; and even a titleless
        // current transcript is only ever attempted once per process.
        val second = TitleSyncManager.runPass()
        assertEquals(0, second.stored)
        assertEquals(listOf("srv-1"), source.callsSnapshot())
    }

    @Test
    fun legacyRefetchThatIsNotReadyIsSkippedNotPendingAndNotRepeated() = runBlocking {
        addRecording(2, "srv-2", transcript = """{"text":"old","segments":[]}""")
        source.script("srv-2", ApiClient.TranscriptResult.Pending)

        val pass = TitleSyncManager.runPass()
        assertEquals(0, pass.pending)
        assertEquals(1, pass.skipped)
        TitleSyncManager.runPass()
        assertEquals(1, source.callsSnapshot().size)
    }

    @Test
    fun renamedOrAlreadyCurrentCachedTranscriptsAreNotRefetched() = runBlocking {
        val renamed = addRecording(3, "srv-3", transcript = """{"text":"old","segments":[]}""")
        RecordingStore.renameFile(renamed, "My own name")
        addRecording(4, "srv-4", transcript = """{"text":"cur","segments":[],"title":null}""")

        val pass = TitleSyncManager.runPass()
        assertEquals(0, pass.stored + pass.pending + pass.failed + pass.skipped)
        assertTrue(source.callsSnapshot().isEmpty())
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

    // MARK: - 404 repair

    @Test
    fun notFoundWithASuccessfulLookupReplacesTheServerIdAndFetchesUnderItNextPass() = runBlocking {
        // Server database was rebuilt: the old id is gone but the recording was re-registered.
        addRecording(1, "srv-stale")
        source.script("srv-stale", ApiClient.TranscriptResult.NotFound)
        source.script("srv-fresh", ready("Recovered title"))
        lookup.responses["SN-A" to 1L] = ApiClient.LookupResult.Found("srv-fresh")

        val first = TitleSyncManager.runPass()

        assertEquals(TitleSyncManager.PassResult(stored = 0, pending = 1, failed = 0, skipped = 0), first)
        assertEquals("srv-fresh", RecordingStore.allFiles.single().serverId)
        assertEquals(listOf("SN-A" to 1L), lookup.calls)
        assertEquals("lists must learn the new id even before the transcript lands", 1, filesChanged.get())

        val second = TitleSyncManager.runPass()
        assertEquals(1, second.stored)
        assertEquals("Recovered title", RecordingStore.allFiles.single().displayName)
        assertEquals(listOf("srv-stale", "srv-fresh"), source.callsSnapshot())
        assertEquals("one lookup was enough", 1, lookup.calls.size)
    }

    @Test
    fun legacyRecordWithARepairedIdIsRefetchedUnderTheNewIdNextPass() = runBlocking {
        // Pre-title cached transcript AND a stale id: the one legacy attempt went to an id the
        // server does not know, so the repaired id must still get its fetch.
        addRecording(1, "srv-stale", transcript = """{"text":"old","segments":[]}""")
        source.script("srv-stale", ApiClient.TranscriptResult.NotFound)
        source.script("srv-fresh", ready("Recovered legacy title"))
        lookup.responses["SN-A" to 1L] = ApiClient.LookupResult.Found("srv-fresh")

        val first = TitleSyncManager.runPass()
        assertEquals(1, first.pending)
        assertEquals("srv-fresh", RecordingStore.allFiles.single().serverId)

        val second = TitleSyncManager.runPass()
        assertEquals(1, second.stored)
        assertEquals("Recovered legacy title", RecordingStore.allFiles.single().displayName)
        assertEquals(listOf("srv-stale", "srv-fresh"), source.callsSnapshot())

        // And it is once more a one-shot: a third pass makes no calls.
        TitleSyncManager.runPass()
        assertEquals(2, source.callsSnapshot().size)
    }

    @Test
    fun repairAndClearDropTheStaleRowFromTheServerListSnapshot() = runBlocking {
        // The list was fetched before the server lost the id; the merged row would keep opening
        // the dead id through that row until the next refresh.
        addRecording(1, "srv-stale")
        addRecording(2, "srv-gone")
        seedServerList("srv-stale", "srv-gone", "srv-other")
        source.script("srv-stale", ApiClient.TranscriptResult.NotFound)
        source.script("srv-gone", ApiClient.TranscriptResult.NotFound)
        lookup.responses["SN-A" to 1L] = ApiClient.LookupResult.Found("srv-fresh")
        lookup.responses["SN-A" to 2L] = ApiClient.LookupResult.NotFound

        TitleSyncManager.runPass()

        assertEquals(listOf("srv-other"), io.github.adamrb.transom.ui.recordings.RecordingsRepository.server.value.map { it.id })
    }

    @Test
    fun notFoundWithAFailedLookupClearsTheStaleIdAndStopsFetching() = runBlocking {
        addRecording(1, "srv-gone")
        source.script("srv-gone", ApiClient.TranscriptResult.NotFound)
        lookup.responses["SN-A" to 1L] = ApiClient.LookupResult.NotFound

        val first = TitleSyncManager.runPass()

        assertEquals(TitleSyncManager.PassResult(stored = 0, pending = 0, failed = 0, skipped = 1), first)
        assertEquals(0, first.remaining)
        val rec = RecordingStore.allFiles.single()
        assertNull(rec.serverId)
        assertTrue("the upload itself is not in doubt", rec.uploaded)
        assertNull(rec.transcriptJSON)
        assertTrue(RecordingStore.awaitingTranscript.isEmpty())
        assertEquals(1, filesChanged.get())

        // The next pass (every resume used to repeat the 404) has nothing to do.
        val second = TitleSyncManager.runPass()
        assertEquals(TitleSyncManager.PassResult(stored = 0, pending = 0, failed = 0, skipped = 0), second)
        assertEquals(listOf("srv-gone"), source.callsSnapshot())
        assertEquals(1, lookup.calls.size)
    }

    @Test
    fun notFoundWithLookupAuthOrTransientErrorLeavesTheIdAlone() = runBlocking {
        // Neither answer proves the id is stale, so nothing is changed. A transient lookup
        // failure is still worth another pass (failed, so the worker keeps retrying); an auth
        // error is not (skipped).
        addRecording(1, "srv-a")
        addRecording(2, "srv-b")
        source.script("srv-a", ApiClient.TranscriptResult.NotFound)
        source.script("srv-b", ApiClient.TranscriptResult.NotFound)
        lookup.responses["SN-A" to 1L] = ApiClient.LookupResult.Error("timeout")
        lookup.responses["SN-A" to 2L] = ApiClient.LookupResult.AuthError(403)

        val result = TitleSyncManager.runPass()

        assertEquals(TitleSyncManager.PassResult(stored = 0, pending = 0, failed = 1, skipped = 1), result)
        assertEquals(1, result.remaining)
        assertEquals(setOf("srv-a", "srv-b"), RecordingStore.allFiles.map { it.serverId }.toSet())
    }

    @Test
    fun notFoundWithLookupConfirmingTheSameIdChangesNothingAndIsNotAskedAgain() = runBlocking {
        addRecording(1, "srv-odd")
        source.script("srv-odd", ApiClient.TranscriptResult.NotFound)
        lookup.responses["SN-A" to 1L] = ApiClient.LookupResult.Found("srv-odd")

        val result = TitleSyncManager.runPass()

        assertEquals(1, result.skipped)
        assertEquals("srv-odd", RecordingStore.allFiles.single().serverId)

        // Every resume kicks a pass; the contradiction must not cost two requests each time.
        val again = TitleSyncManager.runPass()
        assertEquals(TitleSyncManager.PassResult(stored = 0, pending = 0, failed = 0, skipped = 0), again)
        assertEquals(listOf("srv-odd"), source.callsSnapshot())
        assertEquals(1, lookup.calls.size)

        // A different id for the same file (detail screen, later upload) is a fresh question.
        RecordingStore.updateServerId(RecordingStore.allFiles.single().id, "srv-new")
        source.script("srv-new", ready("Now it works"))
        assertEquals(1, TitleSyncManager.runPass().stored)
    }

    @Test
    fun retranscribeReopensASettledMiss() = runBlocking {
        addRecording(1, "srv-odd")
        source.script("srv-odd", ApiClient.TranscriptResult.NotFound, ready("Transcribed at last"))
        lookup.responses["SN-A" to 1L] = ApiClient.LookupResult.Found("srv-odd")
        TitleSyncManager.runPass()
        assertEquals(0, TitleSyncManager.runPass().stored + TitleSyncManager.runPass().skipped) // settled

        TitleSyncManager.reopen("srv-odd") // what RecordingActions.retranscribe does on success

        assertEquals(1, TitleSyncManager.runPass().stored)
        assertEquals("Transcribed at last", RecordingStore.allFiles.single().displayName)
    }

    @Test
    fun repairedIdSendsTheMarksAgain() = runBlocking {
        val rec = addRecording(1, "srv-stale")
        RecordingStore.updateMarks(rec.id, listOf(2.0))
        RecordingStore.markMarksSynced(rec.id, listOf(2.0))
        source.script("srv-stale", ApiClient.TranscriptResult.NotFound)
        lookup.responses["SN-A" to 1L] = ApiClient.LookupResult.Found("srv-fresh")

        TitleSyncManager.runPass()

        // The new record never saw the marks; MarksSyncManager's work list has the file again,
        // and it was kicked, since no upload or device connect may be coming to do it.
        assertEquals(listOf(rec.id), RecordingStore.awaitingMarksSync.map { it.id })
        assertEquals(1, repairKicks.get())
    }

    @Test
    fun clearedOrSettledIdsDoNotKickTheMarksSync() = runBlocking {
        addRecording(1, "srv-gone")
        addRecording(2, "srv-odd")
        source.script("srv-gone", ApiClient.TranscriptResult.NotFound)
        source.script("srv-odd", ApiClient.TranscriptResult.NotFound)
        lookup.responses["SN-A" to 1L] = ApiClient.LookupResult.NotFound
        lookup.responses["SN-A" to 2L] = ApiClient.LookupResult.Found("srv-odd")

        TitleSyncManager.runPass()

        assertEquals(0, repairKicks.get())
    }

    @Test
    fun notFoundWithABlankDeviceSnIsNotLookedUp() = runBlocking {
        RecordingStore.addFiles(listOf(RecordingFile(sessionId = 9, deviceSN = "", name = "legacy", duration = 5, createdAt = 9_000)))
        RecordingStore.markAsUploaded("", 9, "srv-legacy")
        source.script("srv-legacy", ApiClient.TranscriptResult.NotFound)

        val result = TitleSyncManager.runPass()

        assertEquals(1, result.skipped)
        assertTrue(lookup.calls.isEmpty())
        assertEquals("srv-legacy", RecordingStore.allFiles.single().serverId)
    }

    @Test
    fun transcriptAuthErrorStillAbortsWithoutAnyLookup() = runBlocking {
        addRecording(1, "srv-1")
        source.script("srv-1", ApiClient.TranscriptResult.AuthError(401))

        val result = TitleSyncManager.runPass()

        assertEquals(1, result.skipped)
        assertTrue(lookup.calls.isEmpty())
        assertEquals("srv-1", RecordingStore.allFiles.single().serverId)
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
        lookup.responses["SN-A" to 3L] = ApiClient.LookupResult.NotFound

        val result = TitleSyncManager.runPass()

        assertEquals(TitleSyncManager.PassResult(stored = 1, pending = 1, failed = 1, skipped = 1), result)
        assertEquals(2, result.remaining)
        assertEquals("Titled", RecordingStore.allFiles.first { it.sessionId == 1L }.displayName)
        // The missing one lost its stale id and left the work list; pending and down remain.
        assertEquals(setOf(2L, 4L), RecordingStore.awaitingTranscript.map { it.sessionId }.toSet())
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
