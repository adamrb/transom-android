package org.plaudbridge.app.ui.recordings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.plaudbridge.app.managers.SyncManagerProtocol
import org.plaudbridge.app.net.ApiClient

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
 * upload, never from a user-facing delete.
 */
object RecordingActions {

    /**
     * Delete everywhere the app controls: on the server when the recording exists there, then
     * the phone's copy. A server refusal (auth, network) keeps the local copy so the user is not
     * left with a recording that exists on the server but is gone from the phone; 404 means the
     * server copy is already gone, which is the outcome we wanted, so the local delete proceeds.
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
        item.local?.let { local.deleteFile(it) }
        return ApiClient.ActionResult.Ok
    }

    /** Drop the phone's copy (index entry and audio) and keep the server's. No-op without a local file. */
    fun removeFromPhone(item: RecordingItem, local: SyncManagerProtocol): Boolean {
        val file = item.local ?: return false
        local.deleteFile(file)
        return true
    }

    /**
     * Rename on the server when the recording is there (the server title wins in every list), and
     * mirror the new name into the phone's index so the row reads the same offline. Without a
     * server id it is a plain local rename.
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
                is ApiClient.RecordingResult.Error -> return ApiClient.ActionResult.Error(result.message)
            }
        }
        item.local?.let { local.renameFile(it, title) }
        return ApiClient.ActionResult.Ok
    }
}
