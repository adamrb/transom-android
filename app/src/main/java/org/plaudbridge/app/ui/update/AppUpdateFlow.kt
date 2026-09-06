package org.plaudbridge.app.ui.update

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.plaudbridge.app.R
import org.plaudbridge.app.net.UpdateManager
import java.io.File

/**
 * Dialog flow for a self-hosted app update: prompt (version + notes) → download + verify →
 * unknown-sources consent if needed → system installer. Thin UI shell around the tested
 * seams in [UpdateManager]; used by both the Settings manual check and the foreground
 * auto-check in MainActivity.
 */
object AppUpdateFlow {

    /**
     * A verified APK parked while the user grants the Android 8+ "install unknown apps"
     * permission. MainActivity.onResume calls [resumePendingInstall] so the flow continues
     * when they come back from system settings.
     */
    private var pendingInstall: File? = null

    fun promptInstall(activity: FragmentActivity, manifest: UpdateManager.Manifest) {
        val message = buildString {
            append(activity.getString(R.string.update_available_fmt, manifest.versionName))
            manifest.notes?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download_install) { _, _ ->
                downloadAndInstall(activity, manifest)
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun downloadAndInstall(activity: FragmentActivity, manifest: UpdateManager.Manifest) {
        val progress = AlertDialog.Builder(activity)
            .setMessage(R.string.update_downloading)
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { UpdateManager.downloadAndVerify(activity.applicationContext, manifest) }
            }
            progress.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            result.fold(
                onSuccess = { apk -> install(activity, apk) },
                onFailure = { e ->
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.update_available_title)
                        .setMessage(
                            activity.getString(
                                R.string.update_download_failed_fmt, e.message ?: "unknown error"
                            )
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            )
        }
    }

    private fun install(activity: FragmentActivity, apk: File) {
        if (!UpdateManager.canRequestInstalls(activity)) {
            // Android 8+ unknown-sources flow: park the verified APK, send the user to the
            // per-app toggle; resumePendingInstall picks it back up on return.
            pendingInstall = apk
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(R.string.update_allow_installs)
                .setPositiveButton(R.string.bluetooth_open_settings) { _, _ ->
                    activity.startActivity(UpdateManager.unknownSourcesIntent(activity))
                }
                .setNegativeButton(R.string.cancel) { _, _ -> pendingInstall = null }
                .show()
            return
        }
        activity.startActivity(UpdateManager.installIntent(activity, apk))
    }

    /** Continue a parked install after the unknown-sources detour (call from onResume). */
    fun resumePendingInstall(activity: FragmentActivity) {
        val apk = pendingInstall ?: return
        if (!UpdateManager.canRequestInstalls(activity)) return // still not granted
        pendingInstall = null
        if (apk.exists()) activity.startActivity(UpdateManager.installIntent(activity, apk))
    }
}
