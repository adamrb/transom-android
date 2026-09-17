package cloud.adamrb.transom.ui.common

import androidx.annotation.VisibleForTesting
import cloud.adamrb.transom.TransomApp
import cloud.adamrb.transom.managers.DeviceManagerProtocol
import cloud.adamrb.transom.managers.SyncManagerProtocol

/**
 * Where the main screens get their managers. Production reads them off the Application; tests
 * drop in fakes so a fragment can be driven through sync failures and connection changes
 * without the BLE SDK. Reset the overrides to null after each test.
 */
object AppManagers {

    @VisibleForTesting
    var syncOverride: SyncManagerProtocol? = null

    @VisibleForTesting
    var deviceOverride: DeviceManagerProtocol? = null

    fun sync(app: TransomApp): SyncManagerProtocol = syncOverride ?: app.syncManager

    fun device(app: TransomApp): DeviceManagerProtocol = deviceOverride ?: app.deviceManager

    @VisibleForTesting
    fun reset() {
        syncOverride = null
        deviceOverride = null
    }
}
