package io.github.adamrb.transom.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import io.github.adamrb.transom.common.AppLog
import io.github.adamrb.transom.managers.TitleSyncManager
import io.github.adamrb.transom.storage.RecordingStore

/**
 * WorkManager entry point for title/transcript fetching: runs ONE TitleSyncManager pass and maps
 * its outcome onto WorkManager's retry semantics.
 *
 *  - success(): nothing a retry could still resolve (every awaiting recording got its transcript,
 *    or only 404/401/403 cases are left, which repeating the request cannot fix). The next upload
 *    enqueues a fresh request.
 *  - retry():   the server still answers 409 for at least one recording (transcription and
 *    summary in progress), or a fetch failed transiently (server unreachable, 5xx, non-JSON 200).
 *    WorkManager re-runs with exponential backoff (see [TitleSyncScheduler]).
 *  - failure(): permanent misconfiguration only, i.e. no server URL/token.
 *
 * RecordingStore is initialized by TransomApp.onCreate, which Android runs before any worker
 * in the process; no separate init is needed here.
 */
class TitleSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!RecordingStore.isServerConfigured) {
            AppLog.w(TAG, "No server configured; dropping title work")
            return Result.failure()
        }

        var pass = TitleSyncManager.runPass()
        // An in-app pass holds the guard. Do not run alongside it, but do not return right away
        // either: under ExistingWorkPolicy.KEEP an enqueue made while we are RUNNING is dropped,
        // so whatever that pass leaves pending would have no scheduled retry. Wait, then run our
        // own pass over what remains. Cancellation (isStopped) surfaces through delay().
        while (pass.alreadyRunning) {
            delay(ALREADY_RUNNING_POLL_MS)
            pass = TitleSyncManager.runPass()
        }

        AppLog.i(TAG, "Title pass: stored=${pass.stored} pending=${pass.pending} failed=${pass.failed} skipped=${pass.skipped}")
        return if (pass.remaining == 0) Result.success() else Result.retry()
    }

    private companion object {
        const val TAG = "TitleSyncWorker"
        const val ALREADY_RUNNING_POLL_MS = 500L
    }
}
