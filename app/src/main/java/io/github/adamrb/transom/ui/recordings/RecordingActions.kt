package io.github.adamrb.transom.ui.recordings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.adamrb.transom.managers.SyncManagerProtocol
import io.github.adamrb.transom.managers.TitleSyncManager
import io.github.adamrb.transom.net.ApiClient
import io.github.adamrb.transom.storage.RecordingStore

/**
 * The server writes a recording row can trigger. An interface (with [ApiServerRecordingActions]
 * as the production default) so the Recordings list and the detail screen can be exercised in
 * Robolectric without a network stack.
 */
interface ServerRecordingActions {
    suspend fun rename(id: String, title: String): ApiClient.RecordingResult
    suspend fun retranscribe(id: String): ApiClient.ActionResult
    suspend fun delete(id: String): ApiClient.ActionResult
}

/** Production implementation: the blocking ApiClient calls on Dispatchers.IO. */
object ApiServerRecordingActions : ServerRecordingActions {
    override suspend fun rename(id: String, title: String) =
        withContext(Dispatchers.IO) { ApiClient.renameRecording(id, title) }
    override suspend fun retranscribe(id: String) = withContext(Dispatchers.IO) { ApiClient.retranscribe(id) }
    override suspend fun delete(id: String) = withContext(Dispatchers.IO) { ApiClient.deleteRecording(id) }
}

/**
 * What Rename, Remove from phone and Delete mean for a merged [RecordingItem], in one place so
 * the list's long-press sheet and the detail screen's menu cannot disagree. None of these ever
 * touch the recorder: the on-device copy is only removed by the sync flows after a confirmed
 * upload, never from a user-facing delete. Because the recorder keeps its copy, both Delete and
 * Remove from phone leave a marker behind (a tombstone, or the flagged index entry) so the next
 * sync does not treat the session as new and bring the recording straight back.
 */
object RecordingActions {

    /**
     * Delete everywhere the app controls: on the server when the recording exists there, then
     * the phone's copy. A server refusal (auth, network) keeps the local copy so the user is not
     * left with a recording that exists on the server but is gone from the phone; 404 means the
     * server copy is already gone, which is the outcome we wanted, so the local delete proceeds.
     * The session is tombstoned under every identity known for it: the phone's entry (as part of
     * the local delete) and the server row's device_sn + session_id, so a server-only row deleted
     * here does not get re-uploaded by this phone the next time the recorder is synced, and a
     * legacy blank-SN phone entry does not leave the real identity unsuppressed.
     */
    suspend fun delete(
        item: RecordingItem,
        server: ServerRecordingActions,
        local: SyncManagerProtocol
    ): ApiClient.ActionResult {
        val serverId = item.serverId
        if (serverId != null) {
            when (val result = server.delete(serverId)) {
                is ApiClient.ActionResult.Ok, is ApiClient.ActionResult.NotFound -> {}
                else -> return result
            }
            RecordingsRepository.remove(serverId)
        }
        val rec = item.server
        val sessionId = rec?.sessionId
        if (rec != null && sessionId != null && rec.deviceSn.isNotBlank()) {
            RecordingStore.hideSession(rec.deviceSn, sessionId)
        }
        item.local?.let { local.deleteFile(it) } // the store tombstones the phone's identity too
        return ApiClient.ActionResult.Ok
    }

    /**
     * Drop the phone's audio and keep the server's copy: the index entry stays, flagged, so the
     * row remains server-backed and the session is not downloaded again. Without a server copy
     * there is nothing for the row to fall back to, so the entry goes too (a full local delete,
     * tombstoned); the UI only offers this action for server-backed rows, see
     * [RecordingItem.canRemoveFromPhone]. No-op without a local file.
     *
     * The flag lives on the phone entry, keyed by ITS (deviceSN, sessionId). A legacy entry with
     * a blank SN cannot be backfilled (the SN is immutable identity), so the server row's real
     * identity is suppressed as well, with the switch-scoped tombstone flavour so that a later
     * server switch lifts it together with the flag; otherwise the next sync of that recorder
     * would not find the session suppressed and would download it again. When neither side
     * knows the real identity (legacy entry, server row not loaded) the action is refused and
     * returns false: removing the audio would only have it downloaded back next to the legacy
     * row, as a duplicate.
     */
    fun removeFromPhone(item: RecordingItem, local: SyncManagerProtocol): Boolean {
        val file = item.local ?: return false
        val serverId = item.serverId
        if (serverId == null) {
            local.deleteFile(file)
            return true
        }
        val identity = item.suppressibleIdentity ?: return false
        // A row matched by (SN, session) may know the server id only through the server list.
        // The kept entry is about to become the row's only link to the server whenever that
        // list is unavailable (offline, restart), so the id is written to it first.
        if (file.serverId.isNullOrBlank()) RecordingStore.updateServerId(file.id, serverId)
        local.removeFromPhone(file)
        if (identity != file.deviceSN to file.sessionId) {
            RecordingStore.hideSession(identity.first, identity.second, untilServerSwitch = true)
        }
        return true
    }

    /**
     * Ask the server to transcribe again. On success the background title sync is told to stop
     * treating this id as a settled miss (a 404 it decided not to ask about again) and kicked, so
     * a recording that has no cached transcript yet is polled now (409 until Ready) rather than
     * at the next unrelated resume. A recording that already caches a transcript is not the title
     * sync's concern: the detail screen re-reads the server copy after this call and the lists
     * take title and status from the server list, as before.
     */
    suspend fun retranscribe(serverId: String, server: ServerRecordingActions): ApiClient.ActionResult {
        val result = server.retranscribe(serverId)
        if (result is ApiClient.ActionResult.Ok) {
            TitleSyncManager.reopen(serverId)
            TitleSyncManager.kick()
        }
        return result
    }

    /**
     * Rename on the server when the recording is there, and mirror the new name into the phone's
     * index (pinned, see RecordingFile.nameEditedByUser) so the row reads the same offline and
     * the manual name outlives any later server title. Without a server id it is a plain local
     * rename; UploadManager pushes the pinned name to the server once the upload lands.
     */
    suspend fun rename(
        item: RecordingItem,
        title: String,
        server: ServerRecordingActions,
        local: SyncManagerProtocol
    ): ApiClient.ActionResult {
        val serverId = item.serverId
        if (serverId != null) {
            when (val result = server.rename(serverId, title)) {
                is ApiClient.RecordingResult.Ok -> RecordingsRepository.replace(result.recording)
                is ApiClient.RecordingResult.NotFound -> return ApiClient.ActionResult.NotFound
                is ApiClient.RecordingResult.AuthError -> return ApiClient.ActionResult.AuthError(result.code)
                is ApiClient.RecordingResult.Error -> return ApiClient.ActionResult.Error(result.message, detail = result.detail)
            }
        }
        item.local?.let { local.renameFile(it, title) }
        return ApiClient.ActionResult.Ok
    }
}
