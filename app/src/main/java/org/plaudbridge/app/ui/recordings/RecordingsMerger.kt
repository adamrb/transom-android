package org.plaudbridge.app.ui.recordings

import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.ServerRecording

/**
 * Joins the phone's sync index with the server's recording list into one row per recording.
 *
 * Pure Kotlin so the pairing rules are unit-testable. A recording is the same recording when
 * the phone remembers the server id it got back from the upload, or, before that id is known
 * (upload still queued, or a legacy index), when both sides name the same (device SN, session
 * id): that pair is the recorder's own identity for a recording and is what the server stores
 * from the upload metadata. Every server row appears exactly once; every unmatched local file
 * appears once as a phone-only row.
 */
object RecordingsMerger {

    /**
     * [failedUploads] are the phone entries (by [RecordingFile.id]) whose last upload attempt
     * failed, from UploadManager.failedUploads; they mark their rows so the list can say so.
     */
    fun merge(
        local: List<RecordingFile>,
        server: List<ServerRecording>,
        failedUploads: Set<String> = emptySet()
    ): List<RecordingItem> {
        val byServerId = HashMap<String, RecordingFile>()
        val bySession = HashMap<Pair<String, Long>, RecordingFile>()
        for (file in local) {
            file.serverId?.takeIf { it.isNotBlank() }?.let { byServerId.putIfAbsent(it, file) }
            if (file.deviceSN.isNotBlank()) bySession.putIfAbsent(file.deviceSN to file.sessionId, file)
        }

        val consumed = HashSet<String>() // local ids already paired with a server row
        val items = ArrayList<RecordingItem>(server.size + local.size)
        for (rec in server) {
            val match = byServerId[rec.id]?.takeIf { it.id !in consumed }
                ?: rec.sessionId?.let { sid ->
                    if (rec.deviceSn.isBlank()) null else bySession[rec.deviceSn to sid]
                }?.takeIf { it.id !in consumed }
            if (match != null) consumed += match.id
            items += RecordingItem(local = match, server = rec, uploadFailed = match != null && match.id in failedUploads)
        }
        for (file in local) {
            if (file.id !in consumed) items += RecordingItem(local = file, server = null, uploadFailed = file.id in failedUploads)
        }
        return items.sortedByDescending { it.recordedAt }
    }

    /**
     * Client-side search: case-insensitive substring over the title and the server's preview
     * text. Blank query returns the list unchanged.
     */
    fun filter(items: List<RecordingItem>, query: String?): List<RecordingItem> {
        val q = query?.trim()?.takeIf { it.isNotEmpty() } ?: return items
        return items.filter { it.searchText.contains(q, ignoreCase = true) }
    }
}
