package org.plaudbridge.app.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.plaudbridge.app.common.AppLog
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * In-app updates from the self-hosted bridge server (no store, no third parties):
 *
 *   GET /api/v1/apk/info  -> {"version_code", "version_name", "filename", "sha256",
 *                             "size_bytes", "uploaded_at", "notes"}   (404 = none hosted)
 *   GET /api/v1/apk/file  -> the APK binary
 *
 * Responsibilities: strict manifest parsing, version comparison, the 24h auto-check throttle,
 * download + MANDATORY sha256/size verification (a mismatched file is deleted, never installed),
 * install-intent plumbing, and stale-download cleanup. Blocking functions must run on
 * Dispatchers.IO; the dialogs/consent flow lives in ui.update.AppUpdateFlow.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"

    /** Auto-check on foreground at most once per 24 hours. */
    const val CHECK_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    /** Downloaded APKs older than this are deleted on app launch. */
    const val APK_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** Subdirectory of filesDir (durable, unlike cacheDir) holding downloaded updates. */
    private const val APK_DIR = "apk-updates"

    private val APK_NAME_RE = Regex("update-(\\d+)\\.apk")

    // MARK: - Manifest

    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val filename: String,
        val sha256: String,
        val sizeBytes: Long,
        val uploadedAt: String?,
        val notes: String?
    ) {
        companion object {
            /**
             * Strict-typed parse of the apk/info body (same philosophy as the upload contract:
             * org.json coercion must never let a malformed manifest reach the installer).
             * Returns null when anything required is missing or mistyped.
             */
            fun fromJson(text: String): Manifest? {
                val json = try { JSONObject(text) } catch (_: Exception) { return null }
                val versionCode = json.opt("version_code") as? Int ?: return null
                if (versionCode <= 0) return null
                val versionName = (json.opt("version_name") as? String)
                    ?.takeIf { it.isNotBlank() } ?: return null
                val filename = (json.opt("filename") as? String)
                    ?.takeIf { it.isNotBlank() } ?: return null
                val sha256 = (json.opt("sha256") as? String)
                    ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) } ?: return null
                val sizeBytes = when (val s = json.opt("size_bytes")) {
                    is Int -> s.toLong()
                    is Long -> s
                    else -> return null
                }
                if (sizeBytes <= 0) return null
                return Manifest(
                    versionCode = versionCode,
                    versionName = versionName,
                    filename = filename,
                    sha256 = sha256,
                    sizeBytes = sizeBytes,
                    uploadedAt = json.opt("uploaded_at") as? String,
                    notes = json.opt("notes") as? String
                )
            }
        }
    }

    // MARK: - Check

    sealed class CheckResult {
        data class UpdateAvailable(val manifest: Manifest) : CheckResult()
        object UpToDate : CheckResult()
        /** Server responded 404: it hosts no APK. */
        object NotHosted : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    /** Pure comparison seam: only a strictly greater version_code is an update. */
    fun evaluate(manifest: Manifest, installedVersionCode: Int): CheckResult =
        if (manifest.versionCode > installedVersionCode) CheckResult.UpdateAvailable(manifest)
        else CheckResult.UpToDate

    /**
     * 24h auto-check throttle. Also due when the clock moved backwards past the stored
     * timestamp (a bogus future timestamp must not suppress checks forever).
     */
    fun isAutoCheckDue(lastCheckedAtMs: Long, nowMs: Long): Boolean =
        lastCheckedAtMs <= 0L ||
            nowMs < lastCheckedAtMs ||
            nowMs - lastCheckedAtMs >= CHECK_INTERVAL_MS

    /** Blocking (Dispatchers.IO): fetch apk/info and compare against [installedVersionCode]. */
    fun checkForUpdate(installedVersionCode: Int): CheckResult =
        when (val info = ApiClient.fetchApkInfo()) {
            is ApiClient.ApkInfoResult.NotHosted -> CheckResult.NotHosted
            is ApiClient.ApkInfoResult.Error -> CheckResult.Error(info.message)
            is ApiClient.ApkInfoResult.Ok -> {
                val manifest = Manifest.fromJson(info.json)
                if (manifest == null) {
                    AppLog.w(TAG, "apk info response failed strict validation")
                    CheckResult.Error("malformed apk info from server")
                } else {
                    evaluate(manifest, installedVersionCode)
                }
            }
        }

    // MARK: - Download + verification

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Verification seam: null when [file] matches the manifest, else a failure reason. */
    fun verify(file: File, expectedSha256: String, expectedSizeBytes: Long): String? {
        if (file.length() != expectedSizeBytes) {
            return "size mismatch (expected $expectedSizeBytes, got ${file.length()})"
        }
        val actual = sha256Hex(file)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            return "sha256 mismatch — download corrupted or tampered with"
        }
        return null
    }

    fun apkDir(context: Context): File =
        File(context.filesDir, APK_DIR).apply { mkdirs() }

    /**
     * Blocking (Dispatchers.IO): download the hosted APK and verify it against [manifest].
     * On any mismatch the file is DELETED and IOException thrown — unverified bytes never
     * reach the installer. Returns the verified APK file.
     */
    fun downloadAndVerify(context: Context, manifest: Manifest): File {
        val dest = File(apkDir(context), "update-${manifest.versionCode}.apk")
        ApiClient.downloadApk(dest)
        val failure = try {
            verify(dest, manifest.sha256, manifest.sizeBytes)
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        if (failure != null) {
            dest.delete()
            AppLog.w(TAG, "apk verification failed: $failure")
            throw IOException(failure)
        }
        return dest
    }

    // MARK: - Install plumbing

    /** True when the app may launch package installs (always true before Android 8). */
    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user grants this app the unknown-sources permission (API 26+). */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /** Installer intent for a VERIFIED apk via the existing FileProvider (files-path root). */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Delete stale downloads: anything for the running (or an older) version — i.e. after a
     * successful update launch — anything unrecognizable, and anything older than 7 days.
     * Called once per app launch from PlaudBridgeApp.
     */
    fun cleanupStaleApks(context: Context, installedVersionCode: Int, nowMs: Long) {
        val files = File(context.filesDir, APK_DIR).listFiles() ?: return
        for (f in files) {
            val code = APK_NAME_RE.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
            val stale = code == null ||
                code <= installedVersionCode ||
                nowMs - f.lastModified() > APK_MAX_AGE_MS
            if (stale && f.delete()) AppLog.i(TAG, "removed stale update download ${f.name}")
        }
    }
}
