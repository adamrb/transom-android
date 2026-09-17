package cloud.adamrb.transom.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cloud.adamrb.transom.TransomApp
import cloud.adamrb.transom.common.AppLog
import cloud.adamrb.transom.net.ApiClient
import cloud.adamrb.transom.models.RecordingFile
import cloud.adamrb.transom.storage.RecordingStore
import cloud.adamrb.transom.ui.recordings.RecordingsRepository
import cloud.adamrb.transom.work.TitleSyncScheduler
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
 * Result classes: 409 Pending and transient errors are retried; 401/403 AuthError (wrong token)
 * is NOT retried by this manager because repeating the same request cannot change the answer,
 * and a fixed token comes with the next kick. 404 NotFound (the server does not know this id:
 * stale/foreign id, server database reset) gets exactly one repair attempt: the id is re-resolved
 * through the lookup endpoint by device_sn + session_id. A new id is stored and fetched on the
 * next pass; no id means the stale one is cleared so the file leaves the work list instead of
 * producing the same 404 on every resume. The detail screen's lookup (or a later upload) puts an
 * id back if the server ever has the recording again.
 */
object TitleSyncManager {

    private const val TAG = "TitleSyncManager"

    /** Seam over ApiClient.fetchTranscript so passes are unit-testable without a live server. */
    fun interface TranscriptSource {
        fun fetchTranscript(recordingId: String): ApiClient.TranscriptResult
    }

    /** Replaceable for unit tests only. */
    internal var transcriptSource: TranscriptSource = TranscriptSource { ApiClient.fetchTranscript(it) }

    /** Seam over ApiClient.lookupRecordingId, used once per 404 to repair or retire a stale id. */
    fun interface IdLookup {
        fun lookup(deviceSN: String, sessionId: Long): ApiClient.LookupResult
    }

    /** Replaceable for unit tests only. */
    internal var idLookup: IdLookup = IdLookup { sn, sid -> ApiClient.lookupRecordingId(sn, sid) }

    /** Propagates new titles to lists observing SyncManager.files (test seam). */
    internal var onFilesChanged: () -> Unit = { SyncManager.shared.refreshFilesFromStore() }

    /**
     * A transcript the user was waiting for has just been stored by a pass (not a refetch of a
     * pre-title document, and not the detail screen's own fetch, where the user is looking at
     * it). The default tells the user with a notification and starts following the recording's
     * automations so their outcomes are announced too. Test seam.
     */
    internal var onTranscriptStored: (file: RecordingFile, rawJson: String) -> Unit = { file, rawJson ->
        val serverId = file.serverId
        if (serverId != null) {
            val context = try { TransomApp.instance } catch (e: UninitializedPropertyAccessException) { null }
            // The detail screen and this manager poll independently; when the user is looking
            // at the very recording whose transcript just landed, the screen shows it and a
            // notification would only nag.
            val onScreen = try {
                cloud.adamrb.transom.ui.filedetail.FileDetailActivity.isShowing(serverId)
            } catch (t: Throwable) {
                false
            }
            if (context != null && !onScreen) {
                cloud.adamrb.transom.common.AppNotifications.transcriptReady(context, file.id, serverId, file.displayName, rawJson)
            }
            // Nothing to route when the server heard nothing: no router run is coming.
            if (!cloud.adamrb.transom.models.ServerRecording.transcriptSaysNoSpeech(rawJson)) {
                AutomationWatcher.watch(serverId, file.id, file.displayName)
            }
        }
    }

    /**
     * Runs after a stale server id was replaced (test seam). The default starts the marks sync:
     * RecordingStore.replaceServerId resets marksSynced so the rebuilt record gets its marks, and
     * with no upload or device connect necessarily coming, nothing else would issue that PATCH.
     */
    internal var onServerIdRepaired: () -> Unit = { MarksSyncManager.kick() }

    /**
     * Enqueues the durable WorkManager retry (test seam). Same shape as UploadManager.scheduler:
     * needs the Application context and quietly does nothing when it has not been created.
     */
    internal var scheduler: () -> Unit = {
        val context = try {
            TransomApp.instance
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

    /**
     * A pre-title record whose stale id was just repaired gets its one refetch back: the attempt
     * that was spent went to an id the server did not know, so it proved nothing about the title.
     * Without this the record would sit in [legacyAttempted] with a valid id nobody ever asks for.
     */
    private fun unmarkLegacyAttempted(id: String) = synchronized(legacyAttempted) { legacyAttempted.remove(id) }

    /**
     * (file id, server id) pairs where the server contradicted itself in this process: the lookup
     * confirms the id, the transcript endpoint says 404. Neither clearing the id nor asking again
     * can help, and the file stays in the work list (no transcript to cache), so without this the
     * pair of requests would repeat on every resume. Bounded to the process like [legacyAttempted];
     * a new id from the detail screen or a later upload is a different pair and is tried again.
     */
    private val settledMisses = HashSet<Pair<String, String>>()

    private fun isSettledMiss(rec: RecordingFile): Boolean = synchronized(settledMisses) {
        val serverId = rec.serverId ?: return false
        (rec.id to serverId) in settledMisses
    }

    private fun markSettledMiss(fileId: String, serverId: String) = synchronized(settledMisses) {
        settledMisses.add(fileId to serverId)
    }

    /**
     * The user asked the server to transcribe [serverId] again: whatever it answered before no
     * longer describes it, so a settled miss for that id is forgotten and the next pass asks.
     */
    fun reopen(serverId: String) = synchronized(settledMisses) {
        settledMisses.removeAll { it.second == serverId }
    }

    /** Test hook: forget which pre-title transcripts were already refetched, and settled misses. */
    internal fun resetLegacyAttemptsForTest() {
        synchronized(legacyAttempted) { legacyAttempted.clear() }
        synchronized(settledMisses) { settledMisses.clear() }
    }

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
        val work = (RecordingStore.awaitingTranscript + legacy).filterNot { isSettledMiss(it) }
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
                        if (rec.id !in legacyIds) notifyTranscriptStored(rec.id, result.rawJson)
                    }
                }
                ApiClient.TranscriptResult.Pending -> if (rec.id in legacyIds) skipped++ else pending++
                ApiClient.TranscriptResult.NotFound -> when (repairStaleServerId(rec, serverId, configGen)) {
                    Repair.REPAIRED -> pending++
                    Repair.TRANSIENT -> if (rec.id in legacyIds) skipped++ else failed++
                    Repair.SETTLED -> skipped++
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

    /** Outcome of [repairStaleServerId]. */
    private enum class Repair {
        /** A different id was stored; the next pass fetches under it (pending). */
        REPAIRED,
        /** The lookup itself failed transiently; nothing changed, worth another pass (failed). */
        TRANSIENT,
        /** Nothing more a retry could do: id cleared, or left alone on purpose (skipped). */
        SETTLED
    }

    /**
     * The transcript endpoint answered 404 for [staleServerId]. Ask the server which id, if any,
     * it holds for the recorder's own identity of this recording. A different id is stored (and
     * published to the lists, which would otherwise keep opening the old record) and fetched on
     * the next pass. A lookup 404 means the server has no such recording, so the stale id is
     * cleared (see RecordingStore.clearStaleServerId) and the file leaves the work list. A
     * network or 5xx failure of the lookup proves nothing and is reported as transient so the
     * worker keeps its durable retry. An auth error, a server that contradicts itself, or a
     * blank SN that cannot be looked up at all leave the record as it was.
     */
    private fun repairStaleServerId(rec: RecordingFile, staleServerId: String, configGen: Long): Repair {
        if (rec.deviceSN.isBlank()) {
            AppLog.w(TAG, "Server has no recording $staleServerId and the file has no device SN to look up; leaving it")
            return Repair.SETTLED
        }
        val lookup = try {
            idLookup.lookup(rec.deviceSN, rec.sessionId)
        } catch (t: Throwable) {
            ApiClient.LookupResult.Error(t.message ?: "network error")
        }
        if (RecordingStore.serverConfigGeneration != configGen) {
            AppLog.w(TAG, "Lookup discarded, server config changed mid-request (serverId=$staleServerId)")
            return Repair.TRANSIENT
        }
        return when (lookup) {
            is ApiClient.LookupResult.Found -> {
                if (lookup.id == staleServerId) {
                    // The server contradicts itself (lookup knows the id, transcript does not).
                    // Nothing to repair; do not clear an id the server just confirmed, and do
                    // not ask again this process (see settledMisses).
                    markSettledMiss(rec.id, staleServerId)
                    AppLog.w(TAG, "Server has no transcript for $staleServerId but still lists it; not retrying")
                    Repair.SETTLED
                } else {
                    RecordingStore.replaceServerId(rec.id, lookup.id)
                    unmarkLegacyAttempted(rec.id)
                    forgetStaleServerRow(staleServerId)
                    notifyFilesChanged()
                    notifyServerIdRepaired()
                    AppLog.i(TAG, "Stale serverId $staleServerId replaced by ${lookup.id} via lookup")
                    Repair.REPAIRED
                }
            }
            ApiClient.LookupResult.NotFound -> {
                RecordingStore.clearStaleServerId(rec.id, staleServerId)
                forgetStaleServerRow(staleServerId)
                notifyFilesChanged()
                AppLog.w(TAG, "Server has no recording $staleServerId (lookup 404 too); cleared the stale id")
                Repair.SETTLED
            }
            is ApiClient.LookupResult.AuthError -> {
                AppLog.w(TAG, "Lookup for stale serverId $staleServerId rejected (HTTP ${lookup.code}); leaving it")
                Repair.SETTLED
            }
            is ApiClient.LookupResult.Error -> {
                AppLog.w(TAG, "Lookup for stale serverId $staleServerId failed: ${lookup.message}; will retry")
                Repair.TRANSIENT
            }
        }
    }

    /**
     * The server just said it does not know [staleServerId], so a list row still carrying that id
     * (fetched before the server lost it) is stale too. RecordingsMerger would keep pairing it
     * with the phone entry and RecordingItem.serverId prefers the row's id, so a tap would open
     * the dead id until the next list refresh; drop the row now. Best-effort, like the UI refresh.
     */
    private fun forgetStaleServerRow(staleServerId: String) {
        try {
            RecordingsRepository.remove(staleServerId)
        } catch (t: Throwable) {
            AppLog.w(TAG, "could not drop stale server row $staleServerId", t)
        }
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
     * Best-effort like [notifyFilesChanged]: the transcript is already persisted, and a failure
     * to notify must not count as a fetch failure. Re-reads the record so the callback sees the
     * title that [storeTranscript] just captured.
     */
    private fun notifyTranscriptStored(fileId: String, rawJson: String) {
        try {
            val file = RecordingStore.allFiles.firstOrNull { it.id == fileId } ?: return
            onTranscriptStored(file, rawJson)
        } catch (t: Throwable) {
            AppLog.w(TAG, "transcript-stored notification failed", t)
        }
    }

    /** Best-effort like [notifyFilesChanged]: the new id is already persisted. */
    private fun notifyServerIdRepaired() {
        try {
            onServerIdRepaired()
        } catch (t: Throwable) {
            AppLog.w(TAG, "marks sync kick after id repair failed", t)
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
