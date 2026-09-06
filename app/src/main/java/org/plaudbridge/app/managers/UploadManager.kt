package org.plaudbridge.app.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
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
 * Uploads locally synced recordings to the self-hosted plaud-bridge-server.
 *
 * Recordings come off the device via SyncManager (BLE exportAudio / WiFi fast transfer) as MP3s;
 * this manager pushes every not-yet-uploaded file to POST /api/v1/recordings and records the
 * server id. A 200 with duplicate:true counts as success (the server already has the audio).
 *
 * Uploads are retried on the next kick (sync completion, app foreground, manual Sync now) —
 * during a WiFi fast transfer the phone is on the device hotspot with no internet, so uploads
 * intentionally queue up and run after the transfer ends.
 *
 * When the "delete after upload" setting is ON (default OFF), the recording is deleted from the
 * device over BLE after the server confirms the upload.
 */
object UploadManager {

    private const val TAG = "UploadManager"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)

    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state.asStateFlow()

    /** Process the pending-upload queue (no-op when a run is already in flight). */
    fun kick() {
        if (!RecordingStore.isServerConfigured) return
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                processQueue()
            } finally {
                running.set(false)
            }
        }
    }

    private fun processQueue() {
        val pending = RecordingStore.pendingUploads
            .filter { it.localPath != null && File(it.localPath!!).exists() }
        if (pending.isEmpty()) {
            _state.value = UploadState.Idle
            return
        }
        AppLog.i(TAG, "Uploading ${pending.size} pending recording(s)")
        var failures = 0
        pending.forEachIndexed { index, rec ->
            _state.value = UploadState.Uploading(index + 1, pending.size, rec.name)
            try {
                val file = File(rec.localPath!!)
                val result = ApiClient.uploadRecording(
                    file = file,
                    sessionId = rec.sessionId,
                    deviceSn = rec.deviceSN.ifBlank {
                        RecordingStore.lastConnectedDeviceSN ?: ""
                    },
                    startedAtIso = isoUtc(rec.createdAt),
                    durationSec = rec.duration.takeIf { it > 0 }?.toDouble()
                )
                RecordingStore.markAsUploaded(rec.sessionId, result.id)
                // Propagate the new upload badge to every list observing SyncManager.files.
                SyncManager.shared.refreshFilesFromStore()
                AppLog.i(
                    TAG,
                    "Uploaded sessionId=${rec.sessionId} -> id=${result.id ?: "?"} duplicate=${result.duplicate}"
                )
                if (RecordingStore.deleteAfterUpload) deleteFromDevice(rec.sessionId)
            } catch (e: Exception) {
                failures++
                AppLog.w(TAG, "Upload failed for sessionId=${rec.sessionId}", e)
            }
        }
        _state.value = if (failures == 0) UploadState.Idle
        else UploadState.Failed("$failures upload(s) failed — will retry on the next sync")
    }

    /** Delete the (now safely uploaded) recording from the device, if BLE is up. */
    private fun deleteFromDevice(sessionId: Long) {
        try {
            if (!sdk.PlaudDeviceAgent.isConnected()) {
                AppLog.i(TAG, "delete-after-upload deferred (device not connected) sessionId=$sessionId")
                return
            }
            sdk.PlaudDeviceAgent.deleteFile(sessionId)
            AppLog.i(TAG, "Requested device delete for sessionId=$sessionId")
        } catch (e: Exception) {
            AppLog.w(TAG, "deleteFromDevice failed for sessionId=$sessionId", e)
        }
    }

    /** Epoch millis -> ISO8601 UTC ("2026-01-02T03:04:05Z"), or null for unknown timestamps. */
    fun isoUtc(epochMillis: Long): String? {
        if (epochMillis <= 0) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMillis))
    }
}
