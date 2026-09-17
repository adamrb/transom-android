package io.github.adamrb.transom.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.adamrb.transom.common.AppLog

/**
 * Brings the connection service back after a reboot or an app update. Without it a phone that
 * restarted overnight would not reconnect to the recorder until the user opened the app, which
 * is exactly the case background sync exists for. BOOT_COMPLETED is one of the documented
 * exemptions from the API 31+ restriction on starting foreground services from the background.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        AppLog.i(TAG, "received $action")
        // RecordingStore is initialised by TransomApp.onCreate, which always runs before a
        // receiver in this process. sync() applies the policy (paired + configured + enabled).
        DeviceConnectionService.sync(context)
    }
}
