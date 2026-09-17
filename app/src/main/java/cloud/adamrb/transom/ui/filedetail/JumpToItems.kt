package cloud.adamrb.transom.ui.filedetail

import cloud.adamrb.transom.export.TranscriptHighlight
import cloud.adamrb.transom.export.TranscriptMarkdown
import cloud.adamrb.transom.export.TranscriptParagraph

/**
 * One entry of the Jump to sheet: a moment in the recording ([seconds]) and the paragraph to
 * reveal for it ([paragraphIndex], -1 when the document's paragraphs carry no times). [label] is
 * the row's text (the highlight's words, the speaker's name, or nothing for a time mark).
 */
data class JumpToItem(
    val kind: Kind,
    val seconds: Double,
    val paragraphIndex: Int,
    val label: String
) {
    enum class Kind { BOOKMARK, SPEAKER, TIME_MARK }

    val timeLabel: String get() = TranscriptMarkdown.formatTimestamp(seconds)
}

/** Builds the Jump to sheet's sections from a transcript document. Pure Kotlin, no android.*. */
object JumpToItems {

    /** Time marks are laid down every this many seconds. */
    const val TIME_MARK_INTERVAL_S = 600.0

    /**
     * Bookmarks (one per recorder button press, in time order), speaker changes (the first
     * paragraph of each run of one speaker, skipping the very first paragraph, which is where the
     * recording starts anyway) and a mark every ten minutes up to the recording's length. Each
     * section is ordered by time; a recording shorter than ten minutes gets no time marks.
     */
    fun build(
        paragraphs: List<TranscriptParagraph>,
        highlights: List<TranscriptHighlight>,
        durationSeconds: Long
    ): List<JumpToItem> {
        val items = mutableListOf<JumpToItem>()
        highlights.forEachIndexed { i, h ->
            val index = paragraphs.indexOfFirst { i in it.bookmarks }.takeIf { it >= 0 }
                ?: paragraphIndexAt(paragraphs, h.at)
            items += JumpToItem(JumpToItem.Kind.BOOKMARK, h.at, index, h.text)
        }
        var previous: String? = null
        for ((i, p) in paragraphs.withIndex()) {
            val speaker = p.speaker
            // Only paragraphs with a time: an entry seeks the player, and a change with no time
            // would rewind to the start instead.
            val start = p.start
            if (speaker != null && speaker != previous && i > 0 && start != null) {
                items += JumpToItem(JumpToItem.Kind.SPEAKER, start, i, speaker)
            }
            previous = speaker
        }
        var t = TIME_MARK_INTERVAL_S
        while (t < durationSeconds) {
            items += JumpToItem(JumpToItem.Kind.TIME_MARK, t, paragraphIndexAt(paragraphs, t), "")
            t += TIME_MARK_INTERVAL_S
        }
        return items
    }

    /**
     * The paragraph to show for a moment: the one whose span holds it (start inclusive, end
     * exclusive, so a moment on the seam belongs to the paragraph that starts there, as for the
     * now-playing row), else the first one that starts after it, else the last; -1 when no
     * paragraph carries a time.
     */
    fun paragraphIndexAt(paragraphs: List<TranscriptParagraph>, seconds: Double): Int {
        if (paragraphs.none { it.start != null }) return -1
        paragraphs.indexOfFirst { it.start != null && it.end != null && it.start <= seconds && seconds < it.end }
            .takeIf { it >= 0 }?.let { return it }
        paragraphs.indexOfFirst { it.start != null && it.start >= seconds }.takeIf { it >= 0 }?.let { return it }
        return paragraphs.lastIndex
    }
}
