package cloud.adamrb.transom.export

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
            |source: transom
            |---
            |# Budget planning call
            |
            |## Summary
            |
            |Two people plan a budget.
            |
            |## Transcript
            |
            |**Speaker 1:** Let's start.
            |
            |**Speaker 2:** Sure.
            |""".trimMargin()
        assertEquals(expected, md)
    }

    @Test
    fun transcriptBodyMatchesServerTranscriptBodyMarkdown() {
        // Reference output of app/export.py transcript_body_markdown for this input.
        assertEquals(
            "**Speaker 1:** hi there\n\n**Speaker 2:** hello",
            TranscriptMarkdown.transcriptBody("Speaker 1: hi there\nSpeaker 2: hello")
        )
        // Blank lines and surrounding whitespace are dropped, not doubled.
        assertEquals(
            "**Speaker 1:** a\n\n**Speaker 2:** b",
            TranscriptMarkdown.transcriptBody("\n Speaker 1: a \n\n\nSpeaker 2: b\n")
        )
        // Lines without a speaker label (plain prose, lowercase start, no space after colon)
        // pass through unchanged.
        assertEquals("just prose here", TranscriptMarkdown.transcriptBody("just prose here"))
        assertEquals("note: keep this", TranscriptMarkdown.transcriptBody("note: keep this"))
        assertEquals("Time:12:30 lunch", TranscriptMarkdown.transcriptBody("Time:12:30 lunch"))
        // Named speakers, including non-ASCII letters (Python's \w is Unicode-aware).
        assertEquals("**José:** sí", TranscriptMarkdown.transcriptBody("José: sí"))
        assertEquals("**Dr. O'Neil-Smith:** ok", TranscriptMarkdown.transcriptBody("Dr. O'Neil-Smith: ok"))
        // The label is capped at 41 characters; longer runs are not a speaker label.
        val long = "A".repeat(45) + ": text"
        assertEquals(long, TranscriptMarkdown.transcriptBody(long))
        // Lazy label: the FIRST colon followed by whitespace ends the label.
        assertEquals("**Speaker 1:** said: no", TranscriptMarkdown.transcriptBody("Speaker 1: said: no"))
        assertEquals("", TranscriptMarkdown.transcriptBody("  \n \n"))
    }

    @Test
    fun plainParagraphsSeparateTurnsWithOneBlankLine() {
        assertEquals(
            "Speaker 1: hi there\n\nSpeaker 2: hello",
            TranscriptMarkdown.plainParagraphs("Speaker 1: hi there\nSpeaker 2: hello\n")
        )
        // Already-doubled newlines do not become quadruple ones.
        assertEquals("a\n\nb", TranscriptMarkdown.plainParagraphs("a\n\nb"))
        assertEquals("", TranscriptMarkdown.plainParagraphs(""))
    }

    @Test
    fun highlightsSectionSitsBetweenSummaryAndTranscript() {
        val md = TranscriptMarkdown.build(
            title = "Budget planning call",
            recordedAtMillis = recordedAt,
            durationSeconds = 754L,
            transcript = "Speaker 1: Let's start.",
            summary = "Two people plan a budget.",
            highlights = listOf(
                TranscriptHighlight(at = 6.0, start = 4.2, end = 12.9, text = "Ship it Friday."),
                TranscriptHighlight(at = 125.5, start = 125.5, end = 125.5, text = ""),
                TranscriptHighlight(at = 3725.0, start = 3700.0, end = 3730.0, text = "  Hour mark.  ")
            )
        )
        val expected = """
            |---
            |title: "Budget planning call"
            |recorded: "2026-09-07T05:27:31Z"
            |duration_s: "754"
            |source: transom
            |---
            |# Budget planning call
            |
            |## Summary
            |
            |Two people plan a budget.
            |
            |## Highlights
            |
            |- **0:06** Ship it Friday.
            |- **2:06** (no speech near this mark)
            |- **1:02:05** Hour mark.
            |
            |## Transcript
            |
            |**Speaker 1:** Let's start.
            |""".trimMargin()
        assertEquals(expected, md)
    }

    @Test
    fun paragraphsReplaceTheFlatTextWithBookmarkStars() {
        // The server's transcript_markdown with `paragraphs`: paragraphs_markdown is the body, the
        // flat text is not used for it, and the Highlights section keeps its timestamps.
        val paragraphs = listOf(
            TranscriptParagraph("Speaker 1", "Intro.", 0.0, 5.0),
            TranscriptParagraph("Speaker 2", "Reply.", 5.0, 10.0, listOf(0)),
            TranscriptParagraph("Speaker 1", "Later.", 30.0, 35.0, listOf(1, 2))
        )
        val md = TranscriptMarkdown.build(
            title = "Standup",
            recordedAtMillis = recordedAt,
            durationSeconds = 61L,
            transcript = "Speaker 1: Intro.\nSpeaker 2: Reply.\nSpeaker 1: Later.",
            highlights = listOf(
                TranscriptHighlight(6.0, 5.0, 10.0, "Reply."),
                TranscriptHighlight(20.0, 20.0, 20.0, ""),
                TranscriptHighlight(99.0, 99.0, 99.0, "")
            ),
            paragraphs = paragraphs
        )
        val expected = """
            |---
            |title: "Standup"
            |recorded: "2026-09-07T05:27:31Z"
            |duration_s: "61"
            |source: transom
            |---
            |# Standup
            |
            |## Highlights
            |
            |- **0:06** Reply.
            |- **0:20** (no speech near this mark)
            |- **1:39** (no speech near this mark)
            |
            |## Transcript
            |
            |**Speaker 1:** Intro.
            |
            |**Speaker 2:** ★ Reply.
            |
            |**Speaker 1:** ★ Later.
            |""".trimMargin()
        assertEquals(expected, md)
        // Undiarized paragraphs have no label; a bookmarked one still gets its star.
        assertTrue(
            TranscriptMarkdown.build("T", recordedAt, 1L, "", paragraphs = listOf(
                TranscriptParagraph(null, "Just me.", 0.0, 1.0), TranscriptParagraph(null, "Still me.", 2.0, 3.0, listOf(0))
            )).endsWith("## Transcript\n\nJust me.\n\n★ Still me.\n")
        )
    }

    @Test
    fun emptyParagraphsFallBackToTheFlatText() {
        val flat = TranscriptMarkdown.build("T", recordedAt, 1L, "Speaker 1: Hi.\nSpeaker 2: Hello.")
        assertEquals(flat, TranscriptMarkdown.build("T", recordedAt, 1L, "Speaker 1: Hi.\nSpeaker 2: Hello.", paragraphs = emptyList()))
        assertEquals(flat, TranscriptMarkdown.build("T", recordedAt, 1L, "Speaker 1: Hi.\nSpeaker 2: Hello.", paragraphs = null))
        assertTrue(flat.endsWith("## Transcript\n\n**Speaker 1:** Hi.\n\n**Speaker 2:** Hello.\n"))
    }

    @Test
    fun plainParagraphsOfTheReaderLayoutCarryNoStarsOrTimes() {
        assertEquals(
            "Speaker 1: Intro.\n\nSpeaker 2: Reply.\n\nStill talking.",
            TranscriptMarkdown.plainParagraphs(listOf(
                TranscriptParagraph("Speaker 1", "Intro.", 0.0, 5.0),
                TranscriptParagraph("Speaker 2", "Reply.", 5.0, 10.0, listOf(0)),
                TranscriptParagraph(null, "Still talking.", 10.0, 12.0)
            ))
        )
    }

    @Test
    fun highlightsWithoutSummaryFollowTheTitleDirectly() {
        val md = TranscriptMarkdown.build(
            "Standup", recordedAt, 61L, "Speaker 1: Hi.",
            highlights = listOf(TranscriptHighlight(59.6, 58.0, 61.0, "Bye."))
        )
        val expected = """
            |---
            |title: "Standup"
            |recorded: "2026-09-07T05:27:31Z"
            |duration_s: "61"
            |source: transom
            |---
            |# Standup
            |
            |## Highlights
            |
            |- **1:00** Bye.
            |
            |## Transcript
            |
            |**Speaker 1:** Hi.
            |""".trimMargin()
        assertEquals(expected, md)
    }

    @Test
    fun emptyHighlightsOmitTheSection() {
        val md = TranscriptMarkdown.build("Standup", recordedAt, 61L, "Hi.", highlights = emptyList())
        assertFalse(md.contains("## Highlights"))
        assertEquals(TranscriptMarkdown.build("Standup", recordedAt, 61L, "Hi."), md)
    }

    @Test
    fun timestampMatchesServerFmtTs() {
        assertEquals("0:00", TranscriptMarkdown.formatTimestamp(0.0))
        assertEquals("0:06", TranscriptMarkdown.formatTimestamp(6.0))
        // Half to even, like Python's round in the server's fmt_ts.
        assertEquals("0:06", TranscriptMarkdown.formatTimestamp(6.5))
        assertEquals("0:08", TranscriptMarkdown.formatTimestamp(7.5))
        assertEquals("2:06", TranscriptMarkdown.formatTimestamp(125.5))
        assertEquals("0:07", TranscriptMarkdown.formatTimestamp(6.51))
        assertEquals("59:59", TranscriptMarkdown.formatTimestamp(3599.0))
        assertEquals("1:00:00", TranscriptMarkdown.formatTimestamp(3600.0))
        assertEquals("1:02:05", TranscriptMarkdown.formatTimestamp(3725.0))
        assertEquals("0:00", TranscriptMarkdown.formatTimestamp(-3.0))
    }

    @Test
    fun summaryBlockOmittedEntirelyWhenAbsent() {
        val md = TranscriptMarkdown.build("Standup", recordedAt, 61L, "Speaker 1: Hi.")
        val expected = """
            |---
            |title: "Standup"
            |recorded: "2026-09-07T05:27:31Z"
            |duration_s: "61"
            |source: transom
            |---
            |# Standup
            |
            |## Transcript
            |
            |**Speaker 1:** Hi.
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
