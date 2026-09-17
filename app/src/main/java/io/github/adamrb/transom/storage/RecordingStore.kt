package io.github.adamrb.transom.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.adamrb.transom.models.RecordingFile
import java.io.File
import java.util.UUID

object RecordingStore {

    private const val PREFS_NAME = "transom_prefs"
    private const val KEY_LAST_CONNECTED_SN = "last_connected_device_sn"
    private const val KEY_PAIRED_SNS = "paired_device_sns"
    private const val KEY_PAIRED_NAMES = "paired_device_names"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_CACHED_PLAUD_TOKEN_USER_ID = "cached_plaud_token_user_id"
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
    private const val KEY_ADVANCED_SETTINGS_EXPANDED = "advanced_settings_expanded"
    private const val KEY_APPEARANCE = "appearance"
    private const val KEY_HIDDEN_SESSIONS = "hidden_sessions"
    private const val KEY_WATCHED_AUTOMATIONS = "watched_automations"
    private const val KEY_ANNOUNCED_AUTOMATIONS = "announced_automations"
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

    /**
     * Remove a paired device; if it was active, fall back to the first remaining one. The
     * device's delete tombstones go with it: they exist to stop THIS pairing from re-syncing
     * recordings the user deleted, and a re-pair is a fresh start where whatever is on the
     * recorder is what the user wants to see.
     */
    fun removePairedDevice(sn: String) {
        val sns = pairedDeviceSNs.toMutableList().apply { remove(sn) }
        savePairedDeviceSNs(sns)
        savePairedDeviceNames(pairedDeviceNames.toMutableMap().apply { remove(sn) })
        if (lastConnectedDeviceSN == sn) lastConnectedDeviceSN = sns.firstOrNull()
        clearHiddenSessions(sn)
    }

    /** Display name for a paired device SN (falls back to the SN itself). */
    fun deviceName(sn: String): String = pairedDeviceNames[sn] ?: sn

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    /**
     * Stable per-install user id sent to transom-server as the Plaud client_user_id.
     * "pb_" + UUID satisfies Plaud's 6-120 character requirement. Generated once, then persisted.
     */
    fun getOrCreateUserId(): String {
        userId?.let { return it }
        val id = "pb_${UUID.randomUUID()}"
        userId = id
        return id
    }

    /** Plaud accepts 6 to 120 characters for a client user id; we also refuse whitespace. */
    fun isValidUserId(id: String): Boolean = id.length in 6..120 && id.none { it.isWhitespace() }

    /**
     * Adopt the user id of a previous install (another phone, or the app before it changed
     * package name) so a recorder bound to that id keeps working without an unpair. The cached
     * Plaud token belongs to the old id and is dropped; the SDK reads the id at init, so the
     * caller asks for an app restart. Returns false (and changes nothing) for an invalid id.
     */
    @Synchronized
    fun adoptUserId(id: String): Boolean {
        val value = id.trim()
        if (!isValidUserId(value)) return false
        if (value == userId) return true
        // One transaction: the new id and the emptied token cache land together. commit(), not
        // apply(): the caller exits the process right after, so the write must be on disk.
        return prefs.edit()
            .putString(KEY_USER_ID, value)
            .remove(KEY_CACHED_PLAUD_TOKEN)
            .remove(KEY_CACHED_PLAUD_TOKEN_USER_ID)
            .putLong(KEY_CACHED_PLAUD_TOKEN_EXPIRY, 0L)
            .commit()
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

    /** Base URL of the self-hosted transom-server, e.g. "https://bridge.example.com" (no trailing slash). */
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
     * Cached Plaud user access token (JWT) fetched from transom-server. Refreshed by
     * TokenManager when close to expiry or when the SDK reports an auth failure.
     */
    var cachedPlaudToken: String?
        get() = prefs.getString(KEY_CACHED_PLAUD_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_CACHED_PLAUD_TOKEN, value).apply()

    /** Absolute expiry (epoch seconds) derived from the server's expires_in; 0 = unknown. */
    var cachedPlaudTokenExpiry: Long
        get() = prefs.getLong(KEY_CACHED_PLAUD_TOKEN_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_CACHED_PLAUD_TOKEN_EXPIRY, value).apply()

    /**
     * The user id the cached token was minted for. A token tagged with another id (the user
     * adopted a previous install's id after the fetch started) is never handed out. Null on
     * tokens cached by builds before this field existed.
     */
    var cachedPlaudTokenUserId: String?
        get() = prefs.getString(KEY_CACHED_PLAUD_TOKEN_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_CACHED_PLAUD_TOKEN_USER_ID, value).apply()

    /**
     * Token, owner and expiry in ONE preferences transaction, so two writers (a Settings
     * verification and a refresh, say) can never interleave into a token tagged with the wrong
     * owner. [userId] null with a non-null token means "owner unknown" (legacy cache).
     */
    @Synchronized
    fun setCachedPlaudToken(token: String?, userId: String?, expirySec: Long): Boolean {
        // A token minted for an identity this install no longer has (the user adopted another
        // id while the request was in flight) is refused at the write boundary.
        val current = this.userId
        if (userId != null && current != null && userId != current) return false
        prefs.edit()
            .putString(KEY_CACHED_PLAUD_TOKEN, token)
            .putString(KEY_CACHED_PLAUD_TOKEN_USER_ID, userId)
            .putLong(KEY_CACHED_PLAUD_TOKEN_EXPIRY, expirySec)
            .apply()
        return true
    }

    /** Cached token with its owner and expiry, read together; null if none or owned by another id. */
    data class CachedToken(val token: String, val owner: String?, val expirySec: Long)

    @Synchronized
    fun cachedPlaudTokenSnapshot(): CachedToken? {
        val token = cachedPlaudToken ?: return null
        val owner = cachedPlaudTokenUserId
        if (owner != null && owner != userId) return null
        return CachedToken(token, owner, cachedPlaudTokenExpiry)
    }

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

    /**
     * Recordings whose automations the app is waiting on, as AutomationWatcher's JSON. Persisted
     * so a WorkManager-started process after process death still knows what to poll.
     */
    var watchedAutomationsJson: String?
        get() = prefs.getString(KEY_WATCHED_AUTOMATIONS, null)
        set(value) = prefs.edit().putString(KEY_WATCHED_AUTOMATIONS, value).apply()

    /** Hand-offs and router runs already announced, as AutomationWatcher's JSON (bounded there). */
    var announcedAutomationsJson: String?
        get() = prefs.getString(KEY_ANNOUNCED_AUTOMATIONS, null)
        set(value) = prefs.edit().putString(KEY_ANNOUNCED_AUTOMATIONS, value).apply()

    /** "Never show again" preference for the WiFi fast-transfer confirmation sheet. */
    var fastTransferNeverShowAgain: Boolean
        get() = prefs.getBoolean(KEY_FAST_TRANSFER_HIDE, false)
        set(value) = prefs.edit().putBoolean(KEY_FAST_TRANSFER_HIDE, value).apply()

    /**
     * The Settings "Advanced" section (Plaud region, user id, SDK logs) is open. Collapsed by
     * default: those rows are plumbing most users never need, but someone who opened them once
     * is probably debugging and should not have to reopen them on every visit.
     */
    var advancedSettingsExpanded: Boolean
        get() = prefs.getBoolean(KEY_ADVANCED_SETTINGS_EXPANDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ADVANCED_SETTINGS_EXPANDED, value).apply()

    /** Settings > Appearance: System (default), Light or Dark; see [io.github.adamrb.transom.common.Appearance]. */
    var appearance: io.github.adamrb.transom.common.Appearance
        get() = io.github.adamrb.transom.common.Appearance.fromStorage(prefs.getString(KEY_APPEARANCE, null))
        set(value) = prefs.edit().putString(KEY_APPEARANCE, value.storageKey).apply()

    // --- Delete tombstones ---

    /**
     * A recording the user deleted, by the recorder's own identity for it. Persisted (prefs,
     * small JSON list) because the recorder still holds the file whenever delete-after-upload
     * is off: without a tombstone the next device file list would look like a brand-new
     * session, and the app would download and upload the recording the user just deleted.
     *
     * [untilServerSwitch] marks the weaker flavour written by Remove from phone for a legacy
     * entry whose own identity cannot carry the flag (see RecordingActions.removeFromPhone). It
     * means "the server keeps the copy", so [clearServerState] drops it along with the
     * removedFromPhone flags; a Delete tombstone is permanent and always wins over it.
     */
    private data class HiddenSession(
        val deviceSN: String,
        val sessionId: Long,
        val untilServerSwitch: Boolean = false
    )

    private fun loadHiddenSessions(): List<HiddenSession> =
        prefs.getString(KEY_HIDDEN_SESSIONS, null)?.let {
            runCatching { gson.fromJson(it, Array<HiddenSession>::class.java).toList() }.getOrNull()
        } ?: emptyList()

    private fun saveHiddenSessions(value: List<HiddenSession>) =
        prefs.edit().putString(KEY_HIDDEN_SESSIONS, gson.toJson(value)).apply()

    /** (deviceSN, sessionId) pairs the sync flows must never re-add or re-download. */
    val hiddenSessions: Set<Pair<String, Long>>
        get() = synchronized(lock) { loadHiddenSessions().map { it.deviceSN to it.sessionId }.toSet() }

    /**
     * Suppress this session; idempotent, exact-match keyed (see [matches]). Permanent by default
     * (the user deleted the recording); [untilServerSwitch] for the Remove from phone flavour.
     * A permanent request upgrades an existing switch-scoped entry, never the other way round.
     */
    fun hideSession(deviceSN: String, sessionId: Long, untilServerSwitch: Boolean = false) {
        synchronized(lock) {
            val hidden = loadHiddenSessions()
            val existing = hidden.find { it.deviceSN == deviceSN && it.sessionId == sessionId }
            if (existing != null && (!existing.untilServerSwitch || untilServerSwitch)) return
            saveHiddenSessions(
                hidden.filterNot { it === existing } + HiddenSession(deviceSN, sessionId, untilServerSwitch)
            )
        }
    }

    fun isHidden(deviceSN: String, sessionId: Long): Boolean =
        synchronized(lock) { loadHiddenSessions().any { it.deviceSN == deviceSN && it.sessionId == sessionId } }

    /**
     * Forget the Delete tombstones of one device (unpair). The switch-scoped flavour stays: it
     * stands in for a removedFromPhone flag, and those flags live on index entries that unpairing
     * does not touch, so the two must agree or a re-pair would download the removed recording
     * next to its still-flagged legacy entry. A server switch clears both, see [clearServerState].
     */
    fun clearHiddenSessions(deviceSN: String) {
        synchronized(lock) {
            saveHiddenSessions(loadHiddenSessions().filterNot { it.deviceSN == deviceSN && !it.untilServerSwitch })
        }
    }

    // --- Recording Files (JSON persistence) ---

    val allFiles: List<RecordingFile>
        get() = synchronized(lock) {
            loadFiles().sortedByDescending { it.createdAt }
        }

    /**
     * Add index entries for sessions not known yet. Deleted sessions ([hideSession]) are
     * refused here as well as in the sync flows, so no code path can resurrect one by mistake.
     */
    fun addFiles(files: List<RecordingFile>) {
        synchronized(lock) {
            val existing = loadFiles().toMutableList()
            val existingKeys = existing.map { it.deviceSN to it.sessionId }.toSet()
            val hidden = loadHiddenSessions().map { it.deviceSN to it.sessionId }.toSet()
            val newFiles = files.filter {
                val key = it.deviceSN to it.sessionId
                key !in existingKeys && key !in hidden
            }
            existing.addAll(newFiles)
            saveFiles(existing)
        }
    }

    /**
     * The user deleted this recording: drop the index entry and the audio, and tombstone the
     * session so the sync flows do not bring it back from the recorder (see [HiddenSession]).
     * A legacy blank-SN entry can only be tombstoned under its blank identity, which the
     * recorder's real serial never matches; Delete must still work, and guessing the serial from
     * the connected device is the wildcard this store forbids (see [matches]), so such a
     * recording may sync once more as a fresh entry. RecordingActions.delete tombstones the
     * server row's real identity as well whenever one is on screen, which closes the common case.
     */
    fun deleteFile(file: RecordingFile) {
        val storedPath: String?
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            storedPath = files.find { it.id == file.id }?.localPath
            files.removeAll { it.id == file.id }
            saveFiles(files)
            hideSession(file.deviceSN, file.sessionId)
        }
        deleteAudio(storedPath, file.localPath)
    }

    /**
     * Remove exported audio. Both the path the index holds now and the one the caller's copy of
     * the record holds are deleted: the UI can be working from a snapshot taken before a
     * re-export (WiFi) moved the entry to a new file, and deleting only the caller's path would
     * leave the newer MP3 orphaned in storage.
     */
    private fun deleteAudio(vararg paths: String?) {
        paths.filterNotNull().toSet().forEach { path ->
            File(path).takeIf { it.exists() }?.delete()
        }
    }

    /**
     * "Remove from phone": drop the audio but keep the entry, flagged, so the row stays linked
     * to its server copy and the sync flows know this session is not missing, it is unwanted
     * here (a plain localPath = null would read as "evicted, download again").
     */
    fun removeFromPhone(file: RecordingFile) {
        var storedPath: String? = null
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == file.id }?.apply {
                storedPath = this.localPath
                this.localPath = null
                this.syncedAt = null
                this.removedFromPhone = true
            }
            saveFiles(files)
        }
        deleteAudio(storedPath, file.localPath)
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

    /** One session as the recorder lists it (BLE or WiFi file list). */
    data class ListedSession(val sessionId: Long, val durationSec: Long, val createdAt: Long)

    /** Outcome of [reconcileDeviceList]. */
    data class DeviceListReconciliation(
        /** The index as saved. */
        val all: List<RecordingFile>,
        /** Listed sessions that need a download (not synced, not suppressed). */
        val newSessionIds: List<Long>,
        /** Listed sessions that must not be downloaded: deleted, or removed from the phone. */
        val suppressedSessionIds: Set<Long>
    )

    /**
     * Merge a device file list for [deviceSN] into the index, in one step under the store lock so
     * a Remove from phone, rename or delete landing while the list is processed cannot be
     * overwritten by a stale read (both sync paths used to read, compute and replace outside it).
     *
     * Rules: entries with audio, and entries flagged removedFromPhone, are kept whatever the
     * list says (the removed ones would otherwise lose their server link and be re-created as new
     * below). Listed sessions already synced from THIS device are skipped; composite (SN, session)
     * matching, so a session id synced from another device is still downloaded, and blank-SN
     * legacy entries never stand in for a real device's session (that wildcard is exactly how
     * cross-device suppression happened; a legacy blank record may cost one redundant download).
     * Sessions the user deleted (tombstoned) or removed from the phone are neither re-added nor
     * queued: the recorder still lists them whenever delete-after-upload is off. Every other
     * listed session gets an entry: the existing audio-less one for that (SN, session) when there
     * is one, so a pinned name, marks and upload state survive an evicted file or a lifted
     * removal, else a fresh "Untitled Recording". Entries for sessions the recorder no longer
     * lists are dropped, as before.
     */
    fun reconcileDeviceList(deviceSN: String, listed: List<ListedSession>): DeviceListReconciliation {
        synchronized(lock) {
            val index = loadFiles()
            val kept = index.filter { it.isSynced || it.removedFromPhone }
            val keptIds = kept.map { it.id }.toHashSet()
            val reusable = index
                .filter { it.id !in keptIds && it.deviceSN.isNotBlank() }
                .associateBy { it.deviceSN to it.sessionId }
            val hidden = loadHiddenSessions().filter { it.deviceSN == deviceSN }.map { it.sessionId }
            val removed = index.filter { it.removedFromPhone && it.deviceSN == deviceSN }.map { it.sessionId }
            val suppressed = (hidden + removed).toSet()
            fun alreadySynced(sid: Long) = kept.any { it.sessionId == sid && it.deviceSN == deviceSN }
            val fresh = listed.filter { !alreadySynced(it.sessionId) && it.sessionId !in suppressed }
            val deviceFiles = fresh.map { s ->
                reusable[deviceSN to s.sessionId] ?: RecordingFile(
                    id = UUID.randomUUID().toString(),
                    sessionId = s.sessionId,
                    deviceSN = deviceSN,
                    name = "Untitled Recording",
                    duration = s.durationSec,
                    createdAt = s.createdAt,
                    syncedAt = null,
                    localPath = null,
                    summaryText = null,
                    transcriptJSON = null
                )
            }
            val all = kept + deviceFiles
            saveFiles(all)
            return DeviceListReconciliation(all, fresh.map { it.sessionId }, suppressed)
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

    /**
     * Store the normalized button-press marks read off the device. Storing resets [RecordingFile.marksSynced]
     * only when the value actually changed: a re-read that yields the same list must not cause a
     * redundant PATCH, while a different list (a device that answered partially the first time)
     * must reach the server again.
     */
    fun updateMarks(id: String, marks: List<Double>) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == id }?.apply {
                if (this.marks != marks) {
                    this.marks = marks
                    this.marksSynced = false
                }
            }
            saveFiles(files)
        }
    }

    /**
     * Record that the server now holds the given marks. The marks are passed in (not re-read from
     * the record) so a device read that landed while the request was in flight cannot be
     * wrongly flagged as synced: the flag is only set when the stored list is still the one sent.
     */
    fun markMarksSynced(id: String, sentMarks: List<Double>) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == id }?.apply {
                if (this.marks == sentMarks) this.marksSynced = true
            }
            saveFiles(files)
        }
    }

    /** Recordings from [deviceSN] whose marks were never read (null); the BLE read work list. */
    fun awaitingMarksRead(deviceSN: String): List<RecordingFile> =
        allFiles.filter { it.deviceSN == deviceSN && it.marks == null }

    /**
     * Recordings the server knows (serverId) whose marks are read but not yet delivered; the
     * PATCH work list. Empties as marks are synced, so polling it costs one store read.
     */
    val awaitingMarksSync: List<RecordingFile>
        get() = allFiles.filter { !it.serverId.isNullOrBlank() && it.marks != null && !it.marksSynced }

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

    /**
     * Record a finished download. A download that was already queued when the user chose Remove
     * from phone (BLE list built earlier, WiFi export in progress) still completes; honouring the
     * choice means discarding that audio, not attaching it and silently undoing the removal.
     */
    fun markAsSynced(deviceSN: String, sessionId: Long, localPath: String, duration: Long) {
        var discard = false
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            val file = files.find { matches(it, deviceSN, sessionId) }
            if (file?.removedFromPhone == true) {
                discard = true
            } else {
                file?.apply {
                    this.localPath = localPath
                    this.syncedAt = System.currentTimeMillis()
                    this.duration = duration
                }
                saveFiles(files)
            }
        }
        if (discard) {
            io.github.adamrb.transom.common.AppLog.i(
                "RecordingStore", "Discarding downloaded audio for a recording removed from the phone (sessionId=$sessionId)"
            )
            File(localPath).takeIf { it.exists() }?.delete()
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
     * "Removed from phone" is lifted as well: it meant "the OLD server keeps the copy", and the
     * new server has none, so the entry goes back to being an ordinary not-yet-downloaded
     * session that the next sync fetches and uploads like every other recording. Left set, it
     * would be a row with no audio and no server that nothing could ever repair.
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
                it.removedFromPhone = false
                // The marks themselves are device facts and stay; the NEW server has not seen
                // them, so they are re-sent with the re-upload.
                it.marksSynced = false
            }
            saveFiles(files)
            // Same reasoning as removedFromPhone above; Delete tombstones are permanent.
            saveHiddenSessions(loadHiddenSessions().filterNot { it.untilServerSwitch })
            // The ids in these belong to the old server; polling them on the new one is noise.
            watchedAutomationsJson = null
            announcedAutomationsJson = null
        }
    }

    /**
     * Reconcile the index with the filesystem: a record whose exported audio has vanished is no
     * longer synced — clear localPath/syncedAt so the sync flow re-downloads it while the device
     * copy still exists (recordings previously lived in cacheDir, which Android may evict).
     * Entries the user removed from the phone are skipped: their audio is absent on purpose,
     * and a deleted recording has no entry at all, so neither is ever queued for a re-download.
     */
    fun clearMissingLocalFiles() {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            var changed = false
            files.forEach { f ->
                if (f.removedFromPhone) return@forEach
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

    /**
     * Swap in the id the server now holds for this recording (TitleSyncManager's 404 repair).
     * Unlike [updateServerId] this also forgets that the marks were delivered: they went to the
     * OLD record, and the rebuilt or re-registered one has never seen them, so they are PATCHed
     * again. The marks themselves are device facts and stay.
     */
    fun replaceServerId(id: String, newServerId: String) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == id }?.apply {
                this.serverId = newServerId
                this.uploaded = true
                this.marksSynced = false
            }
            saveFiles(files)
        }
    }

    /**
     * Forget a server id the server no longer recognises (404 on the transcript AND on the
     * lookup by device_sn + session_id). Only the id goes: the upload did happen, so the file
     * must not be queued for another upload on the strength of one 404. Without an id the file
     * leaves every server-side work list; the detail screen's lookup (or a server switch, which
     * resets upload state) re-resolves it if the server has it again. Compares against
     * [staleServerId] so an id resolved concurrently by another path is never wiped. "Removed
     * from phone" is lifted too, as in [clearServerState]: it meant "the server keeps the copy",
     * and the server just said it has none, so the entry must not stay a row with neither audio
     * nor a server recording; the next sync treats it as an ordinary session again. For the same
     * reason a deferred delete-after-upload is cancelled: it was earned by an upload the server
     * no longer holds, and running it now could destroy the last copy of the recording.
     */
    fun clearStaleServerId(id: String, staleServerId: String) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            val file = files.find { it.id == id && it.serverId == staleServerId } ?: return
            file.serverId = null
            file.removedFromPhone = false
            file.deletePendingOnDevice = false
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

    /**
     * Uploaded recordings whose transcript was cached BEFORE the server produced titles (the JSON
     * has no "title" key at all) and that the user has not renamed by hand. TitleSyncManager
     * refetches these once per process so phones that synced early still pick up AI titles.
     * A transcript that carries a "title" key (even null) is current and is left alone.
     */
    val cachedWithoutTitle: List<RecordingFile>
        get() = allFiles.filter {
            !it.serverId.isNullOrBlank() && it.transcriptJSON != null &&
                it.serverTitle.isNullOrBlank() && !it.nameEditedByUser &&
                !transcriptHasTitleKey(it.transcriptJSON!!)
        }

    private fun transcriptHasTitleKey(json: String): Boolean =
        try { org.json.JSONObject(json).has("title") } catch (_: Exception) { true } // unparseable: do not loop on it

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
        // Includes the delete tombstones (KEY_HIDDEN_SESSIONS): a fresh start must not carry
        // over a list of recordings to ignore.
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
            io.github.adamrb.transom.common.AppLog.w(
                "RecordingStore", "recordings index rename failed — keeping previous index"
            )
        }
    }
}
