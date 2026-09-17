package cloud.adamrb.transom.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TranscriptHighlight.parse: the server's "highlights" array out of a transcript document, with
 * malformed rows dropped individually and every non-object / no-array shape reading as "none".
 * Robolectric because the parser uses org.json, which is a stub on the plain unit-test JVM.
 */
@RunWith(RobolectricTestRunner::class)
class TranscriptHighlightTest {

    @Test
    fun parsesServerShape() {
        val json = """{"text":"x","segments":[],"marks":[6.0],
            "highlights":[{"at":6.0,"start":4.2,"end":12.9,"speakers":["Speaker 1"],"text":"  Ship it. "}]}"""
        assertEquals(
            listOf(TranscriptHighlight(at = 6.0, start = 4.2, end = 12.9, text = "Ship it.")),
            TranscriptHighlight.parse(json)
        )
    }

    @Test
    fun missingStartEndFallBackToAt() {
        val json = """{"highlights":[{"at":30,"text":""}]}"""
        assertEquals(listOf(TranscriptHighlight(30.0, 30.0, 30.0, "")), TranscriptHighlight.parse(json))
    }

    @Test
    fun rowsWithoutAtAreDroppedOthersKept() {
        val json = """{"highlights":[{"text":"no at"},{"at":1.5,"text":"ok"},"garbage",null]}"""
        assertEquals(listOf(TranscriptHighlight(1.5, 1.5, 1.5, "ok")), TranscriptHighlight.parse(json))
    }

    @Test
    fun absentArrayNonObjectAndGarbageAreEmpty() {
        assertTrue(TranscriptHighlight.parse("""{"text":"x"}""").isEmpty())
        assertTrue(TranscriptHighlight.parse("""{"highlights":null}""").isEmpty())
        assertTrue(TranscriptHighlight.parse("""{"highlights":"nope"}""").isEmpty())
        assertTrue(TranscriptHighlight.parse("""[{"start":0,"text":"bare segment array"}]""").isEmpty())
        assertTrue(TranscriptHighlight.parse("<html>login</html>").isEmpty())
    }
}
