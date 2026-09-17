package cloud.adamrb.transom.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cloud.adamrb.transom.common.AppLog
import java.util.concurrent.TimeUnit

/**
 * Enqueues the durable upload retry ([UploadWorker]) with WorkManager.
 *
 * Why WorkManager at all: UploadManager.kick() only runs while the process is alive and something
 * in the app calls it (device connect, sync completion, foreground, manual Sync now). If an upload
 * fails (server down, phone parked on the recorder's WiFi hotspot with no internet, network
 * flake) and Android then kills the process, nothing retries until the user reopens the app.
 * A persisted, constraint-gated work request survives process death and reboots.
 *
 * Policy:
 *  - Unique name + [ExistingWorkPolicy.KEEP]: any number of enqueues (every kick, every newly
 *    synced recording) collapse into the one already queued/running request. A pass processes
 *    the WHOLE pending queue, so a second request would only be redundant.
 *  - [NetworkType.CONNECTED]: do not even start with no network. The recorder's fast-transfer
 *    hotspot counts as CONNECTED without reaching the internet; that case is covered by the
 *    worker returning retry() and the exponential backoff below, not by the constraint.
 *  - Exponential backoff from 30s (WorkManager caps it at its default 5h maximum). Short
 *    enough that a brief hotspot session or server restart recovers quickly, long enough not
 *    to hammer a server that is genuinely down.
 */
object UploadScheduler {

    private const val TAG = "UploadScheduler"

    const val UNIQUE_WORK_NAME = "upload-recordings"
    const val INITIAL_BACKOFF_SECONDS = 30L

    /** The request as enqueued (exposed so tests can assert the constraints/backoff). */
    fun buildRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()

    /**
     * Enqueue (or keep the existing) upload work. Never throws: a scheduling failure must not
     * take down the caller (kick() runs from BLE callbacks and lifecycle hooks), and the in-app
     * fast path still uploads on its own; only the process-death safety net is lost.
     */
    fun enqueue(context: Context) {
        try {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, buildRequest())
        } catch (t: Throwable) {
            // IllegalStateException when WorkManager is not initialized (e.g. a stripped test
            // environment); anything else is equally non-fatal for the in-app upload path.
            AppLog.w(TAG, "Could not enqueue upload work", t)
        }
    }
}
