package org.plaudbridge.app.ui.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.plaudbridge.app.R
import org.plaudbridge.app.common.AppLog

/**
 * Non-exported receiver for the PackageInstaller session commit result:
 * STATUS_PENDING_USER_ACTION carries the system confirmation intent (launched here),
 * success/failure are logged and surfaced with a toast (the success toast rarely shows —
 * a successful self-update kills the process).
 */
class UpdateResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_UPDATE_STATUS = "org.plaudbridge.app.UPDATE_STATUS"
        private const val TAG = "UpdateResultReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPDATE_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    AppLog.w(TAG, "pending-user-action status without a confirmation intent")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    AppLog.w(TAG, "could not launch install confirmation", e)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.i(TAG, "update installed")
                Toast.makeText(context, R.string.update_install_success, Toast.LENGTH_LONG).show()
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "status $status"
                AppLog.w(TAG, "update install failed: $message")
                Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
