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
 *
 * The snapshot is stamped with the RecordingStore.serverConfigGeneration it was fetched under.
 * Rows belong to one server: after the URL or token changes they are dropped at once rather than
 * shown until the new server answers, and an answer from a request that was in flight across the
 * switch is discarded, never published as the new server's list (same guard UploadManager uses).
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

    /** Server configuration generation the current snapshot was fetched under; null = none yet. */
    @Volatile
    private var snapshotGeneration: Long? = null

    /**
     * Fetch the list off the main thread and, on success, replace the snapshot. Without a
     * configured server there is nothing to ask and the snapshot is cleared so rows from a
     * previously configured server cannot linger. The generation is captured before the request
     * and checked after it: a response that crossed a server switch is dropped and reported as
     * an error, so the caller refreshes again instead of hiding the wrong server's rows.
     */
    suspend fun refresh(): ApiClient.ListResult {
        val generation = RecordingStore.serverConfigGeneration
        dropSnapshotIfStale(generation)
        if (!RecordingStore.isServerConfigured) {
            _server.value = emptyList()
            return ApiClient.ListResult.Error("server not configured")
        }
        val result = withContext(Dispatchers.IO) {
            try { listSource.list() } catch (e: Exception) { ApiClient.ListResult.Error(e.message ?: "network error") }
        }
        val after = RecordingStore.serverConfigGeneration
        if (after != generation) {
            // The rows on screen (if any) belong to the configuration this request started
            // under, which is no longer the current one: drop them now, not at the next refresh.
            dropSnapshotIfStale(after)
            return ApiClient.ListResult.Error("server configuration changed during refresh")
        }
        if (result is ApiClient.ListResult.Ok) {
            _server.value = result.recordings
            snapshotGeneration = generation
        }
        return result
    }

    /**
     * Blank the snapshot the moment it is seen to belong to another server configuration, so a
     * screen refreshing after a switch never shows the old server's rows while it waits.
     */
    private fun dropSnapshotIfStale(currentGeneration: Long) {
        val stamped = snapshotGeneration ?: return
        if (stamped != currentGeneration) {
            _server.value = emptyList()
            snapshotGeneration = null
        }
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
        snapshotGeneration = null
        listSource = ListSource { ApiClient.listRecordings() }
    }
}
