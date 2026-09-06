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
 * this manager pushes every not-yet-uploaded file to POST /api/v1/recordings. A recording is
 * marked uploaded ONLY on a strictly validated response (see ApiClient.uploadRecording); anything
 * else counts as a failed upload and is retried on the next kick.
 *
 * Uploads are retried on the next kick (sync completion, device connect, app foreground, manual
 * Sync now) — during a WiFi fast transfer the phone is on the device hotspot with no internet,
 * so uploads intentionally queue up and run after the transfer ends.
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

    /** Propagates the new upload badge to lists observing SyncManager.files (test seam). */
    internal var onFilesChanged: () -> Unit = { SyncManager.shared.refreshFilesFromStore() }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)

    /** Work arrived while a run was in flight — loop again instead of dropping the wakeup. */
    private val dirty = AtomicBoolean(false)

    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state.asStateFlow()

    /** Process the pending-upload queue (kicks arriving mid-run are queued, never lost). */
    fun kick() {
        if (!RecordingStore.isServerConfigured) return
        dirty.set(true)
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (dirty.getAndSet(false)) {
                    processQueue()
                    processPendingDeletes()
                }
            } finally {
                running.set(false)
                // A kick may have landed between the last getAndSet and releasing the flag.
                if (dirty.get()) kick()
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
                // NOTE: rec.deviceSN is used as-is. A blank SN is still uploaded (the server can
                // store it), but it must never be "fixed up" with the currently connected SN —
                // that guess is exactly what enables cross-device deletion bugs.
                val result = ApiClient.uploadRecording(
                    file = file,
                    sessionId = rec.sessionId,
                    deviceSn = rec.deviceSN,
                    startedAtIso = isoUtc(rec.createdAt),
                    durationSec = rec.duration.takeIf { it > 0 }?.toDouble()
                )
                // Only a validated result reaches this point (non-blank id, exact contract).
                RecordingStore.markAsUploaded(rec.deviceSN, rec.sessionId, result.id)
                onFilesChanged()
                AppLog.i(TAG, "Uploaded sessionId=${rec.sessionId} duplicate=${result.duplicate}")
                if (RecordingStore.deleteAfterUpload) {
                    requestDeviceDelete(rec.deviceSN, rec.sessionId)
                }
            } catch (e: Exception) {
                failures++
                AppLog.w(TAG, "Upload failed for sessionId=${rec.sessionId}", e)
            }
        }
        _state.value = if (failures == 0) UploadState.Idle
        else UploadState.Failed("$failures upload(s) failed — will retry on the next sync")
    }

    // MARK: - Delete after upload

    /** SN of the currently connected device, or null. */
    private fun connectedDeviceSN(): String? = deviceLink.connectedDeviceSN()

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
     * recording's SN. The SDK's bleDeleteFile callback carries no SN, so the pending flag is
     * cleared once [handleDeviceDeleteResult] reports status 0 while this device is connected;
     * on failure the flag stays set and the delete is retried on a later kick.
     */
    private fun issueDeviceDelete(deviceSN: String, sessionId: Long) {
        try {
            if (connectedDeviceSN() != deviceSN) {
                RecordingStore.setDeletePendingOnDevice(deviceSN, sessionId, true)
                return
            }
            RecordingStore.setDeletePendingOnDevice(deviceSN, sessionId, true)
            deviceLink.deleteFile(sessionId)
            AppLog.i(TAG, "Requested device delete for sessionId=$sessionId")
        } catch (e: Exception) {
            AppLog.w(TAG, "deleteFromDevice failed for sessionId=$sessionId", e)
        }
    }

    /**
     * SDK bleDeleteFile result, forwarded by DeviceManager. Attribution: the callback has no SN,
     * so it is credited to the device connected at callback time — the same device the command
     * was issued to (issueDeviceDelete refuses to send otherwise).
     */
    fun handleDeviceDeleteResult(sessionId: Long, status: Int) {
        if (status != 0) {
            AppLog.w(TAG, "device delete failed (status=$status) sessionId=$sessionId — will retry")
            return
        }
        val connected = connectedDeviceSN() ?: return
        RecordingStore.setDeletePendingOnDevice(connected, sessionId, false)
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
