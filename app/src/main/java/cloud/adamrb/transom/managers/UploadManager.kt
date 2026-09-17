package cloud.adamrb.transom.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import cloud.adamrb.transom.TransomApp
import cloud.adamrb.transom.common.AppLog
import cloud.adamrb.transom.net.ApiClient
import cloud.adamrb.transom.storage.RecordingStore
import cloud.adamrb.transom.work.UploadScheduler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/** Per-run upload status for the UI. */
sealed class UploadState {
    object Idle : UploadState()
    data class Uploading(val current: Int, val total: Int, val fileName: String?) : UploadState()
    data class Failed(val message: String) : UploadState()
}

/**
 * Uploads locally synced recordings to the self-hosted transom-server.
 *
 * Recordings come off the device via SyncManager (BLE exportAudio / WiFi fast transfer) as MP3s;
 * this manager pushes every not-yet-uploaded file to POST /api/v1/recordings. A recording is
 * marked uploaded ONLY on a strictly validated response (see ApiClient.uploadRecording); anything
 * else counts as a failed upload and is retried on the next kick.
 *
 * Uploads are retried on the next kick (sync completion, device connect, app foreground, manual
 * Sync now) — during a WiFi fast transfer the phone is on the device hotspot with no internet,
 * so uploads intentionally queue up and run after the transfer ends.
 *
 * Durability: kick() is the in-app fast path, but it only exists while the process does. Every
 * kick (and every newly synced recording, see [ensureScheduled]) ALSO enqueues a WorkManager
 * request (cloud.adamrb.transom.work.UploadScheduler) whose worker calls [runPass] and asks for a
 * backed-off retry while anything is left pending. Both paths share one pass implementation and
 * one single-run guard, so a file is never uploaded twice concurrently.
 *
 * Delete-after-upload (default OFF): the device copy is deleted only when
 *  - the server upload was validated,
 *  - the recording has a known device SN (a blank SN is uploaded but NEVER device-deleted), and
 *  - the CURRENTLY CONNECTED device's SN equals the recording's SN.
 * If the matching device is not connected, a persistent deletePendingOnDevice flag is set and
 * retried on the next kick while that device is connected.
 */
object UploadManager {

    private const val TAG = "UploadManager"

    /**
     * Thin seam over the BLE SDK (a static facade) so queue/delete logic is unit-testable.
     * Production uses [SdkDeviceLink]; tests substitute a fake.
     */
    interface DeviceLink {
        /** SN of the currently connected device, or null when nothing is connected. */
        fun connectedDeviceSN(): String?
        /** Ask the connected device to delete [sessionId] (result arrives via bleDeleteFile). */
        fun deleteFile(sessionId: Long)
    }

    private object SdkDeviceLink : DeviceLink {
        override fun connectedDeviceSN(): String? = try {
            if (!sdk.PlaudDeviceAgent.isConnected()) null
            else DeviceManager.shared.connectedDevice.value?.serialNumber?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

        override fun deleteFile(sessionId: Long) = sdk.PlaudDeviceAgent.deleteFile(sessionId)
    }

    /** Replaceable for unit tests only. */
    internal var deviceLink: DeviceLink = SdkDeviceLink

    /** Seam over ApiClient.renameRecording for the post-upload title push (see [pushPinnedName]). */
    fun interface TitlePusher {
        fun rename(serverId: String, title: String): ApiClient.RecordingResult
    }

    /** Replaceable for unit tests only. */
    internal var titlePusher: TitlePusher = TitlePusher { id, title -> ApiClient.renameRecording(id, title) }

    /** Propagates the new upload badge to lists observing SyncManager.files (test seam). */
    internal var onFilesChanged: () -> Unit = { SyncManager.shared.refreshFilesFromStore() }

    /**
     * Runs after a pass that uploaded at least one recording (test seam). The default starts the
     * title fetch: the server produces a transcript plus AI title within about a minute of an
     * upload, and TitleSyncManager polls for it and enqueues its own durable WorkManager retry.
     */
    internal var onUploadsCompleted: () -> Unit = {
        TitleSyncManager.kick()
        // Marks read after the upload left (or a device that answered late) now have a serverId
        // to be PATCHed against.
        MarksSyncManager.kick()
    }

    /**
     * Enqueues the durable WorkManager retry (test seam; tests substitute a counter). The default
     * needs an Application context: UploadManager is an object, so it reaches for
     * TransomApp.instance and quietly does nothing if the Application has not been created
     * (unit tests driving kick() directly, or an exotic process without our Application class).
     */
    internal var scheduler: () -> Unit = {
        val context = try {
            TransomApp.instance
        } catch (e: UninitializedPropertyAccessException) {
            null
        }
        if (context != null) UploadScheduler.enqueue(context)
    }

    /** Outcome of one [runPass]: what happened plus how much is still waiting. */
    data class PassResult(
        /** Recordings marked uploaded during this pass. */
        val uploaded: Int,
        /** Upload attempts that did not produce a validated result (retried later). */
        val failed: Int,
        /** Pending uploads (with a local file present) still left after the pass. */
        val remaining: Int,
        /** Another pass held the guard; nothing was attempted and the counts are informational. */
        val alreadyRunning: Boolean = false
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Single-pass guard shared by the in-app path (kick) and the WorkManager path (runPass from
     * UploadWorker). Both read the same pending list, so two concurrent passes would upload the
     * same file twice; whoever fails the CAS gets [PassResult.alreadyRunning] instead.
     */
    private val running = AtomicBoolean(false)

    /**
     * Delete commands awaiting their bleDeleteFile callback: sessionId -> SN the command was
     * issued to. The callback carries no SN, so this registry is the correlation source — the
     * completion is credited to the CAPTURED target, never to whatever device happens to be
     * connected at callback time. It also dedupes: while a session has an entry, no second
     * delete command is issued for it. Entries are cleared by the callback, on command failure,
     * or at the start of the next kick run (a lost callback then permits a retry; the pending
     * flag stays set throughout, so nothing is ever wrongly marked deleted).
     */
    private val inFlightDeletes = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** Work arrived while a run was in flight — loop again instead of dropping the wakeup. */
    private val dirty = AtomicBoolean(false)

    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state.asStateFlow()

    private val _failedUploads = MutableStateFlow<Set<String>>(emptySet())

    /**
     * [RecordingFile.id]s whose most recent upload attempt in this process failed and that are
     * still waiting to be uploaded. The lists read it to say "Upload failed" on the row instead
     * of "Uploading" forever. An id leaves the set the moment a new attempt starts for it (the
     * row reads "Uploading" again), or when nothing is pending any more; it comes back if that
     * attempt fails too. Process-scoped on purpose: after a restart the durable WorkManager retry
     * runs within moments and re-derives it, so nothing needs persisting.
     */
    val failedUploads: StateFlow<Set<String>> = _failedUploads.asStateFlow()

    /**
     * "Retry upload" on a failed row: forget the failure right away so the row flips back to
     * Uploading without waiting for the pass to reach it, then run a pass. The pass re-adds the
     * id if the upload fails again.
     */
    fun retryUpload(fileId: String) {
        _failedUploads.update { it - fileId }
        kick()
    }

    /**
     * Process the pending-upload queue now (kicks arriving mid-run are queued, never lost) AND
     * make sure the durable WorkManager retry is scheduled, so a failure followed by process
     * death is still retried. Enqueueing is idempotent (unique work, KEEP), so calling this
     * often is cheap.
     */
    fun kick() {
        if (!RecordingStore.isServerConfigured) return
        scheduler()
        launchPass()
    }

    /**
     * Schedule the durable retry WITHOUT uploading right now. For call sites where a recording
     * just became pending but an immediate attempt is pointless, e.g. the WiFi fast-transfer
     * path (phone on the recorder's hotspot, no internet): the transfer end kicks; this covers
     * the process being killed before that.
     */
    fun ensureScheduled() {
        if (!RecordingStore.isServerConfigured) return
        scheduler()
    }

    /** In-app fast path: run a pass on our own IO scope; the guard inside runPass coalesces. */
    private fun launchPass() {
        scope.launch { runPass() }
    }

    /**
     * One upload pass, callable from any coroutine (the in-app scope or a CoroutineWorker):
     * uploads everything pending, then retries deferred device deletes, looping while more work
     * arrived mid-run (dirty flag). Returns [PassResult.alreadyRunning] without touching anything
     * if another pass holds the guard; that pass will see the dirty flag and loop once more, so
     * the caller's work is not lost, only handled by the other pass.
     */
    internal suspend fun runPass(): PassResult {
        dirty.set(true)
        if (!running.compareAndSet(false, true)) {
            return PassResult(uploaded = 0, failed = 0, remaining = pendingWithLocalFile().size, alreadyRunning = true)
        }
        var uploaded = 0
        var failed = 0
        // Stale in-flight entries (callback never arrived, e.g. disconnect) must not block
        // retries forever; a late callback then finds no entry and is safely ignored.
        inFlightDeletes.clear()
        try {
            while (dirty.getAndSet(false)) {
                val (u, f) = processQueue()
                uploaded += u
                failed += f
                processPendingDeletes()
            }
        } finally {
            running.set(false)
            // A kick may have landed between the last getAndSet and releasing the flag.
            if (dirty.get()) launchPass()
        }
        if (uploaded > 0) notifyUploadsCompleted()
        return PassResult(uploaded = uploaded, failed = failed, remaining = pendingWithLocalFile().size)
    }

    /** Pending uploads that can actually be attempted (index entries without a file are skipped). */
    private fun pendingWithLocalFile() = RecordingStore.pendingUploads
        .filter { it.localPath != null && File(it.localPath!!).exists() }

    /** Upload every pending recording once; returns (uploaded, failed). */
    private fun processQueue(): Pair<Int, Int> {
        val pending = pendingWithLocalFile()
        if (pending.isEmpty()) {
            _state.value = UploadState.Idle
            _failedUploads.value = emptySet()
            return 0 to 0
        }
        AppLog.i(TAG, "Uploading ${pending.size} pending recording(s)")
        var uploaded = 0
        var failures = 0
        val failedIds = mutableSetOf<String>()
        pending.forEachIndexed { index, rec ->
            _state.value = UploadState.Uploading(index + 1, pending.size, rec.displayName)
            // A fresh attempt: the row reads "Uploading" while it runs, "Upload failed" again if
            // this attempt fails too.
            _failedUploads.update { it - rec.id }
            try {
                val file = File(rec.localPath!!)
                // Server-config generation guard: if the user switches server URL/token while
                // this request is in flight, the OLD server's acceptance means nothing on the
                // new one — the result must be discarded, not persisted (and certainly must not
                // trigger a device delete).
                val configGen = RecordingStore.serverConfigGeneration
                // NOTE: rec.deviceSN is used as-is. A blank SN is still uploaded (the server can
                // store it), but it must never be "fixed up" with the currently connected SN —
                // that guess is exactly what enables cross-device deletion bugs.
                val result = ApiClient.uploadRecording(
                    file = file,
                    sessionId = rec.sessionId,
                    deviceSn = rec.deviceSN,
                    startedAtIso = isoUtc(rec.createdAt),
                    durationSec = rec.duration.takeIf { it > 0 }?.toDouble(),
                    marks = rec.marks
                )
                if (RecordingStore.serverConfigGeneration != configGen) {
                    failures++
                    failedIds += rec.id
                    _failedUploads.update { it + rec.id }
                    AppLog.w(TAG, "Upload result discarded — server config changed mid-upload (sessionId=${rec.sessionId})")
                } else {
                    // Only a validated result reaches this point (non-blank id, exact contract).
                    RecordingStore.markAsUploaded(rec.deviceSN, rec.sessionId, result.id)
                    // The marks travelled inside the metadata of this validated upload, so no
                    // PATCH is needed for them (a duplicate:true answer means the server already
                    // had the audio, but it still applied the metadata marks).
                    rec.marks?.let { RecordingStore.markMarksSynced(rec.id, it) }
                    uploaded++
                    notifyFilesChanged()
                    AppLog.i(TAG, "Uploaded sessionId=${rec.sessionId} duplicate=${result.duplicate}")
                    // The device delete is decided here, straight after the generation check and
                    // before any further blocking request (the title PATCH below). Should the
                    // configuration have changed in the few statements since that check, the
                    // delete is deferred rather than issued or dropped: the flag is retried on a
                    // same-host token rotation (the upload is still good there) and cleared by
                    // clearServerState on a host change (the new server never saw the upload).
                    if (RecordingStore.deleteAfterUpload) {
                        if (RecordingStore.serverConfigGeneration == configGen) {
                            requestDeviceDelete(rec.deviceSN, rec.sessionId)
                        } else if (rec.deviceSN.isNotBlank()) {
                            RecordingStore.setDeletePendingOnDevice(rec.deviceSN, rec.sessionId, true)
                            AppLog.w(TAG, "delete-after-upload deferred, server config changed right after the upload (sessionId=${rec.sessionId})")
                        }
                    }
                    pushPinnedName(rec.id, result.id, configGen)
                }
            } catch (e: Exception) {
                failures++
                failedIds += rec.id
                _failedUploads.update { it + rec.id }
                AppLog.w(TAG, "Upload failed for sessionId=${rec.sessionId}", e)
            }
        }
        // Exactly what failed in this pass; ids of recordings deleted or uploaded meanwhile drop out.
        _failedUploads.value = failedIds
        _state.value = if (failures == 0) UploadState.Idle
        else UploadState.Failed("$failures upload(s) failed — will retry on the next sync")
        return uploaded to failures
    }

    /** Rounds of [pushPinnedName] re-sends when the user keeps renaming during the push. */
    private const val MAX_TITLE_PUSH_ROUNDS = 3

    /**
     * A recording renamed on the phone BEFORE it was uploaded has no server id at rename time, so
     * RecordingActions.rename could not PATCH it. Push the pinned name right after the upload
     * that produced the id. The record is re-read before each send, and again after a successful
     * one: the list on screen learns the id only after this returns, so a rename typed meanwhile
     * is stored locally without its own PATCH, and would otherwise leave the server holding the
     * older name for every other client. Best-effort: the upload is already persisted, and the
     * phone shows the pinned name regardless (RecordingItem.title), so a failed PATCH is logged
     * and not retried rather than turning a finished upload into a failure. [configGen] is the
     * generation the upload was validated under: the id belongs to THAT server, so once the
     * configuration changes no further round is sent (it would PATCH whatever recording happens
     * to carry the same id on the new server). Known residual, shared with every ApiClient call
     * here: the client reads the URL and token when it builds the request, so a switch landing
     * between this check and that read is not caught; server ids are server-generated UUIDs, so
     * the same id existing on another server is not a realistic outcome of that window.
     */
    private fun pushPinnedName(fileId: String, serverId: String, configGen: Long) {
        var sent: String? = null
        repeat(MAX_TITLE_PUSH_ROUNDS) {
            if (RecordingStore.serverConfigGeneration != configGen) {
                AppLog.w(TAG, "Title push skipped, server config changed since the upload (serverId=$serverId)")
                return
            }
            val current = RecordingStore.allFiles.firstOrNull { it.id == fileId } ?: return
            if (!current.nameEditedByUser) return
            val name = current.name.trim()
            if (name.isEmpty() || name == sent) return
            val ok = try {
                when (val result = titlePusher.rename(serverId, name)) {
                    is ApiClient.RecordingResult.Ok -> { AppLog.i(TAG, "Pushed manual title after upload (serverId=$serverId)"); true }
                    is ApiClient.RecordingResult.NotFound -> { AppLog.w(TAG, "Title push: server has no recording $serverId"); false }
                    is ApiClient.RecordingResult.AuthError -> { AppLog.w(TAG, "Title push rejected (HTTP ${result.code}) for serverId=$serverId"); false }
                    is ApiClient.RecordingResult.Error -> { AppLog.w(TAG, "Title push failed for serverId=$serverId: ${result.message}"); false }
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "Title push failed for serverId=$serverId", t)
                false
            }
            if (!ok) return
            sent = name
        }
    }

    /**
     * UI refresh is best-effort: the recording is already persisted as uploaded, so a failure
     * here (e.g. SyncManager touching the BLE SDK in a WorkManager-started process where it was
     * never initialized) must neither count as an upload failure nor skip the device delete.
     */
    private fun notifyFilesChanged() {
        try {
            onFilesChanged()
        } catch (t: Throwable) {
            AppLog.w(TAG, "files-changed notification failed", t)
        }
    }

    /** Best-effort like [notifyFilesChanged]: the uploads are already persisted. */
    private fun notifyUploadsCompleted() {
        try {
            onUploadsCompleted()
        } catch (t: Throwable) {
            AppLog.w(TAG, "uploads-completed notification failed", t)
        }
    }

    // MARK: - Delete after upload

    /**
     * SN of the currently connected device, or null. Any failure (the SDK facade not initialized
     * in a background process, a static initializer error) reads as "nothing connected", which
     * is the safe direction: deletes are deferred, never issued.
     */
    private fun connectedDeviceSN(): String? = try {
        deviceLink.connectedDeviceSN()
    } catch (t: Throwable) {
        AppLog.w(TAG, "connectedDeviceSN unavailable", t)
        null
    }

    /**
     * Delete the (validated-uploaded) recording from ITS OWN device, or persist a pending flag
     * to retry when that exact device reconnects.
     */
    private fun requestDeviceDelete(deviceSN: String, sessionId: Long) {
        if (deviceSN.isBlank()) {
            // Unknown origin device — deleting by session id alone could hit the wrong device.
            AppLog.w(TAG, "delete-after-upload skipped (blank device SN) sessionId=$sessionId")
            return
        }
        val connected = connectedDeviceSN()
        if (connected != deviceSN) {
            RecordingStore.setDeletePendingOnDevice(deviceSN, sessionId, true)
            AppLog.i(TAG, "delete-after-upload deferred (device not connected) sessionId=$sessionId")
            return
        }
        issueDeviceDelete(deviceSN, sessionId)
    }

    /** Retry deferred device deletions for whichever device is connected right now. */
    private fun processPendingDeletes() {
        if (!RecordingStore.deleteAfterUpload) return
        val connected = connectedDeviceSN() ?: return
        for (rec in RecordingStore.pendingDeviceDeletes(connected)) {
            issueDeviceDelete(rec.deviceSN, rec.sessionId)
        }
    }

    /**
     * Issue the SDK delete. Requires (checked again here) that the connected SN matches the
     * recording's SN, and registers the (sessionId -> SN) pair in [inFlightDeletes] BEFORE
     * sending so the completion callback is correlated against the captured target — and so
     * exactly ONE command per session is outstanding at a time (processQueue and
     * processPendingDeletes can both reach here for the same session in one run).
     */
    private fun issueDeviceDelete(deviceSN: String, sessionId: Long) {
        try {
            if (connectedDeviceSN() != deviceSN) {
                RecordingStore.setDeletePendingOnDevice(deviceSN, sessionId, true)
                return
            }
            // Persist the intent first: the flag is only ever cleared by a correlated success.
            RecordingStore.setDeletePendingOnDevice(deviceSN, sessionId, true)
            if (inFlightDeletes.putIfAbsent(sessionId, deviceSN) != null) {
                return // a command for this session is already awaiting its callback
            }
            deviceLink.deleteFile(sessionId)
            AppLog.i(TAG, "Requested device delete for sessionId=$sessionId")
        } catch (e: Exception) {
            inFlightDeletes.remove(sessionId)
            AppLog.w(TAG, "deleteFromDevice failed for sessionId=$sessionId", e)
        }
    }

    /**
     * SDK bleDeleteFile result, forwarded by DeviceManager. The callback has no SN, so it is
     * correlated against the (sessionId -> SN) captured when the command was ISSUED. If the
     * connected device changed between issue and callback, the outcome is UNKNOWN — keep the
     * pending flag (a retry against the right device is harmless; clearing it wrongly is not).
     */
    fun handleDeviceDeleteResult(sessionId: Long, status: Int) {
        val targetSN = inFlightDeletes.remove(sessionId)
        if (targetSN == null) {
            AppLog.w(TAG, "bleDeleteFile for sessionId=$sessionId with no in-flight command — ignored")
            return
        }
        if (status != 0) {
            AppLog.w(TAG, "device delete failed (status=$status) sessionId=$sessionId — will retry")
            return
        }
        if (connectedDeviceSN() != targetSN) {
            AppLog.w(TAG, "device changed between delete command and callback (sessionId=$sessionId) — outcome unknown, keeping deletePending")
            return
        }
        RecordingStore.setDeletePendingOnDevice(targetSN, sessionId, false)
        AppLog.i(TAG, "Device delete confirmed for sessionId=$sessionId")
    }

    /** Epoch millis -> ISO8601 UTC ("2026-01-02T03:04:05Z"), or null for unknown timestamps. */
    fun isoUtc(epochMillis: Long): String? {
        if (epochMillis <= 0) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMillis))
    }
}
