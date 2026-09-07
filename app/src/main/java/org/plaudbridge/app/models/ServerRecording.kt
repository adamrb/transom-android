package org.plaudbridge.app.models

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * One recording as the bridge server describes it (GET /api/v1/recordings and
 * GET /api/v1/recordings/{id}). Distinct from [RecordingFile] on purpose: that class is the
 * phone's persisted sync index, and server recordings must never be written into it, so the
 * Library keeps its own read-only view of what the server holds.
 *
 * Pure Kotlin plus org.json so the mapping is unit-testable; no android.* imports.
 */
data class ServerRecording(
    val id: String,
    val deviceSn: String,
    val sessionId: Long?,
    val filename: String,
    val sizeBytes: Long,
    /** Length in seconds as reported by the server (0 when unknown). */
    val durationS: Double,
    /** Recording start as epoch millis, or null when the server has no start time. */
    val startedAt: Long?,
    /** Upload time as epoch millis, or null when unparseable. */
    val uploadedAt: Long?,
    val source: String,
    /** One of done, pending, transcribing, failed, stored (anything else is shown verbatim). */
    val status: String,
    val title: String?,
    val summary: String?,
    /** Button-press marks in seconds from the start; empty when none. */
    val marks: List<Double>,
    val hasTranscript: Boolean,
    val textPreview: String?,
    val error: String?
) {
    /** Name for lists and headers: the AI/manual title when the server has one, else the file name. */
    val displayTitle: String
        get() = title?.trim()?.takeIf { it.isNotEmpty() } ?: filename

    val marksCount: Int get() = marks.size

    /**
     * Timestamp used for sorting and day grouping. Prefer the recording's own start; recordings
     * uploaded without metadata only have an upload time, which is still a better anchor than
     * nothing. 0 when neither is known, which sorts such rows last.
     */
    val recordedAt: Long
        get() = startedAt ?: uploadedAt ?: 0L

    /** Whole seconds for the same duration formatters the Files tab uses. */
    val durationSeconds: Long
        get() = durationS.toLong()

    val isDone: Boolean get() = status == STATUS_DONE

    companion object {
        const val STATUS_DONE = "done"
        const val STATUS_PENDING = "pending"
        const val STATUS_TRANSCRIBING = "transcribing"
        const val STATUS_FAILED = "failed"
        const val STATUS_STORED = "stored"

        /** Parse one recording object. Throws on a missing/blank id; everything else has a default. */
        fun fromJson(obj: JSONObject): ServerRecording {
            val id = obj.opt("id") as? String
            require(!id.isNullOrBlank()) { "recording id must be a non-blank string" }
            val marksArr = obj.optJSONArray("marks")
            val marks = if (marksArr == null) emptyList() else (0 until marksArr.length()).mapNotNull { i ->
                marksArr.optDouble(i, Double.NaN).takeIf { !it.isNaN() }
            }
            return ServerRecording(
                id = id,
                deviceSn = obj.optNullableString("device_sn") ?: "",
                sessionId = if (obj.isNull("session_id")) null else obj.optLong("session_id"),
                filename = obj.optNullableString("filename") ?: "",
                sizeBytes = obj.optLong("size_bytes", 0L),
                durationS = obj.optDouble("duration_s", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                startedAt = parseIso(obj.optNullableString("started_at")),
                uploadedAt = parseIso(obj.optNullableString("uploaded_at")),
                source = obj.optNullableString("source") ?: "",
                status = obj.optNullableString("status") ?: "",
                title = obj.optNullableString("title"),
                summary = obj.optNullableString("summary"),
                marks = marks,
                hasTranscript = obj.optBoolean("has_transcript", false),
                textPreview = obj.optNullableString("text_preview"),
                error = obj.optNullableString("error")
            )
        }

        /**
         * The `recordings` array of a list response. Rows that fail to parse are dropped rather
         * than failing the whole list: one odd row on the server should not blank the Library.
         */
        fun listFromJson(json: String): List<ServerRecording> {
            val arr: JSONArray = JSONObject(json).optJSONArray("recordings") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                try { fromJson(obj) } catch (e: Exception) { null }
            }
        }

        /** optString returns "null" for JSON null; this returns Kotlin null instead. */
        private fun JSONObject.optNullableString(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() || has(key) }

        private val ISO = Regex(
            """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|z|[+-]\d{2}:?\d{2})?$"""
        )

        /**
         * ISO-8601 timestamp to epoch millis, or null when absent or malformed.
         *
         * Hand-rolled instead of SimpleDateFormat because the server (Python) emits fractional
         * seconds of any width and offsets like "+00:00", which SimpleDateFormat's `X` pattern
         * only handles from API 24 while this app supports 21. A timestamp with no zone is taken
         * as UTC, which is what the server stores.
         */
        fun parseIso(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            val m = ISO.matchEntire(value.trim()) ?: return null
            val g = m.groupValues
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            }
            var millis = cal.timeInMillis
            if (g[7].isNotEmpty()) {
                // Keep the first three fraction digits (pad shorter fractions), ignore the rest.
                millis += g[7].padEnd(3, '0').substring(0, 3).toLong()
            }
            val zone = g[8]
            if (zone.isNotEmpty() && zone != "Z" && zone != "z") {
                val sign = if (zone[0] == '-') -1 else 1
                val digits = zone.substring(1).replace(":", "")
                val offsetMin = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
                // "+02:00" means the local clock is ahead of UTC, so subtract to get back to UTC.
                millis -= sign * offsetMin * 60_000L
            }
            return millis
        }
    }
}
