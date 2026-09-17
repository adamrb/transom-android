package io.github.adamrb.transom.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.github.adamrb.transom.managers.UploadManager
import io.github.adamrb.transom.models.RecordingFile
import io.github.adamrb.transom.storage.RecordingStore
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * UploadWorker result classification, driven through TestListenableWorkerBuilder so the worker
 * runs exactly as WorkManager would invoke it (no WorkManager scheduling involved). The server is
 * a MockWebServer on localhost; the scheduler seam is a counter so no WorkManager instance is
 * touched; the BLE seam reports "nothing connected", as in a background process.
 */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerTest {

    private class NoDevice : UploadManager.DeviceLink {
        override fun connectedDeviceSN(): String? = null
        override fun deleteFile(sessionId: Long) = error("no device connected in a worker process")
    }

    /** Simulates the SDK facade blowing up in a process where it was never initialized. */
    private class ThrowingLink : UploadManager.DeviceLink {
        override fun connectedDeviceSN(): String? = throw IllegalStateException("SDK not initialized")
        override fun deleteFile(sessionId: Long) = throw IllegalStateException("SDK not initialized")
    }

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val scheduleCalls = AtomicInteger()

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

        UploadManager.deviceLink = NoDevice()
        UploadManager.onFilesChanged = {}
        scheduleCalls.set(0)
        UploadManager.scheduler = { scheduleCalls.incrementAndGet() }
        UploadManager.onUploadsCompleted = {} // TitleSyncManager is not under test here
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun addSyncedRecording(sn: String, session: Long) {
        val audio = File(context.filesDir, "rec-$session.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        RecordingStore.addFiles(
            listOf(RecordingFile(sessionId = session, deviceSN = sn, name = "rec-$session", duration = 5, createdAt = session * 1000))
        )
        RecordingStore.markAsSynced(sn, session, audio.absolutePath, 5)
    }

    private fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<UploadWorker>(context).build()
        return runBlocking { worker.doWork() }
    }

    @Test
    fun allUploadedReturnsSuccess() {
        addSyncedRecording("SN-A", 1)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"srv-1","duplicate":false}"""))

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertTrue(RecordingStore.allFiles.single().uploaded)
        assertEquals("srv-1", RecordingStore.allFiles.single().serverId)
    }

    @Test
    fun nothingPendingReturnsSuccessWithoutNetwork() {
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun serverErrorReturnsRetryAndKeepsRecordingPending() {
        addSyncedRecording("SN-A", 2)
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))

        assertEquals(ListenableWorker.Result.retry(), runWorker())

        assertFalse(RecordingStore.allFiles.single().uploaded)
        assertEquals(1, RecordingStore.pendingUploads.size)
    }

    @Test
    fun unvalidatedSuccessBodyReturnsRetry() {
        // A captive portal / proxy login page with HTTP 200 must not count as uploaded.
        addSyncedRecording("SN-A", 3)
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertFalse(RecordingStore.allFiles.single().uploaded)
    }

    @Test
    fun noServerConfiguredReturnsFailure() {
        addSyncedRecording("SN-A", 4)
        RecordingStore.serverBaseUrl = null

        assertEquals(ListenableWorker.Result.failure(), runWorker())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun deleteAfterUploadWithNoDeviceDefersInsteadOfDeleting() {
        RecordingStore.deleteAfterUpload = true
        addSyncedRecording("SN-A", 5)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"srv-5","duplicate":false}"""))

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val rec = RecordingStore.allFiles.single()
        assertTrue(rec.uploaded)
        assertTrue("delete must be deferred until SN-A connects", rec.deletePendingOnDevice)
    }

    @Test
    fun throwingDeviceLinkIsToleratedAndUploadStillSucceeds() {
        RecordingStore.deleteAfterUpload = true
        UploadManager.deviceLink = ThrowingLink()
        addSyncedRecording("SN-A", 6)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"srv-6","duplicate":false}"""))

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val rec = RecordingStore.allFiles.single()
        assertTrue(rec.uploaded)
        assertTrue(rec.deletePendingOnDevice)
    }

    @Test
    fun workerNeverEnqueuesMoreWorkItself() {
        addSyncedRecording("SN-A", 7)
        server.enqueue(MockResponse().setResponseCode(500))
        runWorker()
        // Retrying is WorkManager's job (Result.retry()); re-enqueueing from inside the worker
        // would be dropped anyway under KEEP while this request is RUNNING.
        assertEquals(0, scheduleCalls.get())
    }
}
