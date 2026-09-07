package org.plaudbridge.app.net

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.ui.update.UpdateResultReceiver
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app updates from the self-hosted bridge server (no store, no third parties):
 *
 *   GET /api/v1/apk/info  -> {"version_code", "version_name", "filename", "sha256",
 *                             "size_bytes", "uploaded_at", "notes"}   (404 = none hosted)
 *   GET /api/v1/apk/file  -> the APK binary
 *
 * Responsibilities: strict manifest parsing, version comparison, the auto-check throttle
 * (24h after a successful response, 1h after a transient failure, reset on server change),
 * bounded download (200 MB ceiling, Content-Length and byte-count enforcement) with MANDATORY
 * sha256/size verification, APK IDENTITY validation (package name, newer versionCode, signer
 * certificates matching the installed app — a malicious server must not be able to sideload a
 * different app), PackageInstaller-session install plumbing, and stale-download cleanup.
 * Blocking functions must run on Dispatchers.IO; the dialogs/consent flow lives in
 * ui.update.AppUpdateFlow.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"

    /** After a successful check (up-to-date / not hosted / update offered): recheck in 24h. */
    const val CHECK_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    /** After a failed check (server unreachable etc.): retry after 1h, not 24h. */
    const val FAILURE_BACKOFF_MS: Long = 60L * 60 * 1000

    /** Hard ceiling on the APK download — anything larger is refused outright. */
    const val MAX_APK_BYTES: Long = 200L * 1024 * 1024

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
     * Auto-check throttle (pure seam). Due when:
     *  - the server host changed since the last recorded check (old throttle is meaningless);
     *  - no fresh SUCCESSFUL check within 24h AND no fresh FAILED attempt within 1h.
     * Timestamps in the future (clock rollback) never block — a bogus value must not wedge
     * checking forever. Callers record success and failure timestamps separately.
     */
    fun isAutoCheckDue(
        lastSuccessAtMs: Long,
        lastFailureAtMs: Long,
        nowMs: Long,
        lastCheckedHost: String?,
        currentHost: String?
    ): Boolean {
        if (lastCheckedHost != currentHost) return true
        val successFresh = lastSuccessAtMs in 1..nowMs && nowMs - lastSuccessAtMs < CHECK_INTERVAL_MS
        val failureFresh = lastFailureAtMs in 1..nowMs && nowMs - lastFailureAtMs < FAILURE_BACKOFF_MS
        return !successFresh && !failureFresh
    }

    /**
     * Server config generation observed by the last recorded auto-check. The generation counter
     * is per-process, so this in-memory mirror is enough: any URL/token change during this
     * process bumps it and makes the next foreground check due (host changes are additionally
     * covered persistently via the stored last-checked host).
     */
    @Volatile
    var lastCheckedConfigGeneration: Long = -1L

    /** Guards against concurrent in-flight auto-checks (e.g. rapid resume cycles). */
    private val autoCheckInFlight = AtomicBoolean(false)

    fun tryBeginAutoCheck(): Boolean = autoCheckInFlight.compareAndSet(false, true)

    fun endAutoCheck() = autoCheckInFlight.set(false)

    /** Blocking (Dispatchers.IO): fetch apk/info and compare against [installedVersionCode]. */
    fun checkForUpdate(installedVersionCode: Int): CheckResult =
        when (val info = ApiClient.fetchApkInfo()) {
            is ApiClient.ApkInfoResult.NotHosted -> CheckResult.NotHosted
            is ApiClient.ApkInfoResult.Error -> CheckResult.Error(info.message)
            is ApiClient.ApkInfoResult.Ok -> {
                val manifest = Manifest.fromJson(info.json)
                when {
                    manifest == null -> {
                        AppLog.w(TAG, "apk info response failed strict validation")
                        CheckResult.Error("malformed apk info from server")
                    }
                    manifest.sizeBytes > MAX_APK_BYTES ->
                        CheckResult.Error("hosted apk exceeds the ${MAX_APK_BYTES / (1024 * 1024)} MB limit")
                    else -> evaluate(manifest, installedVersionCode)
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

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
     * Blocking (Dispatchers.IO): download the hosted APK (bounded: 200 MB ceiling,
     * Content-Length must match size_bytes when present, streaming aborts past the expected
     * size) into a unique temp file, then verify sha256+size AND the APK's identity
     * ([validateApkIdentity]) before renaming it into place. On ANY failure the file is
     * DELETED and IOException thrown — unverified bytes never reach the installer.
     * [identityCheck] is injectable for tests only.
     */
    fun downloadAndVerify(
        context: Context,
        manifest: Manifest,
        identityCheck: (File) -> String? = { validateApkIdentity(context, it) }
    ): File {
        if (manifest.sizeBytes > MAX_APK_BYTES) {
            throw IOException("hosted apk (${manifest.sizeBytes} bytes) exceeds the $MAX_APK_BYTES byte limit")
        }
        val dir = apkDir(context)
        val tmp = File.createTempFile("update-", ".part", dir)
        try {
            ApiClient.downloadApk(tmp, manifest.sizeBytes)
            verify(tmp, manifest.sha256, manifest.sizeBytes)?.let {
                AppLog.w(TAG, "apk verification failed: $it")
                throw IOException(it)
            }
            identityCheck(tmp)?.let {
                AppLog.w(TAG, "apk identity validation failed: $it")
                throw IOException(it)
            }
            val dest = File(dir, "update-${manifest.versionCode}.apk")
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("could not move verified apk into place")
            return dest
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    // MARK: - APK identity (package / version / signer must match the installed app)

    /**
     * Validate that [apk] really is an update of THIS app before it goes anywhere near the
     * installer: correct package name, strictly newer versionCode, and signing certificates
     * matching the installed app's lineage. A compromised server that serves an arbitrary
     * (correctly hashed) APK fails here. Returns null when valid, else the reason.
     *
     * Below API 28 this relies on GET_SIGNATURES archive parsing, which historically did not
     * fully verify the archive's signature chain (the "fake ID" class of bugs on very old
     * platforms); the system installer still enforces the real signature check at install time,
     * this is defense in depth on top of it.
     */
    fun validateApkIdentity(context: Context, apk: File): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
        val installed = try {
            pm.getPackageInfo(context.packageName, flags)
        } catch (e: Exception) {
            return "could not read the installed package info"
        }
        return validateIdentityCore(
            expectedPackage = context.packageName,
            archivePackage = archive?.packageName,
            archiveVersionCode = archive?.let { versionCodeOf(it) } ?: -1L,
            installedVersionCode = versionCodeOf(installed),
            archiveSigners = archive?.let { currentSignerDigests(it) } ?: emptySet(),
            installedLineage = lineageDigests(installed),
            archiveParsed = archive != null
        )
    }

    /** Pure identity rules (JVM-testable without PackageManager). Null = valid. */
    fun validateIdentityCore(
        expectedPackage: String,
        archivePackage: String?,
        archiveVersionCode: Long,
        installedVersionCode: Long,
        archiveSigners: Set<String>,
        installedLineage: Set<String>,
        archiveParsed: Boolean
    ): String? {
        if (!archiveParsed) return "downloaded file is not a parseable APK"
        if (archivePackage != expectedPackage) {
            return "apk package '${archivePackage ?: "?"}' is not $expectedPackage"
        }
        if (archiveVersionCode <= installedVersionCode) {
            return "apk versionCode $archiveVersionCode is not newer than installed $installedVersionCode"
        }
        if (archiveSigners.isEmpty()) return "apk has no readable signing certificates"
        if (installedLineage.isEmpty()) return "could not read the installed app's signing certificates"
        if (!installedLineage.containsAll(archiveSigners)) {
            return "apk signing certificate does not match the installed app"
        }
        return null
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    /** SHA-256 digests of the package's CURRENT signing certificates. */
    private fun currentSignerDigests(info: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
                ?.map { sha256Hex(it.toByteArray()) }?.toSet() ?: emptySet()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { sha256Hex(it.toByteArray()) }?.toSet() ?: emptySet()
        }

    /** Installed signing lineage: every certificate this app has ever been signed with. */
    private fun lineageDigests(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT < 28) return currentSignerDigests(info)
        val si = info.signingInfo ?: return emptySet()
        val history = si.signingCertificateHistory?.toList() ?: emptyList()
        val current = si.apkContentsSigners?.toList() ?: emptyList()
        return (history + current).map { sha256Hex(it.toByteArray()) }.toSet()
    }

    // MARK: - Install plumbing (PackageInstaller session)

    /** True when the app may launch package installs (always true before Android 8). */
    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user grants this app the unknown-sources permission (API 26+). */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * Install the VERIFIED apk through a PackageInstaller session (blocking; Dispatchers.IO).
     * No implicit ACTION_VIEW intent: nothing but the platform's installer ever sees the bytes.
     * The commit result — including STATUS_PENDING_USER_ACTION, which launches the system
     * confirmation — is delivered to the non-exported [UpdateResultReceiver].
     */
    fun installViaPackageInstaller(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val statusIntent = Intent(context, UpdateResultReceiver::class.java)
                .setAction(UpdateResultReceiver.ACTION_UPDATE_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
            session.commit(pending.intentSender)
        }
    }

    /**
     * Delete stale downloads: anything for the running (or an older) version — i.e. after a
     * successful update launch — anything unrecognizable (incl. .part temp files), and anything
     * older than 7 days. Called once per app launch from PlaudBridgeApp.
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
