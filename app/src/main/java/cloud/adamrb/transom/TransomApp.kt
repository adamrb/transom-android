package cloud.adamrb.transom

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import cloud.adamrb.transom.managers.DeviceManager
import cloud.adamrb.transom.managers.DeviceManagerProtocol
import cloud.adamrb.transom.managers.RecordingManager
import cloud.adamrb.transom.managers.RecordingManagerProtocol
import cloud.adamrb.transom.managers.SyncManager
import cloud.adamrb.transom.managers.SyncManagerProtocol
import cloud.adamrb.transom.managers.mock.MockDeviceManager
import cloud.adamrb.transom.managers.mock.MockRecordingManager
import cloud.adamrb.transom.managers.mock.MockSyncManager
import cloud.adamrb.transom.models.DeviceConnectionState
import cloud.adamrb.transom.service.DeviceConnectionService
import cloud.adamrb.transom.storage.RecordingStore

/**
 * Application entry point
 * Initializes RecordingStore and the manager singletons
 */
class TransomApp : Application() {

    companion object {
        /** Set to true to use the mock managers, enabling UI development without a real device */
        const val USE_MOCK = false

        lateinit var instance: TransomApp
            private set
    }

    // Manager instances (switch between real / mock based on USE_MOCK)
    val deviceManager: DeviceManagerProtocol by lazy {
        if (USE_MOCK) MockDeviceManager() else DeviceManager.shared.also { it.setContext(this) }
    }
    val recordingManager: RecordingManagerProtocol by lazy {
        if (USE_MOCK) MockRecordingManager() else RecordingManager.shared
    }
    val syncManager: SyncManagerProtocol by lazy {
        if (USE_MOCK) MockSyncManager() else SyncManager.shared
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        RecordingStore.init(this)
        // Settings > Appearance, before any activity inflates: the DayNight theme picks its
        // palette from the delegate's default night mode.
        RecordingStore.appearance.apply()
        // Transcript / automation channels exist from the first launch, so Settings >
        // Notifications shows them before the first notification is ever posted.
        cloud.adamrb.transom.common.AppNotifications.ensureChannels(this)
        // Reconcile the index with the filesystem: recordings whose exported audio vanished
        // (legacy cacheDir eviction, user "clear cache") become unsynced again so the sync flow
        // re-downloads them while the recorder copy still exists.
        Thread {
            RecordingStore.clearMissingLocalFiles()
            // Drop stale self-update downloads: anything for this (or an older) version — i.e.
            // after a successful update launched — and anything older than 7 days.
            cloud.adamrb.transom.net.UpdateManager.cleanupStaleApks(
                this, BuildConfig.VERSION_CODE, System.currentTimeMillis()
            )
        }.start()
        registerForegroundReconnect()
        startBackgroundSync()
    }

    /**
     * Background sync lives in a foreground service (DeviceConnectionService). Start it at process
     * start when a device is already paired, and again the moment a device connects: that is the
     * only reliable "pairing completed" signal (DeviceManager writes lastConnectedDeviceSN there),
     * and it covers first-time onboarding as well as "Add device" from Home. sync() is idempotent
     * and applies the policy (paired + configured + setting on), so a connect while the setting is
     * off does nothing. On API 31+ a start from the background is refused by the OS; the service
     * logs and the next foreground entry retries via MainActivity.
     */
    private fun startBackgroundSync() {
        if (USE_MOCK) return
        DeviceConnectionService.sync(this)
        appScope.launch {
            deviceManager.connectionState.collect { state ->
                if (state is DeviceConnectionState.Connected) DeviceConnectionService.sync(this@TransomApp)
            }
        }
    }

    /** Process-lifetime scope for app-wide observers (the Application object is never destroyed). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * App-wide foreground reconnect trigger (mirrors iOS sceneDidBecomeActive): whenever the app
     * returns to the foreground from ANY activity — not just MainActivity — try to reconnect the
     * last device after 2s, guarded by connected/OTA/paired checks inside attemptReconnect().
     */
    private fun registerForegroundReconnect() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacksAdapter() {
            private var startedCount = 0
            override fun onActivityStarted(activity: android.app.Activity) {
                val wasBackground = startedCount == 0
                startedCount++
                if (!wasBackground || USE_MOCK) return
                // Returning to the foreground: retry any queued server uploads, and check on
                // automations still being followed.
                cloud.adamrb.transom.managers.UploadManager.kick()
                cloud.adamrb.transom.managers.AutomationWatcher.kick()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (RecordingStore.lastConnectedDeviceSN != null &&
                        RecordingStore.userId != null
                    ) {
                        deviceManager.attemptReconnect()
                    }
                }, 2_000)
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                startedCount = (startedCount - 1).coerceAtLeast(0)
            }
        })
    }
}

/** No-op base so callers override only the callbacks they need. */
open class ActivityLifecycleCallbacksAdapter : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
    override fun onActivityStarted(activity: android.app.Activity) {}
    override fun onActivityResumed(activity: android.app.Activity) {}
    override fun onActivityPaused(activity: android.app.Activity) {}
    override fun onActivityStopped(activity: android.app.Activity) {}
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
    override fun onActivityDestroyed(activity: android.app.Activity) {}
}
