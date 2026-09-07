package org.plaudbridge.app.ui.recordings

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore

/**
 * Process-wide snapshot of the server's recording list, shared by the Recordings tab and Home.
 *
 * The list is fetched live (tab shown, resume, pull) and kept only in memory: the server owns
 * its recordings, and writing them into RecordingStore would mix them into the phone's sync
 * index. Holding the last good answer here means a failed refresh (server down, phone offline)
 * leaves the rows the user was just reading on screen instead of blanking them, and switching
 * tabs shows the list immediately while a refresh runs.
 */
object RecordingsRepository {

    /** Seam over ApiClient.listRecordings so screens can be driven without a network stack. */
    fun interface ListSource {
        fun list(): ApiClient.ListResult
    }

    @VisibleForTesting
    var listSource: ListSource = ListSource { ApiClient.listRecordings() }

    private val _server = MutableStateFlow<List<ServerRecording>>(emptyList())

    /** Last successfully fetched server list; empty until the first refresh succeeds. */
    val server: StateFlow<List<ServerRecording>> = _server.asStateFlow()

    /**
     * Fetch the list off the main thread and, on success, replace the snapshot. Without a
     * configured server there is nothing to ask and the snapshot is cleared so rows from a
     * previously configured server cannot linger.
     */
    suspend fun refresh(): ApiClient.ListResult {
        if (!RecordingStore.isServerConfigured) {
            _server.value = emptyList()
            return ApiClient.ListResult.Error("server not configured")
        }
        val result = withContext(Dispatchers.IO) {
            try { listSource.list() } catch (e: Exception) { ApiClient.ListResult.Error(e.message ?: "network error") }
        }
        if (result is ApiClient.ListResult.Ok) _server.value = result.recordings
        return result
    }

    /** Drop one recording from the snapshot right after a delete, before the next refresh lands. */
    fun remove(serverId: String) {
        _server.value = _server.value.filterNot { it.id == serverId }
    }

    /** Swap one recording (after a rename) so the row updates without a round trip. */
    fun replace(recording: ServerRecording) {
        _server.value = _server.value.map { if (it.id == recording.id) recording else it }
    }

    @VisibleForTesting
    fun reset() {
        _server.value = emptyList()
        listSource = ListSource { ApiClient.listRecordings() }
    }
}
