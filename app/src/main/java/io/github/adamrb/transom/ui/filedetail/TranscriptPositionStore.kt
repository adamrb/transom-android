package io.github.adamrb.transom.ui.filedetail

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the reader left off in each transcript: the index of the first visible paragraph and how
 * far it was scrolled past the top, so a two-hour recording reopens where it was closed. Keyed
 * by the recording (server id when known, else the phone's file id), kept in a small preferences
 * file; a position at the very top is not worth remembering and clears any earlier one.
 */
class TranscriptPositionStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    /** [paragraphIndex] into the transcript's paragraphs; [offsetPx] how far its top sits above the viewport (>= 0). */
    data class Position(val paragraphIndex: Int, val offsetPx: Int)

    fun save(recordingKey: String, position: Position?) {
        val editor = prefs.edit()
        if (position == null || position.paragraphIndex <= 0 && position.offsetPx <= 0) editor.remove(recordingKey)
        else editor.putString(recordingKey, encode(position))
        editor.apply()
    }

    fun load(recordingKey: String): Position? = decode(prefs.getString(recordingKey, null))

    /** The preferred key for a recording: the server's id outlives the phone copy and its id. */
    fun keyFor(serverId: String?, fileId: String?): String? =
        serverId?.takeIf { it.isNotBlank() }?.let { "server:$it" } ?: fileId?.takeIf { it.isNotBlank() }?.let { "file:$it" }

    companion object {
        const val PREFS_NAME = "transcript_positions"

        fun encode(position: Position): String = "${position.paragraphIndex}:${position.offsetPx}"

        /** Null for anything but "index:offset" with non-negative integers. */
        fun decode(value: String?): Position? {
            if (value == null) return null
            val parts = value.split(':')
            if (parts.size != 2) return null
            val index = parts[0].toIntOrNull() ?: return null
            val offset = parts[1].toIntOrNull() ?: return null
            if (index < 0 || offset < 0) return null
            return Position(index, offset)
        }
    }
}
