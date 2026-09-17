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
 * Enqueues the durable automation-result poll ([AutomationWatchWorker]) with WorkManager.
 *
 * Same reasoning and policy as [TitleSyncScheduler]: an agent can take minutes to file a note,
 * the in-app poll only lives as long as the process, and a persisted request closes the gap so
 * the "Work meetings done" notification arrives even if Android killed the app meanwhile.
 * Unique name + KEEP because one pass covers every watched recording.
 */
object AutomationWatchScheduler {

    private const val TAG = "AutomationWatchScheduler"

    const val UNIQUE_WORK_NAME = "watch-automations"
    const val INITIAL_BACKOFF_SECONDS = 30L

    /** The request as enqueued (exposed so tests can assert the constraints/backoff). */
    fun buildRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<AutomationWatchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()

    /** Enqueue (or keep the existing) watch work. Never throws, see [TitleSyncScheduler.enqueue]. */
    fun enqueue(context: Context) {
        try {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, buildRequest())
        } catch (t: Throwable) {
            AppLog.w(TAG, "Could not enqueue automation watch work", t)
        }
    }
}
