package io.github.adamrb.transom.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.adamrb.transom.common.AppLog
import java.util.concurrent.TimeUnit

/**
 * Enqueues the durable title/transcript fetch ([TitleSyncWorker]) with WorkManager.
 *
 * Why WorkManager: the server needs 20 to 60 seconds after an upload before the transcript and
 * title exist. The in-app path (TitleSyncManager.kick) polls for a while, but only as long as the
 * process lives. If Android kills the app in that window, nothing would fetch the title until the
 * user next opens a list screen. A persisted, constraint-gated request closes that gap.
 *
 * Policy mirrors [UploadScheduler]:
 *  - Unique name + [ExistingWorkPolicy.KEEP]: every upload's enqueue collapses into the request
 *    already queued/running. A pass covers the WHOLE awaiting list, so a second request is
 *    redundant.
 *  - [NetworkType.CONNECTED]: pointless without a network.
 *  - Exponential backoff from 30s: the first retry lands near the end of the server's typical
 *    transcription window; later retries thin out so a slow or down server is not hammered.
 */
object TitleSyncScheduler {

    private const val TAG = "TitleSyncScheduler"

    const val UNIQUE_WORK_NAME = "fetch-titles"
    const val INITIAL_BACKOFF_SECONDS = 30L

    /** The request as enqueued (exposed so tests can assert the constraints/backoff). */
    fun buildRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<TitleSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()

    /**
     * Enqueue (or keep the existing) title work. Never throws: a scheduling failure must not take
     * down the caller (kick() runs from upload completions and lifecycle hooks); the in-app path
     * still fetches on its own, only the process-death safety net is lost.
     */
    fun enqueue(context: Context) {
        try {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, buildRequest())
        } catch (t: Throwable) {
            AppLog.w(TAG, "Could not enqueue title work", t)
        }
    }
}
