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
    var deletePendingOnDevice: Boolean = false
) {
    val isSynced: Boolean
        get() = localPath != null
}
