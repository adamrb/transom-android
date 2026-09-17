package cloud.adamrb.transom.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import cloud.adamrb.transom.common.AppLog
import cloud.adamrb.transom.managers.UploadManager
import cloud.adamrb.transom.storage.RecordingStore

/**
 * WorkManager entry point for the upload queue: runs ONE UploadManager pass and maps its outcome
 * onto WorkManager's retry semantics.
 *
 *  - success(): nothing is left pending (all uploaded, or the queue was already empty). The next
 *    kick()/newly synced recording enqueues a fresh request.
 *  - retry():   uploads failed for transient reasons (server unreachable or 5xx, timeouts, the
 *    phone still on the recorder's internet-less hotspot). WorkManager re-runs with exponential
 *    backoff (see [UploadScheduler]).
 *  - failure(): permanent misconfiguration only, i.e. no server URL/token. Retrying cannot help;
 *    configuring a server later goes through kick(), which enqueues again.
 *
 * The pass itself is exactly the in-app one (validated response before markAsUploaded, blank SN
 * never device-deleted, device delete only while THAT device is connected, deferred deletes
 * retried). In a WorkManager-started background process no device is connected, so the delete
 * branches simply defer, and UploadManager tolerates the BLE SDK being uninitialized.
 *
 * RecordingStore is initialized by TransomApp.onCreate, which Android runs before any
 * worker in the process; no separate init is needed here.
 */
class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!RecordingStore.isServerConfigured) {
            AppLog.w(TAG, "No server configured; dropping upload work")
            return Result.failure()
        }

        var pass = UploadManager.runPass()
        // An in-app pass (kick) holds the single-pass guard. We must not run alongside it (that
        // would double-upload the same files), but returning right away would be wrong too:
        // with ExistingWorkPolicy.KEEP any enqueue made while we are RUNNING is dropped, so
        // whatever that pass leaves behind would have no scheduled retry. Wait for it to
        // finish, then run our own pass over whatever remains (typically nothing, so the pass
        // is a cheap store read). Cancellation (isStopped) surfaces through delay().
        while (pass.alreadyRunning) {
            delay(ALREADY_RUNNING_POLL_MS)
            pass = UploadManager.runPass()
        }

        AppLog.i(TAG, "Upload pass: uploaded=${pass.uploaded} failed=${pass.failed} remaining=${pass.remaining}")
        return if (pass.remaining == 0) Result.success() else Result.retry()
    }

    private companion object {
        const val TAG = "UploadWorker"
        const val ALREADY_RUNNING_POLL_MS = 500L
    }
}
