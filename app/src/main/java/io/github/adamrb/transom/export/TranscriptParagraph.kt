package io.github.adamrb.transom.export

import org.json.JSONArray
import org.json.JSONObject

/**
 * One reader paragraph of a transcript: a stretch of one speaker talking, as the bridge server
 * lays it out in the document's `paragraphs` array (see the server's app/formatting.py). The
 * transcript is read as prose, so [start]/[end] exist only to seek the player, never for display;
 * the one kind of time anchor the reader gets is a bookmark (a recorder button press), and
 * [bookmarks] holds the indexes into the document's `highlights` array that fall in this paragraph.
 *
 * Lives in the export package, pure Kotlin (no android.*), because the detail screen, the
 * clipboard copy and the markdown export all read the same structure and the export tests must
 * run on the plain JVM.
 */
data class TranscriptParagraph(
    val speaker: String?,
    val text: String,
    val start: Double?,
    val end: Double?,
    val bookmarks: List<Int> = emptyList()
) {
    val isBookmarked: Boolean get() = bookmarks.isNotEmpty()

    companion object {
        /** Server label for an unlabeled segment inside a diarized transcript (formatting.py). */
        const val UNKNOWN_SPEAKER = "Unknown speaker"

        /**
         * The paragraphs to show for a transcript document: the server's own `paragraphs` when
         * the document carries them, otherwise [derive]d from its segments. Null when the JSON is
         * not a transcript at all; an empty list when it is one without any spoken text (callers
         * may still try its flat `text`).
         */
        fun parse(transcriptJSON: String): List<TranscriptParagraph>? =
            fromDocument(transcriptJSON)?.takeIf { it.isNotEmpty() } ?: derive(transcriptJSON)

        /**
         * The document's `paragraphs` array as written by the server, or null when the document
         * has none (older cached transcripts) or is not an object. Malformed entries are skipped
         * one by one; JSON `null` speakers read as no speaker (undiarized).
         */
        fun fromDocument(transcriptJSON: String): List<TranscriptParagraph>? = try {
            val arr = JSONObject(transcriptJSON).optJSONArray("paragraphs")
            if (arr == null) null else (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = p.optString("text").trim()
                if (text.isEmpty()) return@mapNotNull null
                val marks = p.optJSONArray("bookmarks")
                TranscriptParagraph(
                    speaker = if (p.isNull("speaker")) null else p.optString("speaker").trim().ifEmpty { null },
                    text = text,
                    start = number(p, "start"),
                    end = number(p, "end"),
                    bookmarks = if (marks == null) emptyList()
                        else (0 until marks.length()).mapNotNull { j -> marks.optInt(j, -1).takeIf { it >= 0 } }
                )
            }
        } catch (e: Exception) {
            null
        }

        /**
         * The reader layout for a document written before the server produced `paragraphs`:
         * consecutive segments by the same speaker become one paragraph, texts joined with a
         * space, and each bookmark (the document's `highlights`) is attached to the paragraph
         * whose time span contains the press, else the next paragraph after it, else the last.
         *
         * Accepts every segment shape the app has cached over time: a bare array or an object
         * with a `segments` (or `transaction`/`list`/`data`) array; text under `content`, `text`
         * or `sentence`; the speaker as the server's label string, a device-cache speaker number
         * (0-based, shown as "Speaker N+1") or a Plaud `speaker_id` ("SPEAKER_00" shown as
         * "Speaker 00"); times as `start`/`end` seconds or `start_time`/`end_time` milliseconds.
         * Once any segment names a speaker, unlabeled ones read as [UNKNOWN_SPEAKER], as on the
         * server; a wholly unlabeled transcript has no speakers at all.
         *
         * Null when the JSON is not a transcript; an empty list when there are no segments with
         * text in it.
         */
        fun derive(transcriptJSON: String): List<TranscriptParagraph>? {
            val segments = segmentsOf(transcriptJSON) ?: return null
            val paragraphs = groupSegments(segments)
            attachBookmarks(paragraphs, TranscriptHighlight.parse(transcriptJSON))
            return paragraphs.map { it.build() }
        }

        /**
         * The distinct speaker labels of a document in first-appearance order: the server's own
         * `speakers` array when the document carries one (newer servers write it for the rename
         * UI), else derived from [paragraphs]. Empty for an undiarized transcript.
         */
        fun speakersOf(transcriptJSON: String?, paragraphs: List<TranscriptParagraph>): List<String> {
            val listed = try {
                transcriptJSON?.let { JSONObject(it).optJSONArray("speakers") }?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> (arr.opt(i) as? String)?.trim()?.takeIf { it.isNotEmpty() } }
                }
            } catch (e: Exception) {
                null
            }
            if (!listed.isNullOrEmpty()) return listed.distinct()
            return paragraphs.mapNotNull { it.speaker }.distinct()
        }

        /** Server `paragraphs_markdown`: bold speaker label, "★ " on bookmarked paragraphs, one blank line between. */
        fun markdown(paragraphs: List<TranscriptParagraph>): String =
            paragraphs.joinToString("\n\n") { p ->
                val text = if (p.isBookmarked) "★ " + p.text else p.text
                if (p.speaker != null) "**${p.speaker}:** $text" else text
            }

        /** Server `paragraphs_plain`: "Speaker: text" paragraphs for the clipboard, no stars, no times. */
        fun plain(paragraphs: List<TranscriptParagraph>): String =
            paragraphs.joinToString("\n\n") { p -> if (p.speaker != null) "${p.speaker}: ${p.text}" else p.text }

        // MARK: - Segment grouping

        /** A parsed segment; [speaker] is null when the segment carries no label of any shape. */
        internal data class Segment(val speaker: String?, val text: String, val start: Double?, val end: Double?)

        internal class Draft(val speaker: String?, text: String, var start: Double?, var end: Double?) {
            val text = StringBuilder(text)
            val bookmarks = mutableListOf<Int>()
            fun build() = TranscriptParagraph(speaker, text.toString(), start, end, bookmarks.toList())
        }

        internal fun groupSegments(segments: List<Segment>): List<Draft> {
            val diarized = segments.any { it.speaker != null }
            val out = mutableListOf<Draft>()
            for (seg in segments) {
                val speaker = seg.speaker ?: if (diarized) UNKNOWN_SPEAKER else null
                val last = out.lastOrNull()
                if (last != null && last.speaker == speaker) {
                    last.text.append(' ').append(seg.text)
                    if (seg.end != null && (last.end == null || seg.end > last.end!!)) last.end = seg.end
                    if (last.start == null) last.start = seg.start
                } else {
                    out += Draft(speaker, seg.text, seg.start, seg.end)
                }
            }
            return out
        }

        private fun attachBookmarks(paragraphs: List<Draft>, highlights: List<TranscriptHighlight>) {
            if (paragraphs.isEmpty()) return
            highlights.forEachIndexed { i, h ->
                val at = h.at
                val target = paragraphs.firstOrNull { p -> p.start != null && p.end != null && p.start!! <= at && at <= p.end!! }
                    ?: paragraphs.firstOrNull { p -> p.start != null && p.start!! >= at }
                    ?: paragraphs.last()
                target.bookmarks += i
            }
        }

        /** Non-blank segments of any cached shape; null when the JSON holds no segment array. */
        internal fun segmentsOf(transcriptJSON: String): List<Segment>? = try {
            val arr: JSONArray = when {
                transcriptJSON.trimStart().startsWith("[") -> JSONArray(transcriptJSON)
                else -> {
                    val obj = JSONObject(transcriptJSON)
                    obj.optJSONArray("segments")
                        ?: obj.optJSONArray("transaction")
                        ?: obj.optJSONArray("list")
                        ?: obj.optJSONArray("data")
                        ?: JSONArray()
                }
            }
            (0 until arr.length()).mapNotNull { i ->
                val seg = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = seg.optString("content", seg.optString("text", seg.optString("sentence"))).trim()
                if (text.isEmpty()) return@mapNotNull null
                Segment(speakerOf(seg), text, timeOf(seg, "start"), timeOf(seg, "end"))
            }
        } catch (e: Exception) {
            null
        }

        private fun speakerOf(seg: JSONObject): String? = when {
            seg.has("speaker_id") && !seg.isNull("speaker_id") ->
                seg.optString("speaker_id").replace("SPEAKER_", "Speaker ").trim().ifEmpty { null }
            seg.has("speaker") && !seg.isNull("speaker") -> when (val v = seg.opt("speaker")) {
                is Number -> "Speaker ${v.toInt() + 1}"
                else -> v.toString().trim().ifEmpty { null }
            }
            else -> null
        }

        /** `start`/`end` in seconds, or the millisecond `start_time`/`startTime` device-cache keys. */
        private fun timeOf(seg: JSONObject, key: String): Double? {
            val msKey = "${key}_time"
            val camelKey = "${key}Time"
            return when {
                seg.has(msKey) && !seg.isNull(msKey) -> number(seg, msKey)?.div(1000.0)
                seg.has(camelKey) && !seg.isNull(camelKey) -> number(seg, camelKey)?.div(1000.0)
                else -> number(seg, key)
            }
        }

        /** A finite number under [key], accepting numeric strings; null otherwise. */
        private fun number(obj: JSONObject, key: String): Double? {
            if (obj.isNull(key)) return null
            val v = obj.optDouble(key, Double.NaN)
            return v.takeIf { !it.isNaN() && !it.isInfinite() }
        }
    }
}
