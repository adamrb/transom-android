package org.plaudbridge.app.export

import org.json.JSONArray
import org.json.JSONObject

/**
 * One transcript highlight as produced by the bridge server from a button-press mark: the
 * moment of the press ([at], seconds from the start) and the stretch of transcript around it
 * ([start] to [end], [text]). Lives in the export package because both the detail screen and
 * the markdown export read the same "highlights" array out of the cached transcript JSON, and
 * the parser must stay pure Kotlin (no android.*) for the export tests.
 */
data class TranscriptHighlight(
    val at: Double,
    val start: Double,
    val end: Double,
    val text: String
) {
    companion object {
        /**
         * The "highlights" array of a transcript document, or an empty list when the document
         * is not an object, has no such array, or an entry is malformed (each entry is checked
         * on its own so one bad row does not hide the rest). Never throws: a broken highlight
         * must not take the transcript rendering down with it.
         */
        fun parse(transcriptJSON: String): List<TranscriptHighlight> = try {
            val arr: JSONArray? = JSONObject(transcriptJSON).optJSONArray("highlights")
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                val h = arr.optJSONObject(i) ?: return@mapNotNull null
                val at = h.optDouble("at", Double.NaN)
                if (at.isNaN()) return@mapNotNull null
                TranscriptHighlight(
                    at = at,
                    start = h.optDouble("start", at).takeIf { !it.isNaN() } ?: at,
                    end = h.optDouble("end", at).takeIf { !it.isNaN() } ?: at,
                    text = h.optString("text", "").trim()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
