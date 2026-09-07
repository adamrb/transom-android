package org.plaudbridge.app.managers

import android.util.Log
import org.plaudbridge.app.common.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sdk.PlaudDeviceAgent
import sdk.audio.AudioExportFormat
import sdk.audio.AudioExporter
import sdk.ble.wifi.IWifiTransferAgent
import com.tinnotech.penblesdk.entity.BleFile
import org.plaudbridge.app.models.*
import org.plaudbridge.app.storage.RecordingStore
import java.io.File
import java.util.UUID

/**
 * File sync manager, built on the GA facade sdk.PlaudDeviceAgent.
 * The file list arrives via PlaudDeviceAgentListener.bleFileList (forwarded by DeviceManager);
 * audio download uses PlaudDeviceAgent.exportAudio; WiFi fast transfer uses
 * PlaudDeviceAgent.startWifiTransfer + getWifiAgent.
 */
class SyncManager private constructor() : SyncManagerProtocol {

    companion object {
        private const val TAG = "SyncManager"

        /**
         * Grace period between stopping a BLE transfer and opening the WiFi session. The device
         * refuses to open its hotspot while still streaming (openWiFi status 4 / connect 1003).
         */
        private const val DEVICE_IDLE_GRACE_MS = 1_500L

        /** How long to wait for the device to confirm the post-transfer batch delete. */
        private const val DELETE_CONFIRM_TIMEOUT_MS = 15_000L

        @Volatile
        private var instance: SyncManager? = null

        val shared: SyncManager
            get() = instance ?: synchronized(this) {
                instance ?: SyncManager().also { instance = it }
            }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // MARK: - StateFlow

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    // Seed from persisted storage so previously synced files appear immediately on cold start,
    // before any new sync runs.
    private val _files = MutableStateFlow<List<RecordingFile>>(RecordingStore.allFiles)
    override val files: StateFlow<List<RecordingFile>> = _files.asStateFlow()

    private val pendingSessionIds = mutableListOf<Long>()
    private var totalToSync = 0
    private var syncedCount = 0
    private var lastProgressUpdate: Long = 0
    private var lastProgressBytes: Double = 0.0
    private var currentFileSize: Int = 0

    private var silentFetch = false

    /**
     * The file-list request currently awaiting its bleFileList callback. The SN is captured in
     * an immutable object when the request is ISSUED, and the callback consumes it exactly once
     * (getAndSet(null)) — so a second/stale callback finds nothing and is dropped, and a newer
     * request can never have its SN read by an older callback slot-style. The request is also
     * invalidated on device disconnect (see [invalidateFileListRequest]).
     *
     * Residual limitation (documented, not fixable app-side): the SDK callback carries no
     * correlation id, so if TWO requests were somehow outstanding at once the callback order
     * could not be verified. Requests only exist for the single connected device and are
     * cleared on disconnect, which closes the cross-device window in practice.
     */
    private class FileListRequest(val sn: String)
    private val fileListRequest =
        java.util.concurrent.atomic.AtomicReference<FileListRequest?>(null)

    /** Drop any outstanding file-list request — its answer can no longer be attributed safely. */
    fun invalidateFileListRequest() {
        fileListRequest.set(null)
    }

    /** Device the active BLE sync run is downloading from (set by handleFileList). */
    @Volatile
    private var activeSyncSN: String = ""

    /** Device the active WiFi fast-transfer session belongs to. */
    @Volatile
    private var wifiDeviceSN: String = ""

    /** sessionId -> byte size from the latest device file list (for transfer-speed display). */
    private val fileSizesBySession = mutableMapOf<Long, Int>()

    /** SN of the currently connected device (single source for attribution checks). */
    private fun currentDeviceSN(): String? =
        DeviceManager.shared.connectedDevice.value?.serialNumber?.takeIf { it.isNotBlank() }
            ?: RecordingStore.lastConnectedDeviceSN

    /** True while a WiFi fast transfer is in flight; guards finishWiFiTransfer so cleanup runs once. */
    @Volatile
    private var wifiTransferActive = false

    // MARK: - WiFi fast-transfer session state (mirrors iOS v1.0.6 semantics)
    private var wifiTotal = 0
    private var wifiCompletedCount = 0
    private var wifiCurrentFileName: String? = null
    /** Last shown transfer speed (bytes/sec), retained between samples so the readout stays steady. */
    private var lastWifiSpeed = 0L
    /** Sessions downloaded this WiFi session; deleted from the device in one batch at the end. */
    /** Sessions exported this WiFi session; a Set so the deferred batch delete can't double-list. */
    private val wifiSyncedSessionIds = linkedSetOf<Long>()
    /** Sessions still to export via exportAudioViaWiFi (serial queue). */
    private val wifiExportQueue = ArrayDeque<Long>()

    /** Current BLE export stage (SDK 1.0.9 onStageChanged); null when no export is running. */
    @Volatile
    private var bleExportStage: sdk.audio.ExportStage? = null
    /** Set when WiFi transfer was requested while a BLE export was already transcoding locally. */
    @Volatile
    private var wifiRequestedDuringExport = false

    /** True while waiting for the device to confirm the batch delete. */
    @Volatile
    private var pendingWifiDeletes = false
    private var deleteFallbackJob: Job? = null
    /** Fallback teardown if the device never self-disconnects after completion. */
    private var deviceCloseFallbackJob: Job? = null

    // MARK: - Sync control

    override fun fetchFileList() {
        silentFetch = true
        fileListRequest.set(currentDeviceSN()?.let { FileListRequest(it) })
        queryDeviceFileList()
    }

    override fun startSync() {
        if (_state.value.isActive) return
        silentFetch = false
        fileListRequest.set(currentDeviceSN()?.let { FileListRequest(it) })
        _state.value = SyncState.Syncing(SyncProgress(totalFiles = 0, syncedFiles = 0))
        queryDeviceFileList()
    }

    private fun queryDeviceFileList() {
        try {
            // Results arrive via PlaudDeviceAgentListener.bleFileList → handleBleFileList (single
            // global listener owned by DeviceManager).
            PlaudDeviceAgent.getFileList()
        } catch (e: Exception) {
            AppLog.e(TAG, "queryDeviceFileList failed", e)
            scope.launch {
                if (!silentFetch) _state.value = SyncState.Failed("Failed to fetch file list")
                silentFetch = false
            }
        }
    }

    /** Entry for the facade bleFileList callback (forwarded by DeviceManager's listener). */
    fun handleBleFileList(bleFiles: List<BleFile>) {
        AppLog.i(TAG, "bleFileList: found ${bleFiles.size} files")
        // Async attribution guard: this callback carries no SN. Consume the outstanding request
        // exactly once — a stale/duplicate callback finds nothing — and require that the device
        // the request was issued to is still the connected one; otherwise the list belongs to
        // the OLD device and is dropped rather than stored under the new device's identity.
        val request = fileListRequest.getAndSet(null)
        val connectedSN = currentDeviceSN()
        if (request == null || connectedSN == null || request.sn != connectedSN) {
            AppLog.w(TAG, "bleFileList dropped — no matching outstanding request for this device")
            silentFetch = false
            return
        }
        val requestSN = request.sn
        val sessionIds = bleFiles.map { it.sessionId }
        // Note: the newer BleFile exposes only sessionId/fileSize publicly; duration members are
        // internal. Duration stays 0 for now.
        // TODO(SDK): expose a public duration getter, aligned with iOS BleFile.duration().
        val durations = bleFiles.map { 0L }
        val sizes = bleFiles.map { it.fileSize.toInt() }
        synchronized(fileSizesBySession) {
            bleFiles.forEach { fileSizesBySession[it.sessionId] = it.fileSize.toInt() }
        }
        val sns = List(bleFiles.size) { requestSN }
        handleFileList(sessionIds, durations, sizes, sns)
    }

    override fun startWiFiTransfer() {
        // Already opening or running a WiFi transfer — ignore re-entry. wifiTransferActive covers
        // the whole session, INCLUDING the device-idle grace window below; checking only for the
        // WiFiTransferring state let a second tap during WiFiConnecting fire a second openWiFi,
        // which the device rejects with status 4 ("WiFi fast transfer already in progress"). That
        // error tore our state down while the first session was still connecting, leaving it
        // orphaned: handshake and file list arrived but nothing was exported, and the device kept
        // its hotspot up until the firmware timeout.
        if (wifiTransferActive) {
            AppLog.i(TAG, "startWiFiTransfer ignored — a WiFi session is already starting")
            return
        }

        // A BLE export already past the download (SDK 1.0.9 stage = TRANSCODING) is only doing
        // local work; the device is idle. Killing it there wastes that work AND makes WiFi
        // re-transfer the same file, since the device only drops it once the export completes.
        // Wait for it (a few seconds) and re-evaluate — the stage signal is language-neutral, so
        // unlike the earlier progress-string heuristic this can't mis-fire.
        if (bleExportStage == sdk.audio.ExportStage.TRANSCODING) {
            AppLog.i(TAG, "WiFi transfer deferred — current file is finishing its local transcode")
            wifiRequestedDuringExport = true
            _state.value = SyncState.Syncing(
                SyncProgress(totalFiles = totalToSync, syncedFiles = syncedCount, fileProgress = 1f)
            )
            return
        }

        // NOTE: deliberately no "are there unsynced files?" pre-check here. The local store can lag
        // behind the device (a fresh recording may not be in the list yet), and guessing wrong
        // silently swallowed the user's tap. The device itself reports an empty file list / closes
        // the session when there is nothing to transfer (mirrors iOS).

        // Auto-sync on connect may be mid-download over BLE. The device can't open its hotspot
        // while it is busy streaming a file — it answers openWiFi with status 4 (busy), or the
        // WiFi connect fails (1003). Always stop the BLE transfer first (state may still lag
        // behind an in-flight exportAudio) and give the device a moment to go idle.
        try {
            PlaudDeviceAgent.stopSyncFile()
        } catch (e: Exception) {
            AppLog.w(TAG, "stopSyncFile before WiFi transfer failed", e)
        }
        pendingSessionIds.clear()

        // During WiFi fast transfer the device drops BLE to free the radio for its hotspot.
        // Suppress BLE auto-reconnect so the reconnect logic doesn't tear down the WiFi
        // transfer mid-handshake (it also can't reach the partner API while the phone is on
        // the device hotspot). BLE is re-enabled and reconnected when the transfer ends.
        DeviceManager.shared.suppressAutoReconnect = true
        wifiTransferActive = true
        // The WiFi session belongs to the device connected right now; everything it produces is
        // attributed to this SN (BLE drops during the transfer, so it can't be re-read later).
        wifiDeviceSN = currentDeviceSN() ?: ""

        // Phase 1 shown immediately; the actual session starts after the device-idle grace period.
        _state.value = SyncState.WiFiConnecting(SyncState.WiFiConnectPhase.OPENING_HOTSPOT)
        scope.launch {
            delay(DEVICE_IDLE_GRACE_MS)
            if (wifiTransferActive) beginWiFiSession()
        }
    }

    /** Opens the WiFi session once the device is idle (see startWiFiTransfer). */
    private fun beginWiFiSession() {
        val userId = RecordingStore.userId ?: ""

        wifiTotal = 0
        wifiCompletedCount = 0
        wifiCurrentFileName = null
        lastWifiSpeed = 0L
        var fileListRequested = false
        val started = PlaudDeviceAgent.startWifiTransfer(userId, object : IWifiTransferAgent.WifiTransferCallback {
            // startWifiTransfer only opens the channel + handshake. Once READY (or handshake done)
            // we must explicitly request the file list, then trigger the batch download — otherwise
            // the device just heartbeats and closes the session without transferring anything.
            private fun requestFileListOnce() {
                if (fileListRequested) return
                fileListRequested = true
                if (!wifiTransferActive) {
                    abandonOrphanWiFiSession("handshake completed after teardown")
                    return
                }
                AppLog.i(TAG, "WiFi ready -> requesting file list")
                PlaudDeviceAgent.getWifiAgent()?.getFileList()
            }

            override fun onConnectionStateChanged(state: IWifiTransferAgent.WifiConnectionState) {
                // Drive the three-phase connect UI (hotspot → joining WiFi → starting transfer)
                val phase = when (state) {
                    IWifiTransferAgent.WifiConnectionState.CONNECTING ->
                        SyncState.WiFiConnectPhase.CONNECTING_WIFI
                    IWifiTransferAgent.WifiConnectionState.CONNECTED,
                    IWifiTransferAgent.WifiConnectionState.HANDSHAKING ->
                        SyncState.WiFiConnectPhase.HANDSHAKING
                    else -> null
                }
                if (phase != null && _state.value is SyncState.WiFiConnecting) {
                    scope.launch { _state.value = SyncState.WiFiConnecting(phase) }
                }
                if (state == IWifiTransferAgent.WifiConnectionState.READY) requestFileListOnce()
            }
            override fun onHandshakeCompleted(info: String) { requestFileListOnce() }
            override fun onFileListReceived(files: List<IWifiTransferAgent.WifiFileInfo>) {
                if (!wifiTransferActive) {
                    abandonOrphanWiFiSession("file list received after teardown")
                    return
                }
                wifiTotal = files.size
                AppLog.i(TAG, "WiFi file list received: ${files.size} files")
                registerWifiFiles(files)
                if (files.isEmpty()) {
                    // Nothing to transfer — let the device close the session itself (device-led).
                    scope.launch { awaitDeviceWiFiClose() }
                } else {
                    scope.launch {
                        _state.value = SyncState.WiFiTransferring(
                            SyncProgress(totalFiles = wifiTotal, syncedFiles = 0)
                        )
                    }
                    // Export one by one via exportAudioViaWiFi instead of the agent's raw
                    // downloadAllFiles(): the raw path writes the device stream verbatim (no E2EE
                    // decrypt, no container), producing .opus files that can't be played, timed or
                    // transcribed. exportAudioViaWiFi runs the same AudioExporter pipeline as BLE.
                    wifiExportQueue.clear()
                    wifiExportQueue.addAll(files.map { it.sessionId })
                    exportNextWiFiFile()
                }
            }
            override fun onTransferProgress(sessionId: Long, progress: Int, speed: Double) {
                // SDK contract: progress = byte-accurate 0–100 of the current file,
                // speed = real byte rate in KB/s (1s window). Retain the last non-zero speed so
                // the readout stays steady between samples (mirrors iOS).
                if (speed > 0) lastWifiSpeed = (speed * 1024).toLong()
                scope.launch {
                    _state.value = SyncState.WiFiTransferring(
                        SyncProgress(
                            totalFiles = wifiTotal,
                            syncedFiles = wifiCompletedCount,
                            currentFileName = wifiCurrentFileName,
                            fileProgress = progress / 100f,
                            bytesPerSecond = lastWifiSpeed
                        )
                    )
                }
            }
            override fun onFileTransferCompleted(sessionId: Long, path: String) {
                // Raw-stream completion of the SDK's internal download step. The playable file is
                // produced by exportAudioViaWiFi's converter (see exportNextWiFiFile), which reports
                // via its own callback — don't register the raw .opus as the synced recording.
                AppLog.i(TAG, "WiFi raw download finished for sessionId=$sessionId (awaiting export)")
            }
            override fun onWifiTransferStopped() {
                // Device closed the session (WIFI_CLOSE / client disconnect) or manual stop.
                scope.launch { finishWiFiTransfer(SyncState.Completed) }
            }
            override fun onDeviceBatteryUpdate(level: Int, charging: Boolean) {}
            override fun onError(code: Int, message: String) {
                AppLog.e(TAG, "WiFi transfer error ($code): $message")
                scope.launch { finishWiFiTransfer(SyncState.Failed(message)) }
            }
            // Batch-download callbacks belong to the agent's raw downloadAllFiles() path, which we
            // no longer drive (see onFileListReceived) — kept as no-ops for interface completeness.
            override fun onBatchDownloadStarted(total: Int) {}
            override fun onBatchDownloadProgress(current: Int, total: Int, filename: String) {}
            override fun onBatchDownloadCompleted(success: Int, failed: Int, results: List<IWifiTransferAgent.BatchDownloadResult>) {}
            override fun onFileDeleteCompleted(success: Boolean, deletedCount: Int, error: String?) {
                // Not used: device-side deletion happens after confirmed server upload (UploadManager).
                AppLog.i(TAG, "WiFi device-side delete result: success=$success, deleted=$deletedCount, error=$error")
            }
        })

        if (!started) {
            AppLog.w(TAG, "startWifiTransfer returned false")
            finishWiFiTransfer(SyncState.Failed("Failed to start WiFi fast transfer"))
        }
    }

    /**
     * A WiFi session that finished coming up after we had already torn our own state down (e.g. a
     * duplicate openWiFi was rejected while the first session was still connecting). Nothing drives
     * it any more — exportNextWiFiFile bails on !wifiTransferActive — so close it right away.
     * Otherwise the device keeps its hotspot up, heartbeats alone until the firmware timeout and
     * only then returns to BLE.
     */
    private fun abandonOrphanWiFiSession(reason: String) {
        AppLog.w(TAG, "Closing orphaned WiFi session ($reason)")
        try {
            sdk.NiceBuildSdk.stopWifiTransfer()
        } catch (e: Exception) {
            AppLog.w(TAG, "stopWifiTransfer for orphaned session failed", e)
        }
    }

    /**
     * Merge the WiFi device file list into the store (keeping already-synced local files), so the
     * incoming files show up and can be marked synced once downloaded. Mirrors handleFileList.
     */
    private fun registerWifiFiles(files: List<IWifiTransferAgent.WifiFileInfo>) {
        val localSynced = RecordingStore.allFiles.filter { it.syncedAt != null }
        // Composite (SN, session) dedupe. Blank-SN legacy entries are deliberately NOT matched:
        // letting them stand in for this device's sessions is exactly the wildcard that caused
        // cross-device suppression, so a legacy blank record may cost one redundant re-download.
        val syncedIds = localSynced
            .filter { it.deviceSN == wifiDeviceSN }
            .map { it.sessionId }
            .toSet()
        val deviceFiles = files
            .filter { it.sessionId !in syncedIds }
            .map { wf ->
                RecordingFile(
                    id = UUID.randomUUID().toString(),
                    sessionId = wf.sessionId,
                    deviceSN = wifiDeviceSN,
                    name = "Untitled Recording",
                    duration = if (wf.duration > 0) wf.duration / 1000L else 0L,
                    createdAt = if (wf.timestamp > 0) wf.timestamp * 1000 else wf.sessionId * 1000,
                    syncedAt = null,
                    localPath = null,
                    summaryText = null,
                    transcriptJSON = null
                )
            }
        val all = localSynced + deviceFiles
        RecordingStore.replaceAllFiles(all)
        scope.launch { _files.value = all }
    }

    /**
     * Kick off the WiFi transfer that was deferred while a BLE export finished its local transcode.
     * By then the file is synced and dropped from the device, so the "nothing pending" guard in
     * startWiFiTransfer usually short-circuits to Completed instead of opening a dead session.
     */
    private fun resumeDeferredWiFiTransferIfNeeded() {
        if (!wifiRequestedDuringExport) return
        wifiRequestedDuringExport = false
        AppLog.i(TAG, "Resuming deferred WiFi transfer request")
        scope.launch { startWiFiTransfer() }
    }

    /**
     * Serially export the queued WiFi sessions through the SDK's WiFi export pipeline
     * (download → E2EE decrypt → MP3), mirroring the BLE download loop. Produces the same
     * playable/timed/transcribable MP3 as BLE sync, unlike the raw downloadAllFiles() path.
     */
    private fun exportNextWiFiFile() {
        if (!wifiTransferActive) return
        val sessionId = wifiExportQueue.removeFirstOrNull()
        if (sessionId == null) {
            AppLog.i(TAG, "WiFi export queue drained (${wifiSyncedSessionIds.size} file(s) synced)")
            // Unlike the template app there is NO batch delete here: the phone is on the device
            // hotspot with no internet, so nothing has been uploaded to the bridge server yet.
            // Deletion (if enabled) happens in UploadManager after each confirmed upload, over BLE.
            wifiSyncedSessionIds.clear()
            scope.launch {
                _files.value = RecordingStore.allFiles
                awaitDeviceWiFiClose()
            }
            return
        }

        val outputDir = RecordingStore.exportDir
        wifiCurrentFileName = RecordingStore.allFiles.firstOrNull { it.sessionId == sessionId }?.displayName
        // Capture the owning SN at issue time; the completion callback must not read the
        // mutable global (a later session could have overwritten it by then).
        val exportSN = wifiDeviceSN
        AppLog.i(TAG, "WiFi export start: sessionId=$sessionId")

        try {
            PlaudDeviceAgent.exportAudioViaWiFi(
                sessionId = sessionId,
                outputDir = outputDir,
                format = AudioExportFormat.MP3,
                channels = 1,
                callback = object : AudioExporter.ExportCallback {
                    override fun onProgress(progress: Int, message: String) {
                        scope.launch {
                            _state.value = SyncState.WiFiTransferring(
                                SyncProgress(
                                    totalFiles = wifiTotal,
                                    syncedFiles = wifiCompletedCount,
                                    currentFileName = wifiCurrentFileName,
                                    fileProgress = progress / 100f,
                                    bytesPerSecond = lastWifiSpeed
                                )
                            )
                        }
                    }

                    override fun onComplete(outputFile: java.io.File) {
                        AppLog.i(TAG, "WiFi export complete: sessionId=$sessionId -> ${outputFile.name}")
                        // exportSN was captured when THIS export was issued (closure, not the
                        // mutable global) — a session started later can't change its attribution.
                        RecordingStore.markAsSynced(
                            exportSN, sessionId, outputFile.absolutePath,
                            audioDurationSec(outputFile.absolutePath)
                        )
                        wifiSyncedSessionIds.add(sessionId)
                        wifiCompletedCount++
                        scope.launch { _files.value = RecordingStore.allFiles }
                        // No kick() here: the phone is on the recorder's hotspot without internet,
                        // so the upload runs when the transfer ends. Schedule the durable retry
                        // now so the file still reaches the server if the process dies first.
                        UploadManager.ensureScheduled()
                        exportNextWiFiFile()
                    }

                    override fun onError(error: String) {
                        AppLog.w(TAG, "WiFi export failed for sessionId=$sessionId: $error")
                        exportNextWiFiFile()
                    }
                }
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "exportAudioViaWiFi threw for sessionId=$sessionId", e)
            exportNextWiFiFile()
        }
    }

    /**
     * Transfer + deletes are done. The device exits on its own (heartbeats → self-disconnect) and
     * resumes BLE quickly, so DON'T force-stop the session — that preempts the graceful exit.
     * Publish Completed, keep the socket alive, and let onWifiTransferStopped drive the cleanup.
     * Force teardown only if the device never self-disconnects within the fallback window.
     */
    private fun awaitDeviceWiFiClose() {
        if (!wifiTransferActive) return
        AppLog.i(TAG, "WiFi transfer complete — waiting for device to self-disconnect")
        // Keep reporting progress (100%) instead of Completed: the device is still finishing its
        // side of the session, and claiming "done" here is what made the app look ahead of the
        // device (PLA2-309). Completed is published by finishWiFiTransfer once the device closes.
        _state.value = SyncState.WiFiTransferring(
            SyncProgress(
                totalFiles = wifiTotal,
                syncedFiles = wifiCompletedCount,
                currentFileName = null,
                fileProgress = 1f
            )
        )
        deviceCloseFallbackJob?.cancel()
        deviceCloseFallbackJob = scope.launch {
            delay(20_000)
            AppLog.w(TAG, "Device did not self-disconnect in time — forcing WiFi teardown")
            finishWiFiTransfer(SyncState.Completed)
        }
    }

    /**
     * Terminal handling for a WiFi fast transfer: publish the final state, re-enable BLE
     * auto-reconnect (the phone is leaving the device hotspot), and kick a reconnect so the
     * device comes back over BLE.
     */
    private fun finishWiFiTransfer(finalState: SyncState) {
        // Run once per transfer: stopWifiTransfer below re-triggers onWifiTransferStopped,
        // which calls back here — the guard prevents a loop and double reconnects.
        if (!wifiTransferActive) return
        wifiTransferActive = false

        deviceCloseFallbackJob?.cancel()
        deviceCloseFallbackJob = null
        deleteFallbackJob?.cancel()
        deleteFallbackJob = null
        pendingWifiDeletes = false
        wifiSyncedSessionIds.clear()

        _state.value = finalState
        // Close the WiFi session so the device stops heartbeating and the phone leaves the
        // hotspot before we scan for BLE again.
        // Use NiceBuildSdk.stopWifiTransfer, NOT getWifiAgent().stopWifiTransfer(): only the former
        // also asks the device to close its hotspot over BLE (the agent-level call just tears down
        // the phone side, leaving the device in WiFi mode until its ~2 min firmware timeout).
        try {
            sdk.NiceBuildSdk.stopWifiTransfer()
        } catch (e: Exception) {
            AppLog.w(TAG, "stopWifiTransfer on finish failed", e)
        }
        // SDK 1.0.9+: stopWifiTransfer also asks the device to close its hotspot (closeWiFi over
        // BLE) — no manual setDeviceWiFi(false) needed here; a second close can make the device
        // answer with an error status.
        // stopWifiTransfer above only reaches the device while BLE is up. If BLE happens to be down
        // right now (the device drops it while switching to AP mode), defer the close to the next
        // successful reconnect — otherwise the device stays in WiFi mode (blue LED) until its ~2 min
        // firmware timeout. When BLE is up the SDK already sent it, so don't send a second one.
        val bleUp = try {
            PlaudDeviceAgent.isConnected()
        } catch (e: Exception) {
            false
        }
        // On the failure path the SDK session may never have come up at all, so the stopWifiTransfer
        // above had no session to piggyback the hotspot close on — that is how the device stayed in
        // WiFi mode (blue LED) after a rejected openWiFi. Ask it explicitly here; a redundant close
        // only costs an error status in the log.
        if (finalState is SyncState.Failed && bleUp) {
            try {
                PlaudDeviceAgent.setDeviceWiFi(false)
            } catch (e: Exception) {
                AppLog.w(TAG, "setDeviceWiFi(false) after failed WiFi transfer failed", e)
            }
        }
        DeviceManager.shared.pendingDeviceWiFiClose = !bleUp
        DeviceManager.shared.suppressAutoReconnect = false
        // Give the WiFi network release and the device's radio switch (AP -> BLE) a moment
        // to settle before scanning, otherwise the device may not be advertising yet.
        scope.launch {
            delay(1_500)
            DeviceManager.shared.attemptReconnect()
            // Back on real internet — push everything the WiFi transfer produced to the server.
            UploadManager.kick()
        }
    }

    override fun stopSync() {
        scope.launch {
            pendingSessionIds.clear()
            _state.value = SyncState.Idle
        }
        try {
            PlaudDeviceAgent.stopSyncFile()
        } catch (e: Exception) {
            AppLog.w(TAG, "stopSyncFile failed", e)
        }
    }

    fun reset() {
        pendingSessionIds.clear()
        scope.launch { _state.value = SyncState.Idle }
        _files.value = emptyList()
    }

    /** Re-publish the persisted file list (e.g. after UploadManager marks files uploaded). */
    fun refreshFilesFromStore() {
        scope.launch { _files.value = RecordingStore.allFiles }
    }

    // MARK: - File operations

    override fun deleteFile(file: RecordingFile) {
        // Local-only delete (mirrors iOS): the on-device copy is only removed by the sync flows
        // after a successful download, never from the user-facing delete action.
        RecordingStore.deleteFile(file)
        _files.value = RecordingStore.allFiles
    }

    override fun renameFile(file: RecordingFile, name: String) {
        RecordingStore.renameFile(file, name)
        _files.value = RecordingStore.allFiles
    }

    override fun exportAudio(file: RecordingFile, completion: (Result<File>) -> Unit) {
        val outputDir = RecordingStore.exportDir
        PlaudDeviceAgent.exportAudio(
            sessionId = file.sessionId,
            outputDir = outputDir,
            format = AudioExportFormat.MP3,
            channels = 1,
            callback = object : AudioExporter.ExportCallback {
                override fun onProgress(progress: Int, message: String) { }
                override fun onComplete(outputFile: File) {
                    completion(Result.success(outputFile))
                }
                override fun onError(error: String) {
                    completion(Result.failure(SyncError.ExportFailed(error)))
                }
            }
        )
    }

    // MARK: - Internal callbacks

    fun handleFileList(
        sessionIds: List<Long>,
        durations: List<Long>,
        sizes: List<Int>,
        sns: List<String>
    ) {
        // This run downloads from exactly one device (all sns entries are the request SN).
        activeSyncSN = sns.firstOrNull() ?: ""
        val localSynced = RecordingStore.allFiles.filter { it.isSynced }
        // Composite (SN, session) dedupe: a session id already synced from ANOTHER device must
        // still be downloaded from this one. Blank-SN legacy entries are their own namespace and
        // never suppress a real device's session (they may re-download; that is the safe side).
        fun isAlreadySynced(sid: Long, sn: String) = localSynced.any {
            it.sessionId == sid && it.deviceSN == sn
        }
        val newSessionIds = sessionIds.filterIndexed { i, sid ->
            !isAlreadySynced(sid, if (i < sns.size) sns[i] else "")
        }

        val deviceFiles = sessionIds.mapIndexedNotNull { i, sid ->
            if (isAlreadySynced(sid, if (i < sns.size) sns[i] else "")) return@mapIndexedNotNull null
            RecordingFile(
                id = UUID.randomUUID().toString(),
                sessionId = sid,
                deviceSN = if (i < sns.size) sns[i] else "",
                name = "Untitled Recording",
                duration = if (i < durations.size) durations[i] / 1000L else 0L,
                createdAt = sid * 1000,
                syncedAt = null,
                localPath = null,
                summaryText = null,
                transcriptJSON = null
            )
        }

        val allFiles = localSynced + deviceFiles
        RecordingStore.replaceAllFiles(allFiles)

        AppLog.i(TAG, "handleFileList: ${sessionIds.size} on device (${newSessionIds.size} new), ${localSynced.size} local synced")

        scope.launch {
            _files.value = allFiles

            if (silentFetch) {
                silentFetch = false
                _state.value = SyncState.Idle
                return@launch
            }

            if (newSessionIds.isEmpty()) {
                _state.value = SyncState.Completed
                // Nothing new on the device — still retry any uploads that failed earlier.
                UploadManager.kick()
                return@launch
            }

            pendingSessionIds.clear()
            pendingSessionIds.addAll(newSessionIds)
            totalToSync = newSessionIds.size
            syncedCount = 0
            downloadNextFile()
        }
    }

    fun handleDownloadProgress(sessionId: Int, progress: Int) {
        val now = System.currentTimeMillis()
        val currentBytes = currentFileSize.toDouble() * progress / 100.0
        if (lastProgressUpdate > 0) {
            val dt = (now - lastProgressUpdate) / 1000.0
            val dBytes = currentBytes - lastProgressBytes
            if (dt <= 0.1) return
            val speed = dBytes / dt
            lastProgressUpdate = now
            lastProgressBytes = currentBytes
            val currentFile = RecordingStore.allFiles.firstOrNull { it.sessionId == sessionId.toLong() }
            val prog = SyncProgress(
                totalFiles = totalToSync, syncedFiles = syncedCount,
                currentFileName = currentFile?.displayName, fileProgress = progress / 100f,
                bytesPerSecond = speed.toLong()
            )
            scope.launch { _state.value = SyncState.Syncing(prog) }
        } else {
            lastProgressUpdate = now
            lastProgressBytes = currentBytes
        }
    }

    /**
     * Read the real duration (seconds) from a downloaded audio file. BleFile exposes no public
     * duration (TODO(SDK)), so until it does, the exported file itself is the source of truth.
     */
    fun audioDurationSec(path: String): Long {
        // MediaMetadataRetriever is unreliable for Ogg/Opus on many OEMs (null duration), so fall
        // back to a throwaway MediaPlayer prepare, which decodes the header properly.
        val retriever = android.media.MediaMetadataRetriever()
        val fromRetriever = try {
            retriever.setDataSource(path)
            (retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) / 1000L
        } catch (e: Exception) {
            0L
        } finally {
            // AutoCloseable close() is API 29+; release() works on all supported API levels
            try { retriever.release() } catch (_: Exception) { }
        }
        if (fromRetriever > 0) return fromRetriever

        // Ogg/Opus: read the last Ogg page's granule position directly (granule rate for Opus is
        // always 48kHz) — exact, dependency-free, and immune to OEM MediaPlayer/retriever quirks.
        if (path.endsWith(".opus", ignoreCase = true) || path.endsWith(".ogg", ignoreCase = true)) {
            oggOpusDurationSec(path)?.let { return it }
        }
        AppLog.w(TAG, "audioDurationSec: could not determine duration for $path")
        return 0L
    }

    /** Duration of an Ogg/Opus file = last page granulePos / 48000 (spec-defined rate). */
    private fun oggOpusDurationSec(path: String): Long? = try {
        java.io.RandomAccessFile(path, "r").use { raf ->
            val fileLen = raf.length()
            val tailLen = minOf(fileLen, 65_536L).toInt()
            val buf = ByteArray(tailLen)
            raf.seek(fileLen - tailLen)
            raf.readFully(buf)
            // Find the LAST "OggS" capture pattern in the tail
            var last = -1
            for (i in 0..tailLen - 27) {
                if (buf[i] == 'O'.code.toByte() && buf[i + 1] == 'g'.code.toByte() &&
                    buf[i + 2] == 'g'.code.toByte() && buf[i + 3] == 'S'.code.toByte()
                ) last = i
            }
            if (last < 0) return null
            // granulePos = 8 bytes little-endian at offset 6 of the page header
            var granule = 0L
            for (b in 7 downTo 0) granule = (granule shl 8) or (buf[last + 6 + b].toLong() and 0xFF)
            if (granule <= 0) null else granule / 48_000L
        }
    } catch (e: Exception) {
        null
    }

    /** [deviceSN] is captured when the export is ISSUED (closure), never read at completion. */
    fun handleDownloadComplete(deviceSN: String, sessionId: Int, outputPath: String) {
        org.plaudbridge.app.common.OpusRepair.repairIfNeeded(outputPath)
        syncedCount++
        RecordingStore.markAsSynced(deviceSN, sessionId.toLong(), outputPath, audioDurationSec(outputPath))
        scope.launch { _files.value = RecordingStore.allFiles }
        // NOTE: unlike Plaud's template app, the file is NOT deleted from the device here.
        // UploadManager pushes it to the bridge server and — only when the user enabled
        // "delete after upload" — removes it from the device once the server confirms.
        UploadManager.kick()
        downloadNextFile()
    }

    // MARK: - Private

    private fun downloadNextFile() {
        if (pendingSessionIds.isEmpty()) {
            scope.launch { _state.value = SyncState.Completed }
            return
        }

        val nextSessionId = pendingSessionIds.removeFirst()
        lastProgressUpdate = 0
        lastProgressBytes = 0.0
        currentFileSize = synchronized(fileSizesBySession) { fileSizesBySession[nextSessionId] ?: 0 }
        // Capture the owning SN for THIS export now; the completion callback uses the captured
        // value so a run started later can never change this file's attribution.
        val exportSN = activeSyncSN

        val currentFile = RecordingStore.allFiles.firstOrNull { it.sessionId == nextSessionId }
        scope.launch {
            _state.value = SyncState.Syncing(
                SyncProgress(totalFiles = totalToSync, syncedFiles = syncedCount, currentFileName = currentFile?.displayName)
            )
        }

        PlaudDeviceAgent.exportAudio(
            sessionId = nextSessionId,
            outputDir = RecordingStore.exportDir,
            // MP3 (LAME mono 32kbps, matches iOS BLE sync): plays everywhere AND transcribes —
            // the backend's opus pipeline currently returns empty results (see sdk-requests doc),
            // and the upload API rejects wav (FILE_TYPE_INVALID).
            format = AudioExportFormat.MP3,
            channels = 1,
            callback = object : AudioExporter.ExportCallback {
                override fun onProgress(progress: Int, message: String) {
                    // SDK 1.0.9 contract: 0-100 is monotonic download-byte progress only.
                    handleDownloadProgress(nextSessionId.toInt(), progress)
                }
                override fun onStageChanged(stage: sdk.audio.ExportStage) {
                    // Language-neutral phase signal (1.0.9). TRANSCODING means the bytes are off
                    // the device and only local work remains — see startWiFiTransfer.
                    bleExportStage = stage
                }
                override fun onComplete(outputFile: File) {
                    bleExportStage = null
                    handleDownloadComplete(exportSN, nextSessionId.toInt(), outputFile.absolutePath)
                    resumeDeferredWiFiTransferIfNeeded()
                }
                override fun onError(error: String) {
                    AppLog.e(TAG, "Download failed for sessionId=$nextSessionId: $error")
                    bleExportStage = null
                    syncedCount++
                    resumeDeferredWiFiTransferIfNeeded()
                    downloadNextFile()
                }
            }
        )
    }
}

// MARK: - Error types

sealed class SyncError(message: String) : Exception(message) {
    class FileNotSynced : SyncError("File not synced yet, cannot export")
    class ExportFailed(detail: String) : SyncError("Export failed: $detail")
}
