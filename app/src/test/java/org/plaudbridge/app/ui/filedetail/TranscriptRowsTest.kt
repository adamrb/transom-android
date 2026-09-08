package org.plaudbridge.app.ui.filedetail

import org.junit.Assert.assertEquals
import org.junit.Test
import org.plaudbridge.app.export.TranscriptParagraph

class TranscriptRowsTest {

    private val paragraphs = listOf(
        TranscriptParagraph("Speaker 1", "Hello there.", 0.0, 4.0),
        TranscriptParagraph("Speaker 1", "Still me.", 4.0, 7.0),
        TranscriptParagraph("Speaker 2", "Hi.", 7.0, 9.0, bookmarks = listOf(0)),
        TranscriptParagraph(null, "Untagged.", 3600.0, 3605.0),
        TranscriptParagraph("Speaker 2", "Back.", 3610.0, 3612.0)
    )

    @Test
    fun rowsShowTheSpeakerOnlyWhereItChangesAndCarryTimeChips() {
        val rows = TranscriptRows.build(paragraphs)
        assertEquals(listOf(0, 1, 2, 3, 4), rows.map { it.index })
        assertEquals(listOf(true, false, true, false, true), rows.map { it.showSpeaker })
        assertEquals(listOf("0:00", "0:04", "0:07", "1:00:00", "1:00:10"), rows.map { it.timeLabel })
        assertEquals(listOf(false, false, true, false, false), rows.map { it.isBookmarked })
        assertEquals("Hi.", rows[2].text)
    }

    @Test
    fun paragraphsWithoutTimesHaveNoChip() {
        val rows = TranscriptRows.build(listOf(TranscriptParagraph(null, "Flat text.", null, null)))
        assertEquals(listOf<String?>(null), rows.map { it.timeLabel })
        assertEquals(false, rows[0].showSpeaker)
    }

    @Test
    fun nowPlayingIndexIsTheParagraphHoldingThePlayhead() {
        assertEquals(-1, TranscriptRows.nowPlayingIndex(paragraphs, -1.0))
        assertEquals(0, TranscriptRows.nowPlayingIndex(paragraphs, 0.0))
        assertEquals(0, TranscriptRows.nowPlayingIndex(paragraphs, 3.9))
        assertEquals(1, TranscriptRows.nowPlayingIndex(paragraphs, 4.0))
        assertEquals(2, TranscriptRows.nowPlayingIndex(paragraphs, 8.5))
        // Silence after a paragraph still belongs to it, until the next one starts.
        assertEquals(2, TranscriptRows.nowPlayingIndex(paragraphs, 1000.0))
        assertEquals(3, TranscriptRows.nowPlayingIndex(paragraphs, 3600.0))
        assertEquals(4, TranscriptRows.nowPlayingIndex(paragraphs, 99_999.0))
    }

    @Test
    fun nowPlayingSkipsParagraphsWithoutTimes() {
        val mixed = listOf(
            TranscriptParagraph(null, "No time.", null, null),
            TranscriptParagraph("Speaker 1", "Timed.", 10.0, 12.0)
        )
        assertEquals(-1, TranscriptRows.nowPlayingIndex(mixed, 5.0))
        assertEquals(1, TranscriptRows.nowPlayingIndex(mixed, 11.0))
        assertEquals(-1, TranscriptRows.nowPlayingIndex(emptyList(), 11.0))
    }
}
