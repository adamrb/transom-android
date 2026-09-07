package org.plaudbridge.app.export

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Markdown export of a transcript, shared layout with the server's own export.
 *
 * The server writes the identical document for its "Export" button, so a file produced here and
 * one downloaded from the dashboard are interchangeable (same front matter keys, same headings,
 * same blank-line rhythm, single trailing newline). Keep any change here in lockstep with the
 * server. Pure Kotlin (no android.*) so the layout is unit-testable byte for byte.
 *
 * ```
 * ---
 * title: "<title, JSON-string-quoted>"
 * recorded: "<ISO-8601 UTC, second precision>"
 * duration_s: "<seconds, integer or decimal>"
 * source: plaud-bridge
 * ---
 * # <title>
 *
 * ## Summary          (block omitted entirely when there is no summary)
 *
 * <summary>
 *
 * ## Highlights       (block omitted entirely when there are no highlights)
 *
 * - **m:ss** <text>
 *
 * ## Transcript
 *
 * **Speaker 1:** <turn>
 *
 * **Speaker 2:** <turn>
 * ```
 *
 * The Transcript section is [transcriptBody]: the server's flat `text` (one "Speaker N: ..."
 * line per turn) rendered as bold-label paragraphs, the same way `transcript_body_markdown`
 * does it server-side, so the two exports stay identical.
 */
object TranscriptMarkdown {

    /** Server wording for a highlight without transcript text; must match app/highlights.py. */
    const val NO_SPEECH = "(no speech near this mark)"

    /**
     * @param title the recording's display name (manual rename > AI title > stored name)
     * @param recordedAtMillis recording start, epoch millis
     * @param durationSeconds recording length; whole values print without a fraction
     * @param transcript the transcript body ("Speaker 1: ..." lines when diarized)
     * @param summary AI summary, or null/blank to omit the Summary block
     * @param highlights button-press highlights, or null/empty to omit the Highlights block
     */
    fun build(
        title: String,
        recordedAtMillis: Long,
        durationSeconds: Double,
        transcript: String,
        summary: String? = null,
        highlights: List<TranscriptHighlight>? = null
    ): String = buildString {
        append("---\n")
        append("title: ").append(quote(title)).append('\n')
        append("recorded: \"").append(isoUtc(recordedAtMillis)).append("\"\n")
        append("duration_s: \"").append(formatDuration(durationSeconds)).append("\"\n")
        append("source: plaud-bridge\n")
        append("---\n")
        append("# ").append(title).append('\n')
        append('\n')
        val trimmedSummary = summary?.trim()
        if (!trimmedSummary.isNullOrEmpty()) {
            append("## Summary\n")
            append('\n')
            append(trimmedSummary).append('\n')
            append('\n')
        }
        if (!highlights.isNullOrEmpty()) {
            append("## Highlights\n")
            append('\n')
            for (h in highlights) {
                append("- **").append(formatTimestamp(h.at)).append("** ")
                append(h.text.trim().ifEmpty { NO_SPEECH }).append('\n')
            }
            append('\n')
        }
        append("## Transcript\n")
        append('\n')
        // transcriptBody drops blank lines, so the document ends with exactly one newline
        // regardless of the stored text.
        append(transcriptBody(transcript)).append('\n')
    }

    /**
     * Server `transcript_body_markdown`: `Speaker 1: hi` becomes `**Speaker 1:** hi`, one blank
     * line between turns; lines without a speaker label pass through unchanged (already prose).
     * The label class is spelled out ([\p{L}\p{N}_]) instead of `\w` because Python's `\w` is
     * Unicode-aware while Java's is ASCII-only by default, and a name like "José" must render
     * the same in both exports.
     */
    fun transcriptBody(text: String): String =
        paragraphs(text).joinToString("\n\n") { line ->
            SPEAKER_LINE.matchEntire(line)?.let { m -> "**${m.groupValues[1]}:** ${m.groupValues[2]}" } ?: line
        }

    /**
     * The transcript as plain speaker paragraphs for the clipboard: the server's turns with a
     * blank line between them and no timestamps or markdown. Whisper segments are already merged
     * per speaker in `text`, which is why this reads better than the on-screen segment blocks.
     */
    fun plainParagraphs(text: String): String = paragraphs(text).joinToString("\n\n")

    /** Non-blank, trimmed lines of the server's `text` field; each is one speaker turn. */
    fun paragraphs(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private val SPEAKER_LINE = Regex("^([A-Z][\\p{L}\\p{N}_ .'-]{0,40}?):\\s+(.*)$")

    /** Convenience for the app's Long duration field. */
    fun build(
        title: String,
        recordedAtMillis: Long,
        durationSeconds: Long,
        transcript: String,
        summary: String? = null,
        highlights: List<TranscriptHighlight>? = null
    ): String = build(title, recordedAtMillis, durationSeconds.toDouble(), transcript, summary, highlights)

    /**
     * "m:ss" (minutes unpadded) below one hour, "h:mm:ss" from one hour on. Seconds are rounded
     * half to even (Math.rint) because that is what Python's round does in the server's fmt_ts,
     * and the two exports must print identical timestamps.
     */
    fun formatTimestamp(seconds: Double): String {
        if (seconds.isNaN()) return "0:00"
        val s = Math.rint(seconds).toLong().coerceAtLeast(0L)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /**
     * JSON string literal (RFC 8259): backslash, double quote and control characters escaped,
     * everything else verbatim. YAML accepts JSON double-quoted scalars, which is why the front
     * matter can be parsed by either a YAML or a JSON-ish reader on the server.
     */
    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }
        append('"')
    }

    /** "123" for whole seconds, "12.5" otherwise (no trailing zeros, never scientific notation). */
    fun formatDuration(seconds: Double): String {
        if (seconds.isNaN() || seconds.isInfinite()) return "0"
        val whole = seconds.toLong()
        if (whole.toDouble() == seconds) return whole.toString()
        return BigDecimal(seconds.toString()).stripTrailingZeros().toPlainString()
    }

    /** 2026-09-07T05:27:31Z: second precision, UTC, matching the server's timestamps. */
    fun isoUtc(epochMillis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMillis))
    }
}
