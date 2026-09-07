package org.plaudbridge.app.ui.recordings

import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.ServerRecording

/**
 * One row of the Recordings tab: a recording as the user thinks of it, whichever of the two
 * stores happen to hold it. [server] is the bridge server's view (transcript status, AI title,
 * marks), [local] the phone's sync index entry (offline audio, upload state). At least one is
 * non-null. The pair is built by [RecordingsMerger]; nothing here touches storage or network.
 */
data class RecordingItem(
    val local: RecordingFile?,
    val server: ServerRecording?
) {
    init {
        require(local != null || server != null) { "a RecordingItem needs a local file or a server recording" }
    }

    /**
     * What the meta line says about a recording that is not finished yet. [NONE] is the normal
     * state and prints nothing: a done recording needs no label, and words like "Synced" or
     * "Uploaded" describe plumbing the user should not have to think about.
     */
    enum class Status { NONE, DOWNLOADING, UPLOADING, TRANSCRIBING, FAILED }

    /** Stable identity for adapters and tests: the server id when known, else the local id. */
    val key: String get() = serverId ?: local!!.id

    /** The server's id, from the server object or from what the phone remembers of its upload. */
    val serverId: String? get() = server?.id ?: local?.serverId?.takeIf { it.isNotBlank() }

    val localId: String? get() = local?.id

    /** The phone holds the audio (not just an index entry), so playback works offline. */
    val hasLocalAudio: Boolean get() = local?.isSynced == true

    /**
     * Server title first: it is the AI title or a rename the user made on any client. Then the
     * phone's display name (manual rename, then cached AI title, then "Untitled Recording"), and
     * only for server-only rows without a title the server file name.
     */
    val title: String
        get() = server?.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: local?.displayName
            ?: server!!.displayTitle

    /** Recording start for sorting and day headers: server started_at, else local createdAt. */
    val recordedAt: Long
        get() = server?.startedAt ?: local?.createdAt ?: server?.uploadedAt ?: 0L

    /** Whichever side knows the length; 0 when neither does. */
    val durationSeconds: Long
        get() = server?.durationSeconds?.takeIf { it > 0 } ?: local?.duration?.takeIf { it > 0 } ?: 0L

    /** Button-press marks: the server's once it has them, else what the phone read off the recorder. */
    val marksCount: Int
        get() = (server?.marks?.takeIf { it.isNotEmpty() } ?: local?.marks ?: emptyList()).size

    val status: Status
        get() {
            val s = server
            if (s != null) {
                return when (s.status) {
                    ServerRecording.STATUS_PENDING, ServerRecording.STATUS_TRANSCRIBING -> Status.TRANSCRIBING
                    ServerRecording.STATUS_FAILED -> Status.FAILED
                    // done, stored (transcription disabled), and anything a newer server invents
                    else -> Status.NONE
                }
            }
            val l = local!!
            return when {
                !l.isSynced -> Status.DOWNLOADING
                !l.uploaded -> Status.UPLOADING
                // Uploaded but absent from the server list we hold (stale snapshot, or removed
                // on the server): nothing certain to say, so say nothing.
                else -> Status.NONE
            }
        }

    /** Text the search box matches against besides the title. */
    val searchText: String
        get() = listOfNotNull(title, server?.textPreview, server?.summary, local?.summaryText)
            .joinToString("\n")
}
