package org.plaudbridge.app.ui.common

import androidx.annotation.VisibleForTesting
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.managers.DeviceManagerProtocol
import org.plaudbridge.app.managers.SyncManagerProtocol

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

    fun sync(app: PlaudBridgeApp): SyncManagerProtocol = syncOverride ?: app.syncManager

    fun device(app: PlaudBridgeApp): DeviceManagerProtocol = deviceOverride ?: app.deviceManager

    @VisibleForTesting
    fun reset() {
        syncOverride = null
        deviceOverride = null
    }
}
