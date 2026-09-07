package org.plaudbridge.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TranscriptMarkdown must stay byte-for-byte compatible with the server's export, so these
 * tests pin the exact document rather than checking for substrings.
 */
class TranscriptMarkdownTest {

    // 2026-09-07T05:27:31Z
    private val recordedAt = 1_788_758_851_000L

    @Test
    fun fullDocumentWithSummary() {
        val md = TranscriptMarkdown.build(
            title = "Budget planning call",
            recordedAtMillis = recordedAt,
            durationSeconds = 754L,
            transcript = "Speaker 1: Let's start.\nSpeaker 2: Sure.",
            summary = "Two people plan a budget."
        )
        val expected = """
            |---
            |title: "Budget planning call"
            |recorded: "2026-09-07T05:27:31Z"
            |duration_s: "754"
            |source: plaud-bridge
            |---
            |# Budget planning call
            |
            |## Summary
            |
            |Two people plan a budget.
            |
            |## Transcript
            |
            |Speaker 1: Let's start.
            |Speaker 2: Sure.
            |""".trimMargin()
        assertEquals(expected, md)
    }

    @Test
    fun summaryBlockOmittedEntirelyWhenAbsent() {
        val md = TranscriptMarkdown.build("Standup", recordedAt, 61L, "Speaker 1: Hi.")
        val expected = """
            |---
            |title: "Standup"
            |recorded: "2026-09-07T05:27:31Z"
            |duration_s: "61"
            |source: plaud-bridge
            |---
            |# Standup
            |
            |## Transcript
            |
            |Speaker 1: Hi.
            |""".trimMargin()
        assertEquals(expected, md)
        assertFalse(md.contains("## Summary"))
    }

    @Test
    fun blankSummaryCountsAsAbsent() {
        val md = TranscriptMarkdown.build("Standup", recordedAt, 61L, "Hi.", summary = "  \n ")
        assertFalse(md.contains("## Summary"))
    }

    @Test
    fun titleWithQuotesAndBackslashIsJsonQuotedInFrontMatterOnly() {
        val md = TranscriptMarkdown.build("""Call re: "Q3" C:\plan""", recordedAt, 5L, "x")
        assertTrue(md.contains("""title: "Call re: \"Q3\" C:\\plan""" + "\"\n"))
        assertTrue(md.contains("""# Call re: "Q3" C:\plan""" + "\n"))
    }

    @Test
    fun quoteEscapesControlCharacters() {
        assertEquals("\"a\\nb\\tc\\u0001\"", TranscriptMarkdown.quote("a\nb\tc\u0001"))
        assertEquals("\"plain\"", TranscriptMarkdown.quote("plain"))
    }

    @Test
    fun endsWithExactlyOneNewlineRegardlessOfInputTrailingWhitespace() {
        val md = TranscriptMarkdown.build("T", recordedAt, 1L, "Body text\n\n\n")
        assertTrue(md.endsWith("Body text\n"))
        assertFalse(md.endsWith("\n\n"))
        val md2 = TranscriptMarkdown.build("T", recordedAt, 1L, "Body text")
        assertEquals(md, md2)
    }

    @Test
    fun durationFormatting() {
        assertEquals("754", TranscriptMarkdown.formatDuration(754.0))
        assertEquals("0", TranscriptMarkdown.formatDuration(0.0))
        assertEquals("12.5", TranscriptMarkdown.formatDuration(12.5))
        assertEquals("0.25", TranscriptMarkdown.formatDuration(0.25))
        assertEquals("3600", TranscriptMarkdown.formatDuration(3600.0))
        assertEquals("0", TranscriptMarkdown.formatDuration(Double.NaN))
        // Long overload goes through the same path
        assertTrue(TranscriptMarkdown.build("T", recordedAt, 90L, "x").contains("duration_s: \"90\"\n"))
    }

    @Test
    fun recordedIsIso8601UtcSecondPrecision() {
        assertEquals("1970-01-01T00:00:00Z", TranscriptMarkdown.isoUtc(0L))
        assertEquals("2026-09-07T05:27:31Z", TranscriptMarkdown.isoUtc(recordedAt))
        // millis are dropped, not rounded
        assertEquals("2026-09-07T05:27:31Z", TranscriptMarkdown.isoUtc(recordedAt + 999))
    }
}
