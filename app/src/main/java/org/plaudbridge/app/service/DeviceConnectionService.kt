package org.plaudbridge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.R
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.managers.DeviceManagerProtocol
import org.plaudbridge.app.models.DeviceConnectionState
import org.plaudbridge.app.models.PlaudDevice
import org.plaudbridge.app.models.displayName
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.main.MainActivity

/**
 * Foreground service that keeps the process alive and the BLE link (re)connected while the app
 * is in the background. Without it Doze freezes the process, the SDK's connection dies with it,
 * and a recording stopped while the phone sits in a pocket is not downloaded until the user next
 * opens the app.
 *
 * The service deliberately does NOT sync anything itself: DeviceManager already auto-syncs after
 * every connect and RecordingManager after every record-stop, and both end in UploadManager.kick().
 * Its two jobs are (1) hold a foreground notification so the process survives, and (2) call
 * DeviceManager.attemptReconnect() on a backoff schedule while the device is out of reach.
 */
class DeviceConnectionService : Service() {

    companion object {
        private const val TAG = "DeviceConnectionService"
        const val CHANNEL_ID = "device_connection"
        const val NOTIFICATION_ID = 0x5EC0

        /**
         * True between onCreate and onDestroy. Lets callers skip redundant start/stop calls
         * (MainActivity.onResume fires often) and lets tests see the lifecycle without binding.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Policy inputs read from the store; see [BackgroundSyncPolicy.shouldRun]. */
        fun isEligible(): Boolean = BackgroundSyncPolicy.shouldRun(
            paired = RecordingStore.lastConnectedDeviceSN != null,
            userConfigured = RecordingStore.userId != null,
            enabled = RecordingStore.isBackgroundSyncEnabled
        )

        /** Start or stop the service so that its state matches the policy. Safe from any context. */
        fun sync(context: Context) {
            if (isEligible()) start(context) else stop(context)
        }

        /**
         * Request a foreground start. On API 31+ this throws when the app is in the background
         * (ForegroundServiceStartNotAllowedException, an IllegalStateException subclass) unless a
         * documented exemption applies, such as BOOT_COMPLETED. The failure is logged, not fatal:
         * the next foreground entry (MainActivity.onResume) retries.
         */
        fun start(context: Context) {
            if (PlaudBridgeApp.USE_MOCK) return
            if (isRunning) return
            val intent = Intent(context.applicationContext, DeviceConnectionService::class.java)
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (e: IllegalStateException) {
                AppLog.w(TAG, "foreground start not allowed right now", e)
            } catch (e: SecurityException) {
                AppLog.w(TAG, "foreground start rejected", e)
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            try {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, DeviceConnectionService::class.java)
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "stopService failed", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var reconnectJob: Job? = null
    private var notificationJob: Job? = null
    private var inForeground = false

    private val deviceManager: DeviceManagerProtocol
        get() = (application as PlaudBridgeApp).deviceManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first, even when about to quit: a service started via
        // startForegroundService that stops without ever calling startForeground trips the
        // "did not then call startForeground" crash on some API 26-28 builds.
        if (!inForeground && !enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isEligible()) {
            AppLog.i(TAG, "no paired device or background sync disabled; stopping")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (notificationJob == null) observeConnection()
        if (reconnectJob == null) runReconnectLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    // MARK: - Foreground / notification

    /**
     * startForeground with the connectedDevice type. API 34 additionally requires that at least
     * one prerequisite runtime permission (BLUETOOTH_CONNECT/SCAN here) is granted and throws
     * SecurityException otherwise, e.g. if the user revoked Nearby devices in system settings.
     */
    private fun enterForeground(): Boolean {
        val notification = buildNotification(getString(R.string.bg_sync_notif_disconnected))
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            )
            inForeground = true
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException on API 31+.
            AppLog.w(TAG, "startForeground not allowed", e)
            false
        } catch (e: SecurityException) {
            AppLog.w(TAG, "startForeground rejected (missing connected-device permission?)", e)
            false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bg_sync_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.bg_sync_channel_desc)
            setShowBadge(false)
            // IMPORTANCE_LOW is already silent; be explicit so OEM defaults cannot add a sound.
            setSound(null, null)
            enableVibration(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(this, 0, openApp, piFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /** Mirror the connection state into the persistent notification text. */
    private fun observeConnection() {
        notificationJob = scope.launch {
            combine(deviceManager.connectionState, deviceManager.connectedDevice) { state, device ->
                notificationText(state, device)
            }.collect { text -> updateNotification(text) }
        }
    }

    private fun notificationText(state: DeviceConnectionState, device: PlaudDevice?): String {
        val pairedName = RecordingStore.lastConnectedDeviceSN?.let { RecordingStore.deviceName(it) }
            ?: getString(R.string.device)
        return when (state) {
            is DeviceConnectionState.Connected ->
                getString(R.string.bg_sync_notif_connected_fmt, device?.displayName ?: pairedName)
            is DeviceConnectionState.Scanning,
            is DeviceConnectionState.Connecting ->
                getString(R.string.bg_sync_notif_searching_fmt, pairedName)
            is DeviceConnectionState.Disconnected,
            is DeviceConnectionState.Failed ->
                getString(R.string.bg_sync_notif_disconnected)
        }
    }

    private fun updateNotification(text: String) {
        if (!inForeground) return
        try {
            // On API 33+ without POST_NOTIFICATIONS this is a silent no-op, which is allowed for
            // a foreground service: the process still runs, the user just sees no notification.
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: SecurityException) {
            AppLog.w(TAG, "notification update rejected", e)
        }
    }

    // MARK: - Reconnect loop

    /**
     * While the link is down, nudge DeviceManager.attemptReconnect() on the backoff schedule from
     * [BackgroundSyncPolicy]. DeviceManager already runs its own bounded burst after an unexpected
     * disconnect (10 scans, 30s apart) and attemptReconnect() RESTARTS that burst, so we only call
     * it once the link has stayed down for a whole backoff window with no scan activity at all:
     * that is the signal the burst has been exhausted (or never started, e.g. after a boot). The
     * attempt counter resets on every successful connection so a brief dropout always gets the
     * fast 15s retry again. attemptReconnect() itself stays a no-op while suppressed (user
     * disconnect / adding a device) or mid-OTA, so the loop never fights those flows.
     */
    private fun runReconnectLoop() {
        reconnectJob = scope.launch {
            var attempt = 0
            while (isActive) {
                if (!isEligible()) {
                    AppLog.i(TAG, "policy no longer satisfied; stopping")
                    ServiceCompat.stopForeground(this@DeviceConnectionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                val state = deviceManager.connectionState
                when (state.value) {
                    is DeviceConnectionState.Connected -> {
                        attempt = 0
                        // Park until the link drops; no polling while connected.
                        state.first { it !is DeviceConnectionState.Connected }
                    }
                    is DeviceConnectionState.Scanning,
                    is DeviceConnectionState.Connecting -> {
                        // DeviceManager is mid-attempt; wait for the outcome without counting it.
                        state.first { it !is DeviceConnectionState.Scanning && it !is DeviceConnectionState.Connecting }
                    }
                    is DeviceConnectionState.Disconnected,
                    is DeviceConnectionState.Failed -> {
                        val windowMs = BackgroundSyncPolicy.reconnectDelayMs(attempt)
                        val activity = withTimeoutOrNull(windowMs) {
                            state.first { it !is DeviceConnectionState.Disconnected && it !is DeviceConnectionState.Failed }
                        }
                        if (activity != null) continue // DeviceManager woke up on its own
                        attempt++
                        AppLog.i(TAG, "link down for ${windowMs / 1000}s with no scan; reconnect attempt #$attempt")
                        // Re-run configure so the SDK is initialised even when this process was
                        // started by the boot receiver (no activity ran WelcomeActivity's
                        // configure) and so the partner token stays fresh over a long-lived
                        // process. It is cheap when the SDK is already up with a valid token.
                        RecordingStore.userId?.let { deviceManager.configure(it) }
                        deviceManager.attemptReconnect()
                    }
                }
            }
        }
    }
}
