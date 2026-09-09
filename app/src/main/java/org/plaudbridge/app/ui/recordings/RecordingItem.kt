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
    val server: ServerRecording?,
    /**
     * The phone's last attempt to upload [local] failed (UploadManager.failedUploads). Only
     * meaningful for a phone-only row still waiting to upload; a server row means it got there.
     */
    val uploadFailed: Boolean = false
) {
    init {
        require(local != null || server != null) { "a RecordingItem needs a local file or a server recording" }
    }

    /**
     * What the meta line says about a recording that is not finished yet. [NONE] is the normal
     * state and prints nothing: a done recording needs no label, and words like "Synced" or
     * "Uploaded" describe plumbing the user should not have to think about. [UPLOAD_FAILED] is
     * the phone-side counterpart of [FAILED]: the recording is here but did not reach the server.
     */
    enum class Status { NONE, DOWNLOADING, UPLOADING, UPLOAD_FAILED, TRANSCRIBING, FAILED }

    /** The row's action list offers Retry upload. */
    val canRetryUpload: Boolean get() = status == Status.UPLOAD_FAILED && localId != null

    /** Stable identity for adapters and tests: the server id when known, else the local id. */
    val key: String get() = serverId ?: local!!.id

    /** The server's id, from the server object or from what the phone remembers of its upload. */
    val serverId: String? get() = server?.id ?: local?.serverId?.takeIf { it.isNotBlank() }

    val localId: String? get() = local?.id

    /** The phone holds the audio (not just an index entry), so playback works offline. */
    val hasLocalAudio: Boolean get() = local?.isSynced == true

    /**
     * "Remove from phone" applies: the phone actually holds audio (an entry still waiting for its
     * download has nothing to remove, and offering it there would only race the download), there
     * is a server copy for the row to fall back to (without one the action would just be Delete),
     * and the session has an identity the sync flows can suppress, see [suppressibleIdentity].
     */
    val canRemoveFromPhone: Boolean
        get() = local != null && local.isSynced && !local.removedFromPhone && serverId != null &&
            suppressibleIdentity != null

    /**
     * The recorder's (device SN, session id) for this row, from the phone entry or, for a legacy
     * blank-SN entry, from the server row. Null when neither side knows it: then no marker could
     * stop the next sync from downloading the recording again, so Remove from phone is refused
     * (RecordingActions.removeFromPhone) rather than offered and silently undone.
     */
    val suppressibleIdentity: Pair<String, Long>?
        get() {
            local?.takeIf { it.deviceSN.isNotBlank() }?.let { return it.deviceSN to it.sessionId }
            val sn = server?.deviceSn?.takeIf { it.isNotBlank() } ?: return null
            val sid = server?.sessionId ?: return null
            return sn to sid
        }

    /**
     * A name the user typed on this phone wins outright: RecordingFile.nameEditedByUser pins it,
     * and a server title (AI, or a rename made elsewhere before this one was pushed) must not
     * undo the user's own choice. Then the server title, the AI title or a rename made on any
     * client. Then the phone's display name (cached AI title, then "Untitled Recording"), and
     * only for server-only rows without a title the server file name.
     */
    val title: String
        get() = local?.takeIf { it.nameEditedByUser }?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: server?.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: local?.displayName
            ?: server!!.displayTitle

    /**
     * [title] is a real name (typed by the user, given by the server's AI, or cached from it),
     * not one of the stand-ins ("Untitled Recording", the server's file name). Rows without one
     * may say something more useful instead, see [isNoSpeech].
     */
    val hasTitle: Boolean
        get() = local?.takeIf { it.nameEditedByUser }?.name?.isNotBlank() == true ||
            !server?.title.isNullOrBlank() ||
            !local?.serverTitle.isNullOrBlank()

    /**
     * The server finished with this recording and heard nothing in it: no text, no title, no
     * summary. The server's object is the word on it when the row has one; without one (offline,
     * before the first list refresh) the transcript document the phone cached says the same.
     */
    val isNoSpeech: Boolean
        get() = server?.noSpeech ?: ServerRecording.transcriptSaysNoSpeech(local?.transcriptJSON)

    /** The server is still working on the recording (queued or transcribing). */
    val isTranscribing: Boolean get() = status == Status.TRANSCRIBING

    /** Recording start for sorting and day headers: server started_at, else local createdAt. */
    val recordedAt: Long
        get() = server?.startedAt ?: local?.createdAt ?: server?.uploadedAt ?: 0L

    /** Whichever side knows the length; 0 when neither does. */
    val durationSeconds: Long
        get() = server?.durationSeconds?.takeIf { it > 0 } ?: local?.duration?.takeIf { it > 0 } ?: 0L

    /** Button-press marks: the server's once it has them, else what the phone read off the recorder. */
    val marksCount: Int
        get() = (server?.marks?.takeIf { it.isNotEmpty() } ?: local?.marks ?: emptyList()).size

    /** What the automations did with the recording, when the server has run them for it. */
    val automations: org.plaudbridge.app.models.AutomationsSummary? get() = server?.automations

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
                // Audio dropped on purpose; the row is a link to the server copy, not a download.
                l.removedFromPhone -> Status.NONE
                !l.isSynced -> Status.DOWNLOADING
                !l.uploaded -> if (uploadFailed) Status.UPLOAD_FAILED else Status.UPLOADING
                // Uploaded but absent from the server list we hold (stale snapshot, or removed
                // on the server): nothing certain to say, so say nothing.
                else -> Status.NONE
            }
        }

    /**
     * Text the search box matches against besides the title. The [title] here is the real or
     * stand-in name, never the "No speech detected" wording a row may display instead.
     */
    val searchText: String
        get() = listOfNotNull(title, server?.textPreview, server?.summary, local?.summaryText)
            .joinToString("\n")
}
