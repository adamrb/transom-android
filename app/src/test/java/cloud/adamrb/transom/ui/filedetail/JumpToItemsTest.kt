package cloud.adamrb.transom.ui.filedetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import cloud.adamrb.transom.export.TranscriptHighlight
import cloud.adamrb.transom.export.TranscriptParagraph

class JumpToItemsTest {

    private val paragraphs = listOf(
        TranscriptParagraph("Speaker 1", "Opening.", 0.0, 300.0),
        TranscriptParagraph("Speaker 1", "Still opening.", 300.0, 650.0),
        TranscriptParagraph("Speaker 2", "Reply.", 650.0, 700.0, bookmarks = listOf(0)),
        TranscriptParagraph("Speaker 1", "Back.", 700.0, 1300.0),
        TranscriptParagraph("Speaker 1", "Closing.", 1300.0, 1400.0)
    )
    private val highlights = listOf(
        TranscriptHighlight(at = 660.0, start = 650.0, end = 700.0, text = "Reply."),
        TranscriptHighlight(at = 1350.0, start = 1350.0, end = 1350.0, text = "")
    )

    @Test
    fun bookmarksSpeakerChangesAndTenMinuteMarksEachInTimeOrder() {
        val items = JumpToItems.build(paragraphs, highlights, durationSeconds = 1400)
        val bookmarks = items.filter { it.kind == JumpToItem.Kind.BOOKMARK }
        val speakers = items.filter { it.kind == JumpToItem.Kind.SPEAKER }
        val marks = items.filter { it.kind == JumpToItem.Kind.TIME_MARK }

        // One per highlight: the paragraph named by its bookmark index, else the one at its time.
        assertEquals(listOf(660.0, 1350.0), bookmarks.map { it.seconds })
        assertEquals(listOf(2, 4), bookmarks.map { it.paragraphIndex })
        assertEquals(listOf("Reply.", ""), bookmarks.map { it.label })
        assertEquals("11:00", bookmarks[0].timeLabel)

        // Speaker changes skip the very first paragraph (that is where the recording starts).
        assertEquals(listOf("Speaker 2", "Speaker 1"), speakers.map { it.label })
        assertEquals(listOf(2, 3), speakers.map { it.paragraphIndex })
        assertEquals(listOf(650.0, 700.0), speakers.map { it.seconds })

        // Every ten minutes, strictly inside the recording.
        assertEquals(listOf(600.0, 1200.0), marks.map { it.seconds })
        assertEquals(listOf("10:00", "20:00"), marks.map { it.timeLabel })
        assertEquals(listOf(1, 3), marks.map { it.paragraphIndex })
    }

    @Test
    fun shortRecordingsGetNoTimeMarksAndOneSpeakerGetsNoSpeakerEntries() {
        val items = JumpToItems.build(paragraphs.take(2), emptyList(), durationSeconds = 599)
        assertTrue(items.isEmpty())
        // Exactly ten minutes long: no mark at the very end either.
        assertTrue(JumpToItems.build(paragraphs.take(2), emptyList(), durationSeconds = 600).isEmpty())
    }

    @Test
    fun paragraphIndexAtFallsForwardThenToTheLastParagraph() {
        assertEquals(0, JumpToItems.paragraphIndexAt(paragraphs, 100.0))
        assertEquals(2, JumpToItems.paragraphIndexAt(paragraphs, 650.0))
        assertEquals(4, JumpToItems.paragraphIndexAt(paragraphs, 5000.0))
        // A gap between paragraphs resolves to the next one.
        val gappy = listOf(TranscriptParagraph("A", "x", 0.0, 10.0), TranscriptParagraph("B", "y", 20.0, 30.0))
        assertEquals(1, JumpToItems.paragraphIndexAt(gappy, 15.0))
        // No times at all: nothing to reveal.
        assertEquals(-1, JumpToItems.paragraphIndexAt(listOf(TranscriptParagraph(null, "flat", null, null)), 5.0))
        assertEquals(-1, JumpToItems.paragraphIndexAt(emptyList(), 5.0))
    }

    @Test
    fun untimedParagraphsStillListBookmarksButNoSpeakerChanges() {
        // A speaker entry seeks the player; without a time it would rewind to the start, so none is offered.
        val flat = listOf(TranscriptParagraph("Speaker 1", "a", null, null), TranscriptParagraph("Speaker 2", "b", null, null))
        val items = JumpToItems.build(flat, listOf(TranscriptHighlight(5.0, 5.0, 5.0, "b")), durationSeconds = 10)
        assertEquals(listOf(JumpToItem.Kind.BOOKMARK), items.map { it.kind })
        assertEquals(-1, items[0].paragraphIndex)
        assertEquals(5.0, items[0].seconds, 0.0)
    }
}
