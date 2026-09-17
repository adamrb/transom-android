package cloud.adamrb.transom.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TranscriptParagraph: the server's `paragraphs` array read as written, the local fallback that
 * groups an older document's segments the same way (per speaker turn, bookmarks attached), and
 * the markdown / plain renderings that must match the server's paragraphs_markdown and
 * paragraphs_plain (tests/test_formatting.py in the server repo). Robolectric because the parser
 * uses org.json, which is a stub on the plain unit-test JVM.
 */
@RunWith(RobolectricTestRunner::class)
class TranscriptParagraphTest {

    private fun p(speaker: String?, text: String, start: Double? = null, end: Double? = null, bookmarks: List<Int> = emptyList()) =
        TranscriptParagraph(speaker, text, start, end, bookmarks)

    @Test
    fun readsTheServerParagraphsAsWritten() {
        val json = """{"text":"x","segments":[],"highlights":[{"at":6.0,"start":4.0,"end":8.0,"text":"t"}],
            "paragraphs":[{"speaker":"Speaker 1","text":"  Hello there. ","start":0.0,"end":4.0,"bookmarks":[]},
                          {"speaker":null,"text":"(cough)","start":4.0,"end":8.0,"bookmarks":[0]},
                          {"speaker":"Speaker 2","text":"","start":8.0,"end":9.0,"bookmarks":[]},
                          "not an object",
                          {"speaker":"Speaker 2","text":"No times.","start":null,"end":null}]}"""
        val paras = TranscriptParagraph.fromDocument(json)!!
        assertEquals(
            listOf(
                p("Speaker 1", "Hello there.", 0.0, 4.0),
                p(null, "(cough)", 4.0, 8.0, listOf(0)),
                p("Speaker 2", "No times.")
            ),
            paras
        )
        assertEquals(paras, TranscriptParagraph.parse(json))
    }

    @Test
    fun documentsWithoutParagraphsReportNoneAndParseFallsBackToSegments() {
        val legacy = """{"text":"Speaker 1: Hello there.","segments":[{"speaker":"Speaker 1","start":0.0,"end":1.0,"text":"Hello"},
            {"speaker":"Speaker 1","start":1.0,"end":2.0,"text":"there."}]}"""
        assertNull(TranscriptParagraph.fromDocument(legacy))
        assertEquals(listOf(p("Speaker 1", "Hello there.", 0.0, 2.0)), TranscriptParagraph.parse(legacy))
        // An empty paragraphs array counts as none too, so the segments still get their chance.
        assertEquals(listOf(p("Speaker 1", "Hello there.", 0.0, 2.0)),
            TranscriptParagraph.parse(legacy.dropLast(1) + ""","paragraphs":[]}"""))
        // Not a transcript at all.
        assertNull(TranscriptParagraph.parse("<html>login</html>"))
        assertNull(TranscriptParagraph.fromDocument("[]"))
        // A transcript with nothing spoken in it.
        assertEquals(emptyList<TranscriptParagraph>(), TranscriptParagraph.parse("""{"text":"","segments":[]}"""))
        assertEquals(emptyList<TranscriptParagraph>(), TranscriptParagraph.parse("""{"no_speech":true}"""))
    }

    @Test
    fun deriveGroupsConsecutiveSegmentsBySpeakerWithoutTimestamps() {
        val json = """{"segments":[
            {"speaker":"Speaker 1","start":0.0,"end":2.0,"text":"Hello there."},
            {"speaker":"Speaker 1","start":2.0,"end":4.0,"text":" How are you? "},
            {"speaker":"Speaker 2","start":4.0,"end":5.0,"text":"Fine."},
            {"speaker":"Speaker 2","start":5.0,"end":7.0,"text":"And you?"},
            {"speaker":"Speaker 2","start":7.0,"end":7.5,"text":"   "},
            {"speaker":"Speaker 1","start":7.5,"end":9.0,"text":"Good."}]}"""
        val paras = TranscriptParagraph.derive(json)!!
        assertEquals(
            listOf(
                p("Speaker 1", "Hello there. How are you?", 0.0, 4.0),
                p("Speaker 2", "Fine. And you?", 4.0, 7.0),
                p("Speaker 1", "Good.", 7.5, 9.0)
            ),
            paras
        )
        val md = TranscriptParagraph.markdown(paras)
        assertEquals("**Speaker 1:** Hello there. How are you?\n\n**Speaker 2:** Fine. And you?\n\n**Speaker 1:** Good.", md)
        assertTrue(!Regex("\\d:\\d\\d").containsMatchIn(md))
    }

    @Test
    fun deriveAcceptsEveryCachedSegmentShape() {
        // Plaud result shape: speaker_id "SPEAKER_00", seconds as numeric strings.
        val plaud = """{"segments":[{"speaker_id":"SPEAKER_00","start":"0.0","text":"Hello"},
            {"speaker_id":"SPEAKER_00","start":"1.0","text":"there."},{"speaker_id":"SPEAKER_01","start":"2.5","text":"Hi."}]}"""
        assertEquals(
            listOf(p("Speaker 00", "Hello there.", 0.0), p("Speaker 01", "Hi.", 2.5)),
            TranscriptParagraph.derive(plaud)
        )
        // Device-cache shape: numeric 0-based speaker, millisecond times, `content` text, a bare array.
        val device = """[{"speaker":0,"start_time":0,"end_time":1500,"content":"Hello"},
            {"speaker":1,"startTime":2500,"endTime":3000,"content":"Hi."}]"""
        assertEquals(
            listOf(p("Speaker 1", "Hello", 0.0, 1.5), p("Speaker 2", "Hi.", 2.5, 3.0)),
            TranscriptParagraph.derive(device)
        )
        // Alternative wrapper keys still count as segments.
        assertEquals(listOf(p(null, "a b")), TranscriptParagraph.derive("""{"list":[{"sentence":"a"},{"sentence":"b"}]}"""))
        assertNull(TranscriptParagraph.derive("nonsense"))
    }

    @Test
    fun deriveLabelsUnlabeledSegmentsOnlyInsideADiarizedTranscript() {
        val mixed = """{"segments":[{"speaker":"Speaker 1","start":0,"end":1,"text":"Hi."},
            {"start":1,"end":2,"text":"(cough)"},{"speaker":"Speaker 2","start":2,"end":3,"text":"Hello."}]}"""
        assertEquals(listOf("Speaker 1", TranscriptParagraph.UNKNOWN_SPEAKER, "Speaker 2"),
            TranscriptParagraph.derive(mixed)!!.map { it.speaker })
        assertTrue(TranscriptParagraph.markdown(TranscriptParagraph.derive(mixed)!!).contains("**Unknown speaker:** (cough)"))
        // Wholly undiarized text keeps no label at all and flows as one paragraph.
        val plain = """{"segments":[{"start":0,"end":1,"text":"a"},{"start":1,"end":2,"text":"b"}]}"""
        assertEquals(listOf(p(null, "a b", 0.0, 2.0)), TranscriptParagraph.derive(plain))
        assertEquals("a b", TranscriptParagraph.markdown(TranscriptParagraph.derive(plain)!!))
        assertEquals("a b", TranscriptParagraph.plain(TranscriptParagraph.derive(plain)!!))
    }

    @Test
    fun deriveAttachesBookmarksToTheParagraphTheyFallInOrTheNextOne() {
        // Mirrors the server's test_bookmarks_attach_to_the_paragraph_they_fall_in_or_the_next_one.
        val json = """{"segments":[{"speaker":"Speaker 1","start":0,"end":5,"text":"Intro."},
            {"speaker":"Speaker 2","start":5,"end":10,"text":"Reply."},{"speaker":"Speaker 1","start":30,"end":35,"text":"Later."}],
            "highlights":[{"at":6.0,"start":5,"end":10,"text":"Reply."},{"at":20.0,"start":20,"end":20,"text":""},
                          {"at":99.0,"start":99,"end":99,"text":""},{"at":null}]}"""
        val paras = TranscriptParagraph.derive(json)!!
        assertEquals(listOf(emptyList(), listOf(0), listOf(1, 2)), paras.map { it.bookmarks })
        val md = TranscriptParagraph.markdown(paras)
        assertTrue(md, md.contains("**Speaker 2:** ★ Reply.") && md.contains("**Speaker 1:** Intro."))
        assertEquals("**Speaker 1:** Intro.\n\n**Speaker 2:** ★ Reply.\n\n**Speaker 1:** ★ Later.", md)
        // The clipboard layout has no stars.
        assertEquals("Speaker 1: Intro.\n\nSpeaker 2: Reply.\n\nSpeaker 1: Later.", TranscriptParagraph.plain(paras))
        // Segments without times cannot hold a bookmark by time; the press goes to the last paragraph.
        val untimed = """{"segments":[{"text":"no times"}],"highlights":[{"at":1.0}]}"""
        assertEquals(listOf(p(null, "no times", bookmarks = listOf(0))), TranscriptParagraph.derive(untimed))
    }

    @Test
    fun renderingsMatchTheServerFormatters() {
        // test_speaker_turns_become_paragraphs_without_timestamps
        val paras = listOf(
            p("Speaker 1", "Hello there. How are you?", 0.0, 4.0),
            p("Speaker 2", "Fine. And you?", 4.0, 7.0),
            p("Speaker 1", "Good.", 7.0, 9.0)
        )
        val md = TranscriptParagraph.markdown(paras)
        assertTrue(md.startsWith("**Speaker 1:** Hello there. How are you?\n\n**Speaker 2:** Fine."))
        assertTrue(!md.replace("**Speaker 1:**", "").replace("**Speaker 2:**", "").contains(":"))
        assertEquals("Speaker 1: Hello there. How are you?", TranscriptParagraph.plain(paras).lines()[0])
        // test_edge_cases
        assertEquals("", TranscriptParagraph.markdown(emptyList()))
        assertEquals("", TranscriptParagraph.plain(emptyList()))
    }
}
