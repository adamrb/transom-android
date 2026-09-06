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

/**
 * UploadManager queue + delete-after-upload safety, with the BLE SDK faked out behind
 * UploadManager.DeviceLink:
 *  - dirty-flag loop: a kick() arriving mid-run is never lost (finding 8)
 *  - blank device SN: uploaded, but NEVER device-deleted (finding 2)
 *  - SN mismatch: no delete on the wrong device; deferred via deletePendingOnDevice and
 *    retried once the matching device is connected (findings 2 + 7)
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

        // Device reports failure -> flag stays set so a later kick retries.
        UploadManager.handleDeviceDeleteResult(40L, 1)
        assertEquals(1, RecordingStore.pendingDeviceDeletes("SN-A").size)

        // Success clears it.
        UploadManager.handleDeviceDeleteResult(40L, 0)
        assertTrue(RecordingStore.pendingDeviceDeletes("SN-A").isEmpty())
    }
}
