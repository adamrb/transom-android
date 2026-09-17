package cloud.adamrb.transom.ui.common

import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import cloud.adamrb.transom.R
import cloud.adamrb.transom.managers.DeviceManagerProtocol
import cloud.adamrb.transom.managers.SyncManagerProtocol
import cloud.adamrb.transom.models.DeviceConnectionState
import cloud.adamrb.transom.models.FirmwareUpdateUiState
import cloud.adamrb.transom.models.PairedDeviceInfo
import cloud.adamrb.transom.models.PlaudDevice
import cloud.adamrb.transom.models.RecordingFile
import cloud.adamrb.transom.models.ScannedDevice
import cloud.adamrb.transom.models.SyncState
import java.io.File

/** Sync manager whose state and files tests set directly; records what the screens ask of it. */
class FakeSyncManager : SyncManagerProtocol {
    override val state = MutableStateFlow<SyncState>(SyncState.Idle)
    override val files = MutableStateFlow<List<RecordingFile>>(emptyList())
    var startSyncCalls = 0
    var wifiCalls = 0
    val renamed = mutableListOf<Pair<RecordingFile, String>>()
    val deleted = mutableListOf<RecordingFile>()

    override fun fetchFileList() {}
    override fun startSync() { startSyncCalls++ }
    override fun startWiFiTransfer() { wifiCalls++ }
    override fun stopSync() { state.value = SyncState.Idle }
    override fun deleteFile(file: RecordingFile) { deleted += file; files.value = files.value.filterNot { it.id == file.id } }
    override fun removeFromPhone(file: RecordingFile) {}
    override fun renameFile(file: RecordingFile, newName: String) { renamed += file to newName }
    override fun exportAudio(file: RecordingFile, callback: (Result<File>) -> Unit) {}
}

/** Device manager with a settable connected recorder and no BLE behind it. */
class FakeDeviceManager : DeviceManagerProtocol {
    override val connectionState = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    override val connectedDevice = MutableStateFlow<PlaudDevice?>(null)
    override val scannedDevices: StateFlow<List<ScannedDevice>> = MutableStateFlow(emptyList())
    override val firmwareProgress: StateFlow<Float?> = MutableStateFlow(null)
    override val firmwareUpdateState: StateFlow<FirmwareUpdateUiState?> = MutableStateFlow(null)
    override val cloudAlerts: SharedFlow<String> = MutableSharedFlow()
    override val recoveryOffers: SharedFlow<ScannedDevice> = MutableSharedFlow()
    override var suppressAutoReconnect: Boolean = false
    var reconnectAttempts = 0
    var paired: List<PairedDeviceInfo> = emptyList()

    override fun configure(userId: String) {}
    override fun startScan() {}
    override fun stopScan() {}
    override fun attemptReconnect() { reconnectAttempts++ }
    override fun connect(device: ScannedDevice, userId: String, userInitiated: Boolean) {}
    override fun disconnect() {}
    override fun unpair() {}
    override fun startDeviceRecovery(device: ScannedDevice) {}
    override fun getPairedDevices(): List<PairedDeviceInfo> = paired
    override fun switchDevice(sn: String) {}
    override fun refreshRecordState() {}
    override fun refreshDeviceInfo() {}
    override fun refreshFirmwareCheck() {}
    override fun startFirmwareUpdate() {}
    override fun setAutoSync(enabled: Boolean) {}

    fun connect(sn: String = "SN-A", name: String = "NotePin") {
        connectedDevice.value = PlaudDevice(
            serialNumber = sn, name = name, batteryLevel = 80, isCharging = false,
            storageUsed = 1L shl 30, storageTotal = 4L shl 30, firmwareVersion = "1.0"
        )
        connectionState.value = DeviceConnectionState.Connected
    }

    fun disconnectRecorder() {
        connectedDevice.value = null
        connectionState.value = DeviceConnectionState.Disconnected
    }
}

/** Bare host for fragment tests that also records the snackbars the fragment asks for. */
class SnackbarHostActivity : AppCompatActivity(), SnackbarHost {
    val messages = mutableListOf<String>()
    val actionLabels = mutableListOf<String?>()
    var lastAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        setTheme(R.style.Theme_Transom)
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = CONTAINER })
    }

    override fun showSnackbar(message: String, actionLabel: String?, action: (() -> Unit)?) {
        messages += message
        actionLabels += actionLabel
        lastAction = action
    }

    companion object { const val CONTAINER = 4343 }
}
