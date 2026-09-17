package cloud.adamrb.transom.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
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
import cloud.adamrb.transom.models.RecordingFile
import cloud.adamrb.transom.net.ApiClient
import cloud.adamrb.transom.storage.RecordingStore
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * MarksSyncManager pass semantics with the BLE SDK and ApiClient faked out behind MarksReader and
 * MarksSink:
 *  - a device answer is normalized and stored (an empty answer is stored as an empty list)
 *  - no answer (timeout) leaves marks null and is not retried in this process
 *  - only recordings of the CONNECTED device are read, at most MAX_READS_PER_PASS per pass
 *  - one PATCH per recording with serverId + marks + !marksSynced, then flagged synced
 *  - 404 / 401 are not flagged; a 401 aborts the pass
 *  - nothing to do means no reader/sink calls at all
 *  - fetchForSession stores marks for one session and pushes them when the audio is already up
 */
@RunWith(RobolectricTestRunner::class)
class MarksSyncManagerTest {

    private class FakeReader(@Volatile var sn: String? = "SN-A") : MarksSyncManager.MarksReader {
        /** sessionId -> raw answer; a missing key means "device never answers" (null). */
        val answers = mutableMapOf<Long, List<Long>>()
        val reads = mutableListOf<Long>()
        /** Simulated device latency before answering. */
        var latencyMs = 0L

        override fun connectedDeviceSN(): String? = sn

        override suspend fun readRawMarks(sessionId: Long): List<Long>? {
            synchronized(reads) { reads.add(sessionId) }
            if (latencyMs > 0) delay(latencyMs)
            return answers[sessionId]
        }

        fun readsSnapshot(): List<Long> = synchronized(reads) { reads.toList() }
    }

    private class FakeSink : MarksSyncManager.MarksSink {
        val responses = mutableMapOf<String, ApiClient.PatchMarksResult>()
        val calls = mutableListOf<Pair<String, List<Double>>>()

        override fun patchMarks(recordingId: String, marks: List<Double>): ApiClient.PatchMarksResult {
            synchronized(calls) { calls.add(recordingId to marks) }
            return responses[recordingId] ?: error("unexpected PATCH for $recordingId")
        }

        fun callsSnapshot(): List<Pair<String, List<Double>>> = synchronized(calls) { calls.toList() }
    }

    private lateinit var context: Context
    private lateinit var reader: FakeReader
    private lateinit var sink: FakeSink

    // Real Note Pro session id (epoch seconds); marks arrive as epoch seconds in most tests.
    private val session = 1_788_758_851L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        File(context.filesDir, "recordings.json").delete()
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "test-token"

        reader = FakeReader()
        sink = FakeSink()
        MarksSyncManager.reader = reader
        MarksSyncManager.sink = sink
        MarksSyncManager.resetForTest()
    }

    @After
    fun tearDown() {
        MarksSyncManager.readTimeoutMs = 10_000L
    }

    private fun addRecording(
        sessionId: Long, sn: String = "SN-A", serverId: String? = null,
        marks: List<Double>? = null, marksSynced: Boolean = false, duration: Long = 600
    ): RecordingFile {
        RecordingStore.addFiles(
            listOf(
                RecordingFile(
                    sessionId = sessionId, deviceSN = sn, name = "Untitled Recording", duration = duration,
                    createdAt = sessionId * 1000, marks = marks, marksSynced = marksSynced
                )
            )
        )
        if (serverId != null) RecordingStore.markAsUploaded(sn, sessionId, serverId)
        return stored(sessionId, sn)
    }

    private fun stored(sessionId: Long, sn: String = "SN-A") =
        RecordingStore.allFiles.first { it.sessionId == sessionId && it.deviceSN == sn }

    private fun awaitCondition(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for: $what")
    }

    // MARK: - device reads

    @Test
    fun deviceAnswerIsNormalizedAndStored() = runBlocking {
        addRecording(session)
        reader.answers[session] = listOf(session + 6, session + 125)

        val pass = MarksSyncManager.runPass()

        assertEquals(1, pass.read)
        assertEquals(0, pass.readFailed)
        assertEquals(listOf(6.0, 125.0), stored(session).marks)
        assertFalse(stored(session).marksSynced)
    }

    @Test
    fun emptyDeviceAnswerIsStoredAsEmptyListNotNull() = runBlocking {
        addRecording(session)
        reader.answers[session] = emptyList()

        MarksSyncManager.runPass()

        assertEquals(emptyList<Double>(), stored(session).marks)
        // Read once, done: a second pass has nothing to ask the device.
        MarksSyncManager.runPass()
        assertEquals(listOf(session), reader.readsSnapshot())
    }

    @Test
    fun noAnswerLeavesMarksNullAndIsNotRetriedThisProcess() = runBlocking {
        addRecording(session) // no scripted answer: the fake returns null (timeout)

        val first = MarksSyncManager.runPass()
        assertEquals(0, first.read)
        assertEquals(1, first.readFailed)
        assertNull(stored(session).marks)

        val second = MarksSyncManager.runPass()
        assertEquals(0, second.readFailed)
        assertEquals("one attempt per session per process", listOf(session), reader.readsSnapshot())

        // A fresh process (reset) asks again.
        MarksSyncManager.resetForTest()
        reader.answers[session] = listOf(session + 1)
        MarksSyncManager.runPass()
        assertEquals(listOf(1.0), stored(session).marks)
    }

    @Test
    fun onlyRecordingsOfTheConnectedDeviceAreRead() = runBlocking {
        addRecording(session, sn = "SN-A")
        addRecording(session + 1, sn = "SN-B")
        reader.answers[session] = listOf(session + 3)
        reader.answers[session + 1] = listOf(session + 4)
        reader.sn = "SN-B"

        MarksSyncManager.runPass()

        assertEquals(listOf(session + 1), reader.readsSnapshot())
        assertNull(stored(session, "SN-A").marks)
        assertEquals(listOf(3.0), stored(session + 1, "SN-B").marks)
    }

    @Test
    fun nothingIsReadWhenNoDeviceIsConnected() = runBlocking {
        addRecording(session)
        reader.sn = null

        val pass = MarksSyncManager.runPass()

        assertEquals(0, pass.read + pass.readFailed)
        assertTrue(reader.readsSnapshot().isEmpty())
    }

    @Test
    fun readsPerPassAreBounded() = runBlocking {
        for (i in 0 until MarksSyncManager.MAX_READS_PER_PASS + 5) {
            addRecording(session + i)
            reader.answers[session + i] = emptyList()
        }

        val first = MarksSyncManager.runPass()
        assertEquals(MarksSyncManager.MAX_READS_PER_PASS, first.read)

        val second = MarksSyncManager.runPass()
        assertEquals(5, second.read)
        assertTrue(RecordingStore.allFiles.all { it.marks != null })
    }

    // MARK: - PATCH

    @Test
    fun unsyncedMarksArePatchedOnceAndFlagged() = runBlocking {
        val a = addRecording(1, serverId = "srv-1", marks = listOf(6.0))
        val b = addRecording(2, serverId = "srv-2", marks = emptyList())
        addRecording(3, serverId = "srv-3", marks = listOf(9.0), marksSynced = true) // already there
        addRecording(4, serverId = null, marks = listOf(1.0)) // not uploaded yet: nothing to PATCH against
        sink.responses["srv-1"] = ApiClient.PatchMarksResult.Ok
        sink.responses["srv-2"] = ApiClient.PatchMarksResult.Ok

        val pass = MarksSyncManager.runPass()

        assertEquals(2, pass.pushed)
        assertEquals(0, pass.pushFailed)
        assertEquals(
            setOf("srv-1" to listOf(6.0), "srv-2" to emptyList<Double>()),
            sink.callsSnapshot().toSet()
        )
        assertTrue(stored(a.sessionId).marksSynced)
        assertTrue(stored(b.sessionId).marksSynced)
        assertFalse(stored(4).marksSynced)

        // Everything flagged: the next pass makes no calls.
        val again = MarksSyncManager.runPass()
        assertEquals(0, again.pushed + again.pushFailed)
        assertEquals(2, sink.callsSnapshot().size)
    }

    @Test
    fun notFoundIsCountedFailedAndNotFlagged() = runBlocking {
        addRecording(1, serverId = "srv-1", marks = listOf(6.0))
        sink.responses["srv-1"] = ApiClient.PatchMarksResult.NotFound

        val pass = MarksSyncManager.runPass()

        assertEquals(1, pass.pushFailed)
        assertFalse(stored(1).marksSynced)
    }

    @Test
    fun authErrorAbortsThePass() = runBlocking {
        addRecording(1, serverId = "srv-1", marks = listOf(6.0))
        addRecording(2, serverId = "srv-2", marks = listOf(7.0))
        sink.responses["srv-1"] = ApiClient.PatchMarksResult.AuthError(401)
        sink.responses["srv-2"] = ApiClient.PatchMarksResult.AuthError(401)

        MarksSyncManager.runPass()

        assertEquals("one rejected request, then stop", 1, sink.callsSnapshot().size)
        assertTrue(RecordingStore.allFiles.none { it.marksSynced })
    }

    @Test
    fun transientErrorLeavesMarksForTheNextPass() = runBlocking {
        addRecording(1, serverId = "srv-1", marks = listOf(6.0))
        sink.responses["srv-1"] = ApiClient.PatchMarksResult.Error("HTTP 503")

        MarksSyncManager.runPass()
        assertFalse(stored(1).marksSynced)

        sink.responses["srv-1"] = ApiClient.PatchMarksResult.Ok
        MarksSyncManager.runPass()
        assertTrue(stored(1).marksSynced)
        assertEquals(2, sink.callsSnapshot().size)
    }

    @Test
    fun serverConfigChangeMidPatchDiscardsTheResult() = runBlocking {
        addRecording(1, serverId = "srv-1", marks = listOf(6.0))
        sink.responses["srv-1"] = ApiClient.PatchMarksResult.Ok
        MarksSyncManager.sink = MarksSyncManager.MarksSink { id, marks ->
            RecordingStore.serverAuthToken = "other-token" // user switched servers mid-request
            sink.patchMarks(id, marks)
        }

        val pass = MarksSyncManager.runPass()

        assertEquals(1, pass.pushFailed)
        assertFalse(stored(1).marksSynced)
    }

    @Test
    fun readThenPushInOnePass() = runBlocking {
        // Uploaded before this feature existed: marks unknown, server has the audio.
        addRecording(session, serverId = "srv-x")
        reader.answers[session] = listOf(session * 1000 + 30_000) // epoch ms this time
        sink.responses["srv-x"] = ApiClient.PatchMarksResult.Ok

        val pass = MarksSyncManager.runPass()

        assertEquals(1, pass.read)
        assertEquals(1, pass.pushed)
        assertEquals(listOf("srv-x" to listOf(30.0)), sink.callsSnapshot())
        assertTrue(stored(session).marksSynced)
    }

    // MARK: - nothing to do

    @Test
    fun nothingToDoMakesNoCalls() = runBlocking {
        addRecording(1, serverId = "srv-1", marks = listOf(6.0), marksSynced = true)
        addRecording(2, sn = "SN-B") // unread, but its device is not connected

        val pass = MarksSyncManager.runPass()

        assertEquals(MarksSyncManager.PassResult(0, 0, 0, 0), pass)
        assertTrue(reader.readsSnapshot().isEmpty())
        assertTrue(sink.callsSnapshot().isEmpty())
    }

    @Test
    fun pushIsSkippedWithoutServerConfig() = runBlocking {
        RecordingStore.serverBaseUrl = null
        addRecording(1, serverId = "srv-1", marks = listOf(6.0))

        MarksSyncManager.runPass()

        assertTrue(sink.callsSnapshot().isEmpty())
        assertFalse(stored(1).marksSynced)
    }

    // MARK: - kick / fetchForSession (async entry points)

    @Test
    fun kickRunsAPassInTheBackground() {
        addRecording(session)
        reader.answers[session] = listOf(session + 8)

        MarksSyncManager.kick()

        awaitCondition("marks stored") { stored(session).marks == listOf(8.0) }
    }

    @Test
    fun fetchForSessionReadsOneSessionAndPushesWhenAlreadyUploaded() {
        addRecording(session, serverId = "srv-s")
        addRecording(session + 1) // another unread session: must NOT be read by fetchForSession
        reader.answers[session] = listOf(session + 12)
        reader.answers[session + 1] = listOf(session + 1)
        sink.responses["srv-s"] = ApiClient.PatchMarksResult.Ok

        MarksSyncManager.fetchForSession("SN-A", session)

        awaitCondition("marks pushed") { stored(session).marksSynced }
        assertEquals(listOf(12.0), stored(session).marks)
        assertEquals(listOf(session), reader.readsSnapshot())
        assertNull(stored(session + 1).marks)
    }

    @Test
    fun fetchForSessionIgnoresRecordingsOfAnotherDevice() {
        addRecording(session, sn = "SN-B")
        reader.answers[session] = listOf(session + 1)
        reader.sn = "SN-A"

        MarksSyncManager.fetchForSession("SN-B", session)

        Thread.sleep(300)
        assertTrue(reader.readsSnapshot().isEmpty())
        assertNull(stored(session, "SN-B").marks)
    }

    @Test
    fun concurrentPassReportsAlreadyRunning() = runBlocking {
        addRecording(session)
        reader.answers[session] = listOf(session + 1)
        reader.latencyMs = 400
        val alreadyRunning = AtomicInteger()

        MarksSyncManager.kick()
        Thread.sleep(100) // let the kicked pass take the guard and block in the fake read
        val second = MarksSyncManager.runPass()
        if (second.alreadyRunning) alreadyRunning.incrementAndGet()

        awaitCondition("marks stored") { stored(session).marks == listOf(1.0) }
        assertEquals(1, alreadyRunning.get())
        assertEquals("the read was issued exactly once", listOf(session), reader.readsSnapshot())
    }
}
