package org.plaudbridge.app.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.work.TitleSyncScheduler
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pulls transcripts (and with them the server's AI-generated titles) for uploaded recordings, so
 * the Files and Home lists stop saying "Untitled Recording" without the user opening each file.
 *
 * The recorder has no file names, so every synced recording is stored as "Untitled Recording".
 * The bridge server transcribes and summarizes each upload (roughly 20 to 60 seconds) and puts a
 * "title" in the transcript JSON. A pass walks every record that has a serverId but no cached
 * transcript, GETs /recordings/{id}/transcript and stores a Ready body via
 * RecordingStore.updateTranscript, which captures the title. The work list empties as transcripts
 * land, so a pass over an already-titled library costs one store read and zero network calls.
 *
 * Triggers: UploadManager after a pass that uploaded something (the title is at most a minute
 * away), the Files/Home screens on resume (cheap, see above), and a durable WorkManager request
 * ([TitleSyncScheduler]/TitleSyncWorker) that survives process death. The in-app kick also polls
 * a bounded number of times while the server still answers 409, so a freshly uploaded recording
 * usually gets its title while the user is still looking at the list; WorkManager's backoff is
 * the fallback, not the primary path.
 *
 * Result classes: 409 Pending and transient errors are retried; 404 NotFound (the server does not
 * know this id: stale/foreign id, server database reset) and 401/403 AuthError (wrong token) are
 * NOT retried by this manager because repeating the same request cannot change the answer. They
 * are left for the user-facing paths: the detail screen re-resolves ids via the lookup endpoint,
 * and a fixed token comes with the next kick.
 */
object TitleSyncManager {

    private const val TAG = "TitleSyncManager"

    /** Seam over ApiClient.fetchTranscript so passes are unit-testable without a live server. */
    fun interface TranscriptSource {
        fun fetchTranscript(recordingId: String): ApiClient.TranscriptResult
    }

    /** Replaceable for unit tests only. */
    internal var transcriptSource: TranscriptSource = TranscriptSource { ApiClient.fetchTranscript(it) }

    /** Propagates new titles to lists observing SyncManager.files (test seam). */
    internal var onFilesChanged: () -> Unit = { SyncManager.shared.refreshFilesFromStore() }

    /**
     * Enqueues the durable WorkManager retry (test seam). Same shape as UploadManager.scheduler:
     * needs the Application context and quietly does nothing when it has not been created.
     */
    internal var scheduler: () -> Unit = {
        val context = try {
            PlaudBridgeApp.instance
        } catch (e: UninitializedPropertyAccessException) {
            null
        }
        if (context != null) TitleSyncScheduler.enqueue(context)
    }

    /**
     * In-app poll interval while the server still reports 409. 20s sits inside the server's
     * typical 20 to 60 second transcription window; with [MAX_IN_APP_POLLS] attempts the in-app
     * path covers about two minutes before handing over to WorkManager.
     */
    internal var inAppPollDelayMs = 20_000L
    const val MAX_IN_APP_POLLS = 6

    /** Outcome of one [runPass]. */
    data class PassResult(
        /** Transcripts (and titles) stored during this pass. */
        val stored: Int,
        /** 409: the server is still transcribing/summarizing; a later pass will succeed. */
        val pending: Int,
        /** Transient failures (network, 5xx, unparseable 200 body); retried later. */
        val failed: Int,
        /** 404/401/403: retrying cannot help, left alone (see the class KDoc). */
        val skipped: Int,
        /** Another pass held the guard; nothing was attempted and the counts are informational. */
        val alreadyRunning: Boolean = false
    ) {
        /** Files a later pass could still resolve. Drives the worker's retry decision. */
        val remaining: Int get() = pending + failed
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Single-pass guard shared by the in-app path (kick) and the WorkManager path (runPass from
     * TitleSyncWorker): both read the same work list, so two concurrent passes would fetch the
     * same transcripts twice.
     */
    private val running = AtomicBoolean(false)

    /** Work arrived while a run was in flight; loop again instead of dropping the wakeup. */
    private val dirty = AtomicBoolean(false)

    /**
     * Pre-title transcripts already refetched in this process. One attempt is enough: if the
     * server still has no title for them, retrying every resume would be pointless traffic.
     */
    private val legacyAttempted = HashSet<String>()

    private fun legacyCandidates(): List<RecordingFile> = synchronized(legacyAttempted) {
        RecordingStore.cachedWithoutTitle.filter { it.id !in legacyAttempted }
    }

    private fun markLegacyAttempted(files: List<RecordingFile>) = synchronized(legacyAttempted) {
        files.forEach { legacyAttempted.add(it.id) }
    }

    /** Test hook: forget which pre-title transcripts were already refetched. */
    internal fun resetLegacyAttemptsForTest() = synchronized(legacyAttempted) { legacyAttempted.clear() }

    /**
     * Fetch outstanding titles now AND make sure the durable WorkManager retry is scheduled. Safe
     * to call often (screen resumes, every upload): with nothing awaiting a transcript it does not
     * touch the network or WorkManager at all.
     */
    fun kick() {
        if (!RecordingStore.isServerConfigured) return
        scope.launch {
            if (RecordingStore.awaitingTranscript.isEmpty() && legacyCandidates().isEmpty()) return@launch
            scheduler()
            var polls = 0
            while (true) {
                val pass = runPass()
                // Stop once nothing is pending. A pass that found the guard held still polls again:
                // the other pass may leave 409s behind and has no poll loop of its own (worker).
                if (!pass.alreadyRunning && pass.pending == 0) break
                if (polls++ >= MAX_IN_APP_POLLS) break
                delay(inAppPollDelayMs)
            }
        }
    }

    /**
     * One pass over every recording awaiting a transcript, callable from any coroutine (the
     * in-app scope or a CoroutineWorker). Returns [PassResult.alreadyRunning] without touching
     * anything if another pass holds the guard; that pass sees the dirty flag and loops once more,
     * so a kick arriving mid-run is handled by the other pass, never lost.
     */
    suspend fun runPass(): PassResult {
        dirty.set(true)
        if (!running.compareAndSet(false, true)) {
            return PassResult(
                stored = 0, pending = RecordingStore.awaitingTranscript.size, failed = 0, skipped = 0,
                alreadyRunning = true
            )
        }
        var stored = 0
        var pending = 0
        var failed = 0
        var skipped = 0
        try {
            while (dirty.getAndSet(false)) {
                // Each loop starts from a fresh work list; counts from the earlier loop are dropped
                // because they described files this loop re-checked.
                val r = processAwaiting()
                stored += r.stored
                pending = r.pending
                failed = r.failed
                skipped = r.skipped
            }
        } finally {
            running.set(false)
        }
        return PassResult(stored = stored, pending = pending, failed = failed, skipped = skipped)
    }

    /**
     * Fetch once for every recording awaiting a transcript, plus (once per process) recordings
     * whose cached transcript predates server titles. For the latter a non-Ready answer is
     * simply skipped: they already have a transcript, so nothing is "pending" for the worker.
     */
    private fun processAwaiting(): PassResult {
        val legacy = legacyCandidates()
        markLegacyAttempted(legacy)
        val legacyIds = legacy.map { it.id }.toHashSet()
        val work = RecordingStore.awaitingTranscript + legacy
        if (work.isEmpty()) return PassResult(0, 0, 0, 0)
        AppLog.i(TAG, "Checking ${work.size} recording(s) for a transcript/title")
        var stored = 0
        var pending = 0
        var failed = 0
        var skipped = 0
        for (rec in work) {
            val serverId = rec.serverId ?: continue
            // Same guard as uploads: a document from the OLD server must not be attached to a
            // record after the user switched servers (clearServerState wiped its serverId).
            val configGen = RecordingStore.serverConfigGeneration
            val result = try {
                transcriptSource.fetchTranscript(serverId)
            } catch (t: Throwable) {
                ApiClient.TranscriptResult.Error(t.message ?: "network error")
            }
            if (RecordingStore.serverConfigGeneration != configGen) {
                failed++
                AppLog.w(TAG, "Transcript discarded, server config changed mid-fetch (serverId=$serverId)")
                continue
            }
            when (result) {
                is ApiClient.TranscriptResult.Ready -> {
                    // A 200 from a captive portal or misrouted proxy is HTML, not a transcript.
                    // Storing it would end the retries for this file with garbage cached.
                    if (!looksLikeTranscript(result.rawJson)) {
                        if (rec.id in legacyIds) skipped++ else failed++
                        AppLog.w(TAG, "Transcript body is not JSON, will retry (serverId=$serverId)")
                    } else {
                        storeTranscript(rec.id, result.rawJson)
                        stored++
                        AppLog.i(TAG, "Stored transcript/title for serverId=$serverId")
                    }
                }
                ApiClient.TranscriptResult.Pending -> if (rec.id in legacyIds) skipped++ else pending++
                ApiClient.TranscriptResult.NotFound -> {
                    skipped++
                    AppLog.w(TAG, "Server has no recording $serverId; not retrying")
                }
                is ApiClient.TranscriptResult.AuthError -> {
                    // Every further request in this pass carries the same rejected token.
                    skipped++
                    AppLog.w(TAG, "Transcript fetch rejected (HTTP ${result.code}); aborting pass")
                    break
                }
                is ApiClient.TranscriptResult.Error -> {
                    if (rec.id in legacyIds) skipped++ else failed++
                    AppLog.w(TAG, "Transcript fetch failed for serverId=$serverId: ${result.message}")
                }
            }
        }
        return PassResult(stored = stored, pending = pending, failed = failed, skipped = skipped)
    }

    /**
     * Persist a transcript document (the title inside it is captured by
     * RecordingStore.updateTranscript) and refresh the observed file list so the Files/Home rows
     * re-render with the new name. Shared with the detail screen so both paths behave the same.
     */
    fun storeTranscript(fileId: String, rawJson: String) {
        RecordingStore.updateTranscript(fileId, rawJson)
        notifyFilesChanged()
    }

    /** A transcript is a JSON object ({"text", "segments", "title", ...}) or a bare segment array. */
    internal fun looksLikeTranscript(raw: String): Boolean {
        val t = raw.trimStart()
        return try {
            when {
                t.startsWith("{") -> { org.json.JSONObject(raw); true }
                t.startsWith("[") -> { org.json.JSONArray(raw); true }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * UI refresh is best-effort: the transcript is already persisted, so a failure here (e.g.
     * SyncManager touching the BLE SDK in a WorkManager-started process where it was never
     * initialized) must not count as a fetch failure.
     */
    private fun notifyFilesChanged() {
        try {
            onFilesChanged()
        } catch (t: Throwable) {
            AppLog.w(TAG, "files-changed notification failed", t)
        }
    }
}
