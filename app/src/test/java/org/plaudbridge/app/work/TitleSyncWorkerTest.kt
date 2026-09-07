package org.plaudbridge.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.managers.TitleSyncManager
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * TitleSyncWorker result classification, driven through TestListenableWorkerBuilder so the worker
 * runs exactly as WorkManager would invoke it. ApiClient is faked behind TranscriptSource; the
 * scheduler seam is a counter so no WorkManager instance is touched.
 */
@RunWith(RobolectricTestRunner::class)
class TitleSyncWorkerTest {

    private lateinit var context: Context
    private val fetches = AtomicInteger()
    private val scheduleCalls = AtomicInteger()
    private var response: (String) -> ApiClient.TranscriptResult = { ApiClient.TranscriptResult.Pending }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        File(context.filesDir, "recordings.json").delete()
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "test-token"

        fetches.set(0)
        TitleSyncManager.transcriptSource = TitleSyncManager.TranscriptSource { id ->
            fetches.incrementAndGet()
            response(id)
        }
        TitleSyncManager.onFilesChanged = {}
        scheduleCalls.set(0)
        TitleSyncManager.scheduler = { scheduleCalls.incrementAndGet() }
    }

    private fun addUploaded(session: Long, serverId: String) {
        RecordingStore.addFiles(
            listOf(RecordingFile(sessionId = session, deviceSN = "SN-A", name = "Untitled Recording", duration = 5, createdAt = session * 1000))
        )
        RecordingStore.markAsUploaded("SN-A", session, serverId)
    }

    private fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<TitleSyncWorker>(context).build()
        return runBlocking { worker.doWork() }
    }

    @Test
    fun allTitlesStoredReturnsSuccess() {
        addUploaded(1, "srv-1")
        response = { ApiClient.TranscriptResult.Ready("""{"text":"t","segments":[],"title":"Weekly sync"}""") }

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals("Weekly sync", RecordingStore.allFiles.single().displayName)
    }

    @Test
    fun nothingAwaitingReturnsSuccessWithoutFetching() {
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(0, fetches.get())
    }

    @Test
    fun pendingTranscriptReturnsRetry() {
        addUploaded(1, "srv-1")
        response = { ApiClient.TranscriptResult.Pending }

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertNull(RecordingStore.allFiles.single().transcriptJSON)
    }

    @Test
    fun transientErrorReturnsRetry() {
        addUploaded(1, "srv-1")
        response = { ApiClient.TranscriptResult.Error("HTTP 502") }

        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    @Test
    fun notFoundReturnsSuccessSoTheWorkerDoesNotRetryForever() {
        addUploaded(1, "srv-gone")
        response = { ApiClient.TranscriptResult.NotFound }

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, RecordingStore.awaitingTranscript.size) // left for the detail screen's lookup
    }

    @Test
    fun authErrorReturnsSuccessSoTheWorkerDoesNotRetryForever() {
        addUploaded(1, "srv-1")
        response = { ApiClient.TranscriptResult.AuthError(403) }

        assertEquals(ListenableWorker.Result.success(), runWorker())
    }

    @Test
    fun noServerConfiguredReturnsFailure() {
        addUploaded(1, "srv-1")
        RecordingStore.serverBaseUrl = null

        assertEquals(ListenableWorker.Result.failure(), runWorker())
        assertEquals(0, fetches.get())
    }

    @Test
    fun workerNeverEnqueuesMoreWorkItself() {
        addUploaded(1, "srv-1")
        response = { ApiClient.TranscriptResult.Pending }
        runWorker()
        // Retrying is WorkManager's job (Result.retry()); an enqueue from inside the worker
        // would be dropped anyway under KEEP while this request is RUNNING.
        assertEquals(0, scheduleCalls.get())
    }
}
