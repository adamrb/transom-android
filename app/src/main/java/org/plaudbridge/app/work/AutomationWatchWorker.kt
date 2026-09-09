package org.plaudbridge.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.managers.AutomationWatcher
import org.plaudbridge.app.storage.RecordingStore

/**
 * WorkManager entry point for automation results: runs ONE AutomationWatcher pass and maps its
 * outcome onto WorkManager's retry semantics, exactly like [TitleSyncWorker] does for titles.
 *
 *  - success(): nothing left to watch (every hand-off reported, or the watches expired).
 *  - retry():   at least one recording still has an agent working, or a fetch failed
 *               transiently; WorkManager re-runs with exponential backoff.
 *  - failure(): no server configured.
 */
class AutomationWatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!RecordingStore.isServerConfigured) {
            AppLog.w(TAG, "No server configured; dropping automation watch work")
            return Result.failure()
        }
        var pass = AutomationWatcher.runPass()
        // Same as TitleSyncWorker: wait out an in-app pass rather than returning, because under
        // ExistingWorkPolicy.KEEP nothing else would retry what that pass leaves pending.
        while (pass.alreadyRunning) {
            delay(ALREADY_RUNNING_POLL_MS)
            pass = AutomationWatcher.runPass()
        }
        AppLog.i(TAG, "Automation pass: announced=${pass.announced} watching=${pass.watching} failed=${pass.failed}")
        return if (pass.remaining == 0) Result.success() else Result.retry()
    }

    private companion object {
        const val TAG = "AutomationWatchWorker"
        const val ALREADY_RUNNING_POLL_MS = 500L
    }
}
