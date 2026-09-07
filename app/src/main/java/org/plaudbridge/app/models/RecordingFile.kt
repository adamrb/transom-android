package org.plaudbridge.app.models

import com.google.gson.annotations.SerializedName
import java.util.UUID

data class RecordingFile(
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),

    @SerializedName("sessionId")
    val sessionId: Long,

    /** Immutable: (deviceSN, sessionId) is the recording's identity; blank = legacy/unknown. */
    @SerializedName("deviceSN")
    val deviceSN: String,

    @SerializedName("name")
    var name: String,

    @SerializedName("duration")
    var duration: Long,

    @SerializedName("createdAt")
    val createdAt: Long,

    @SerializedName("syncedAt")
    var syncedAt: Long? = null,

    @SerializedName("localPath")
    var localPath: String? = null,

    @SerializedName("summaryText")
    var summaryText: String? = null,

    @SerializedName("transcriptJSON")
    var transcriptJSON: String? = null,

    /** Confirmed uploaded to the self-hosted bridge server (201, or 200 duplicate:true). */
    @SerializedName("uploaded")
    var uploaded: Boolean = false,

    /** Recording id assigned by the bridge server, used for transcript lookup. */
    @SerializedName("serverId")
    var serverId: String? = null,

    @SerializedName("uploadedAt")
    var uploadedAt: Long? = null,

    /** Delete-after-upload could not run (device disconnected); retry on the matching device. */
    @SerializedName("deletePendingOnDevice")
    var deletePendingOnDevice: Boolean = false,

    /**
     * AI-generated title from the bridge server (the "title" field of the transcript JSON). The
     * recorder has no file names, so [name] is "Untitled Recording" for every synced file; this is
     * what the lists show instead once the server has summarized the recording. Null until then.
     */
    @SerializedName("serverTitle")
    var serverTitle: String? = null,

    /**
     * The user renamed this recording by hand. Once set, [displayName] sticks to [name] and a
     * server title arriving later never overrides the manual choice. Non-nullable is safe with
     * Gson: records written before this field existed are allocated without a constructor, so a
     * missing (or null) JSON value leaves the primitive at its JVM default, false.
     */
    @SerializedName("nameEditedByUser")
    var nameEditedByUser: Boolean = false
) {
    val isSynced: Boolean
        get() = localPath != null

    /**
     * Single source of truth for the name shown anywhere in the UI: a manual rename wins, then
     * the server's AI title, then the stored [name]. Computed (no backing field), so Gson never
     * persists it and it always reflects the current fields.
     */
    val displayName: String
        get() {
            if (nameEditedByUser) return name
            val title = serverTitle?.trim()
            return if (!title.isNullOrEmpty()) title else name
        }
}
