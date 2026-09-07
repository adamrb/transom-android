package org.plaudbridge.app.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.plaudbridge.app.models.RecordingFile
import java.io.File
import java.util.UUID

object RecordingStore {

    private const val PREFS_NAME = "plaud_bridge_prefs"
    private const val KEY_LAST_CONNECTED_SN = "last_connected_device_sn"
    private const val KEY_PAIRED_SNS = "paired_device_sns"
    private const val KEY_PAIRED_NAMES = "paired_device_names"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_AUTO_SYNC = "is_auto_sync_enabled"
    private const val KEY_BACKGROUND_SYNC = "is_background_sync_enabled"
    private const val KEY_NOTIF_PERMISSION_ASKED = "notification_permission_asked"
    private const val KEY_FAST_TRANSFER_HIDE = "fast_transfer_never_show"
    private const val KEY_SERVER_BASE_URL = "server_base_url"
    private const val KEY_SERVER_AUTH_TOKEN = "server_auth_token"
    private const val KEY_PLAUD_DOMAIN = "plaud_domain"
    private const val KEY_DELETE_AFTER_UPLOAD = "delete_after_upload"
    private const val KEY_LIBRARY_WEBVIEW_HOST = "library_webview_host"
    private const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"
    private const val KEY_LAST_UPDATE_CHECK_FAILURE_AT = "last_update_check_failure_at"
    private const val KEY_LAST_UPDATE_CHECK_HOST = "last_update_check_host"
    private const val KEY_CACHED_PLAUD_TOKEN = "cached_plaud_token"
    private const val KEY_CACHED_PLAUD_TOKEN_EXPIRY = "cached_plaud_token_expiry"
    private const val RECORDINGS_FILE = "recordings.json"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- SharedPreferences ---

    /** Active device SN (the one currently selected / to auto-reconnect). */
    var lastConnectedDeviceSN: String?
        get() = prefs.getString(KEY_LAST_CONNECTED_SN, null)
        set(value) = prefs.edit().putString(KEY_LAST_CONNECTED_SN, value).apply()

    // --- Paired devices (multi-device support, mirrors iOS) ---

    /** Serial numbers of all paired devices. */
    val pairedDeviceSNs: List<String>
        get() = prefs.getString(KEY_PAIRED_SNS, null)?.let {
            runCatching { gson.fromJson(it, Array<String>::class.java).toList() }.getOrDefault(emptyList())
        } ?: emptyList()

    private fun savePairedDeviceSNs(value: List<String>) =
        prefs.edit().putString(KEY_PAIRED_SNS, gson.toJson(value)).apply()

    /** [SN: display name] cache for paired devices. */
    private val pairedDeviceNames: Map<String, String>
        get() = prefs.getString(KEY_PAIRED_NAMES, null)?.let {
            val type = object : TypeToken<Map<String, String>>() {}.type
            runCatching { gson.fromJson<Map<String, String>>(it, type) }.getOrNull()
        } ?: emptyMap()

    private fun savePairedDeviceNames(value: Map<String, String>) =
        prefs.edit().putString(KEY_PAIRED_NAMES, gson.toJson(value)).apply()

    /** Add a paired device and make it the active device. */
    fun addPairedDevice(sn: String, name: String) {
        val sns = pairedDeviceSNs.toMutableList()
        if (!sns.contains(sn)) sns.add(sn)
        savePairedDeviceSNs(sns)
        savePairedDeviceNames(pairedDeviceNames.toMutableMap().apply { put(sn, name) })
        lastConnectedDeviceSN = sn
    }

    /** Remove a paired device; if it was active, fall back to the first remaining one. */
    fun removePairedDevice(sn: String) {
        val sns = pairedDeviceSNs.toMutableList().apply { remove(sn) }
        savePairedDeviceSNs(sns)
        savePairedDeviceNames(pairedDeviceNames.toMutableMap().apply { remove(sn) })
        if (lastConnectedDeviceSN == sn) lastConnectedDeviceSN = sns.firstOrNull()
    }

    /** Display name for a paired device SN (falls back to the SN itself). */
    fun deviceName(sn: String): String = pairedDeviceNames[sn] ?: sn

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    /**
     * Stable per-install user id sent to plaud-bridge-server as the Plaud client_user_id.
     * "pb_" + UUID satisfies Plaud's 6-120 character requirement. Generated once, then persisted.
     */
    fun getOrCreateUserId(): String {
        userId?.let { return it }
        val id = "pb_${UUID.randomUUID()}"
        userId = id
        return id
    }

    // --- Bridge server settings ---

    /**
     * Bumped whenever the server URL or auth token changes. In-flight uploads capture the
     * generation before the request and discard their result if it changed — an upload that was
     * accepted by the OLD server must not be persisted (and must never trigger a device delete)
     * once the app points at a different server.
     */
    private val serverConfigGenCounter = java.util.concurrent.atomic.AtomicLong(0L)
    val serverConfigGeneration: Long get() = serverConfigGenCounter.get()

    /** Base URL of the self-hosted plaud-bridge-server, e.g. "https://bridge.example.com" (no trailing slash). */
    var serverBaseUrl: String?
        get() = prefs.getString(KEY_SERVER_BASE_URL, null)
        set(value) {
            val normalized = value?.trimEnd('/')
            if (normalized != serverBaseUrl) serverConfigGenCounter.incrementAndGet()
            prefs.edit().putString(KEY_SERVER_BASE_URL, normalized).apply()
        }

    /** Bearer token for the self-hosted server's API. */
    var serverAuthToken: String?
        get() = prefs.getString(KEY_SERVER_AUTH_TOKEN, null)
        set(value) {
            if (value != serverAuthToken) serverConfigGenCounter.incrementAndGet()
            prefs.edit().putString(KEY_SERVER_AUTH_TOKEN, value).apply()
        }

    /**
     * Host whose data (localStorage token, cookies, cache) the Library WebView currently holds.
     * Persisted so the fragment can wipe WebView storage when the configured server host changes
     * — including across app restarts, and after unpair/re-onboarding (clearAll() nulls this,
     * which the fragment treats as "wipe before first load").
     */
    var libraryWebViewHost: String?
        get() = prefs.getString(KEY_LIBRARY_WEBVIEW_HOST, null)
        set(value) = prefs.edit().putString(KEY_LIBRARY_WEBVIEW_HOST, value).apply()

    /** Last SUCCESSFUL automatic app-update check (epoch ms); 0 = never. 24h throttle. */
    var lastUpdateCheckAt: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, value).apply()

    /** Last FAILED automatic app-update check (epoch ms); 0 = none. 1h retry backoff. */
    var lastUpdateCheckFailureAt: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK_FAILURE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_FAILURE_AT, value).apply()

    /** Server host the last auto-check ran against; a different host resets the throttle. */
    var lastUpdateCheckHost: String?
        get() = prefs.getString(KEY_LAST_UPDATE_CHECK_HOST, null)
        set(value) = prefs.edit().putString(KEY_LAST_UPDATE_CHECK_HOST, value).apply()

    /** Server configuration complete (onboarding gate). */
    val isServerConfigured: Boolean
        get() = !serverBaseUrl.isNullOrBlank() && !serverAuthToken.isNullOrBlank()

    // --- Plaud cloud (auth handshake only — audio never goes there) ---

    const val PLAUD_DOMAIN_US = "platform-us.plaud.ai"
    const val PLAUD_DOMAIN_JP = "platform-jp.plaud.ai"

    /** Selectable Plaud platform regions (SDK customDomain / partner API host). */
    val plaudDomains: List<Pair<String, String>> = listOf(
        "US" to PLAUD_DOMAIN_US,
        "JP" to PLAUD_DOMAIN_JP
    )

    /** Plaud platform domain used by the SDK (BLE handshake auth). Restart to apply a change. */
    var plaudDomain: String
        get() = prefs.getString(KEY_PLAUD_DOMAIN, null) ?: PLAUD_DOMAIN_US
        set(value) = prefs.edit().putString(KEY_PLAUD_DOMAIN, value).apply()

    /**
     * Cached Plaud user access token (JWT) fetched from plaud-bridge-server. Refreshed by
     * TokenManager when close to expiry or when the SDK reports an auth failure.
     */
    var cachedPlaudToken: String?
        get() = prefs.getString(KEY_CACHED_PLAUD_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_CACHED_PLAUD_TOKEN, value).apply()

    /** Absolute expiry (epoch seconds) derived from the server's expires_in; 0 = unknown. */
    var cachedPlaudTokenExpiry: Long
        get() = prefs.getLong(KEY_CACHED_PLAUD_TOKEN_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_CACHED_PLAUD_TOKEN_EXPIRY, value).apply()

    /** Delete a recording from the device after a CONFIRMED server upload. Default OFF. */
    var deleteAfterUpload: Boolean
        get() = prefs.getBoolean(KEY_DELETE_AFTER_UPLOAD, false)
        set(value) = prefs.edit().putBoolean(KEY_DELETE_AFTER_UPLOAD, value).apply()

    /** Entered the app from Welcome without pairing a device ("Connect device later"). */
    var hasSkippedOnboarding: Boolean
        get() = prefs.getBoolean("has_skipped_onboarding", false)
        set(value) = prefs.edit().putBoolean("has_skipped_onboarding", value).apply()

    var isAutoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    /**
     * Keep the BLE link alive from a foreground service while the app is in the background, so a
     * recording stopped with the phone in a pocket syncs without opening the app. Default ON: it
     * is the whole point of pairing a recorder to a bridge; the user can opt out in Settings.
     */
    var isBackgroundSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_SYNC, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_SYNC, value).apply()

    /** POST_NOTIFICATIONS (API 33+) has been requested once; never nag again after a denial. */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_PERMISSION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_PERMISSION_ASKED, value).apply()

    /** "Never show again" preference for the WiFi fast-transfer confirmation sheet. */
    var fastTransferNeverShowAgain: Boolean
        get() = prefs.getBoolean(KEY_FAST_TRANSFER_HIDE, false)
        set(value) = prefs.edit().putBoolean(KEY_FAST_TRANSFER_HIDE, value).apply()

    // --- Recording Files (JSON persistence) ---

    val allFiles: List<RecordingFile>
        get() = synchronized(lock) {
            loadFiles().sortedByDescending { it.createdAt }
        }

    fun addFiles(files: List<RecordingFile>) {
        synchronized(lock) {
            val existing = loadFiles().toMutableList()
            val existingKeys = existing.map { it.deviceSN to it.sessionId }.toSet()
            val newFiles = files.filter { (it.deviceSN to it.sessionId) !in existingKeys }
            existing.addAll(newFiles)
            saveFiles(existing)
        }
    }

    fun deleteFile(file: RecordingFile) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.removeAll { it.id == file.id }
            saveFiles(files)
        }
        // Delete the local audio file
        file.localPath?.let { path ->
            File(path).takeIf { it.exists() }?.delete()
        }
    }

    /**
     * Manual rename. Also pins the name: a server title that arrives (or already exists) must not
     * replace what the user typed, see [RecordingFile.displayName].
     */
    fun renameFile(file: RecordingFile, newName: String) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == file.id }?.apply {
                this.name = newName
                this.nameEditedByUser = true
            }
            saveFiles(files)
        }
    }

    fun replaceAllFiles(files: List<RecordingFile>) {
        synchronized(lock) {
            saveFiles(files)
        }
    }

    /** Backfill a real duration (seconds) for a file whose stored duration is 0. */
    fun updateDuration(id: String, durationSec: Long) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == id }?.duration = durationSec
            saveFiles(files)
        }
    }

    /**
     * Persist a transcript (JSON: {"text": ..., "segments": [...], "summary": ..., "title": ...})
     * fetched from the server. The AI title travels inside the same document, so it is captured
     * here too: every path that stores a transcript (detail screen, TitleSyncManager) then picks
     * up the title for free. A document without a usable title leaves [RecordingFile.serverTitle]
     * untouched rather than clearing a title learned earlier.
     */
    fun updateTranscript(id: String, transcriptJSON: String) {
        val title = parseTranscriptTitle(transcriptJSON)
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == id }?.apply {
                this.transcriptJSON = transcriptJSON
                if (title != null) this.serverTitle = title
            }
            saveFiles(files)
        }
    }

    /** Set only the server-side AI title (e.g. when it is learned without a transcript body). */
    fun updateServerTitle(id: String, title: String?) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == id }?.serverTitle = title
            saveFiles(files)
        }
    }

    /**
     * "title" out of a transcript document, or null when absent, JSON null, not a string, blank,
     * or when the document is not a JSON object at all (a bare segment array is a legal transcript
     * shape here, see FileDetailActivity.parseTranscript). Never throws: a malformed title must not
     * stop the transcript itself from being stored.
     */
    internal fun parseTranscriptTitle(transcriptJSON: String): String? = try {
        val obj = org.json.JSONObject(transcriptJSON)
        (obj.opt("title") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /**
     * Composite-key matcher: a recording's identity is EXACTLY (device_sn, session_id) —
     * session ids are NOT globally unique across devices. Blank-SN legacy records form their
     * own namespace: they only ever match a blank requested SN, are never backfilled to a real
     * device, and never stand in for a real device's session. (A wildcard here is precisely how
     * cross-device state corruption — wrong-device deletes, suppressed downloads — happens; the
     * worst case of strict isolation is one redundant re-download of a legacy blank record.)
     */
    private fun matches(file: RecordingFile, deviceSN: String, sessionId: Long): Boolean =
        file.sessionId == sessionId && file.deviceSN == deviceSN

    fun markAsSynced(deviceSN: String, sessionId: Long, localPath: String, duration: Long) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { matches(it, deviceSN, sessionId) }?.apply {
                this.localPath = localPath
                this.syncedAt = System.currentTimeMillis()
                this.duration = duration
            }
            saveFiles(files)
        }
    }

    /** Record a confirmed server upload (validated 201, or validated 200 duplicate:true). */
    fun markAsUploaded(deviceSN: String, sessionId: Long, serverId: String) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { matches(it, deviceSN, sessionId) }?.apply {
                this.uploaded = true
                this.serverId = serverId
                this.uploadedAt = System.currentTimeMillis()
            }
            saveFiles(files)
        }
    }

    /** Set/clear the "delete from device once reconnected" flag (delete-after-upload retry). */
    fun setDeletePendingOnDevice(deviceSN: String, sessionId: Long, pending: Boolean) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { matches(it, deviceSN, sessionId) }?.deletePendingOnDevice = pending
            saveFiles(files)
        }
    }

    /** Uploaded recordings still waiting for a device-side delete, for the given device only. */
    fun pendingDeviceDeletes(deviceSN: String): List<RecordingFile> =
        allFiles.filter { it.deletePendingOnDevice && it.uploaded && it.deviceSN == deviceSN }

    /**
     * Drop all server-side state (uploaded/serverId/transcripts/AI titles) — used when the user
     * points the app at a DIFFERENT server: the old ids mean nothing there, and re-uploads are
     * deduplicated. The title goes too: it was produced by the old server and the new one will
     * generate its own once the recording is re-uploaded. Manual renames are local and survive.
     */
    fun clearServerState() {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.forEach {
                it.uploaded = false
                it.serverId = null
                it.uploadedAt = null
                it.deletePendingOnDevice = false
                it.transcriptJSON = null
                it.serverTitle = null
            }
            saveFiles(files)
        }
    }

    /**
     * Reconcile the index with the filesystem: a record whose exported audio has vanished is no
     * longer synced — clear localPath/syncedAt so the sync flow re-downloads it while the device
     * copy still exists (recordings previously lived in cacheDir, which Android may evict).
     */
    fun clearMissingLocalFiles() {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            var changed = false
            files.forEach { f ->
                val path = f.localPath
                if (path != null && !File(path).exists()) {
                    f.localPath = null
                    f.syncedAt = null
                    changed = true
                }
            }
            if (changed) saveFiles(files)
        }
    }

    /** Store the server-side recording id (e.g. resolved via the lookup endpoint). */
    fun updateServerId(id: String, serverId: String) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == id }?.apply {
                this.serverId = serverId
                this.uploaded = true
            }
            saveFiles(files)
        }
    }

    /** Files synced locally but not yet confirmed uploaded to the bridge server. */
    val pendingUploads: List<RecordingFile>
        get() = allFiles.filter { it.isSynced && !it.uploaded }

    /**
     * Uploaded recordings whose transcript (and with it the AI title) has not been fetched yet.
     * This is the work list for TitleSyncManager; it empties as transcripts are stored, so polling
     * it is free once everything is titled.
     */
    val awaitingTranscript: List<RecordingFile>
        get() = allFiles.filter { !it.serverId.isNullOrBlank() && it.transcriptJSON == null }

    /** Durable storage for exported recordings (filesDir — cacheDir can be evicted by the OS). */
    val exportDir: File
        get() {
            val dir = File(appContext.filesDir, "recordings")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun clearAll() {
        synchronized(lock) {
            saveFiles(emptyList())
        }
        prefs.edit().clear().apply()
        // Delete the audio directory
        File(appContext.filesDir, "audio").takeIf { it.exists() }?.deleteRecursively()
    }

    // --- Private helpers ---

    private fun recordingsFile(): File = File(appContext.filesDir, RECORDINGS_FILE)

    private fun loadFiles(): List<RecordingFile> {
        val file = recordingsFile()
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val type = object : TypeToken<List<RecordingFile>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            // Quarantine the corrupt index instead of silently treating it as empty — the next
            // save would otherwise overwrite it and permanently lose upload/server state.
            try {
                file.copyTo(File(file.parentFile, "$RECORDINGS_FILE.bak"), overwrite = true)
            } catch (_: Exception) { }
            emptyList()
        }
    }

    /** Write via temp file + rename so a crash mid-write never truncates the index. */
    private fun saveFiles(files: List<RecordingFile>) {
        val json = gson.toJson(files)
        val target = recordingsFile()
        val tmp = File(target.parentFile, "$RECORDINGS_FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(target)) {
            // Rename failed (rare). NEVER fall back to a direct write — a crash mid-write would
            // truncate the index, which is the exact loss the temp file exists to prevent. Keep
            // the previous index intact (this update is lost, but re-derivable) and leave the
            // temp file behind for inspection.
            org.plaudbridge.app.common.AppLog.w(
                "RecordingStore", "recordings index rename failed — keeping previous index"
            )
        }
    }
}
