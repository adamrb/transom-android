package io.github.adamrb.transom.ui.filedetail

import io.github.adamrb.transom.export.TranscriptMarkdown
import io.github.adamrb.transom.export.TranscriptParagraph

/**
 * One row of the transcript list: a [TranscriptParagraph] plus what the row shows around it.
 * [showSpeaker] is true only where the speaker changes (consecutive paragraphs by one speaker
 * share a label); [timeLabel] is the paragraph's start as "m:ss" / "h:mm:ss" for the time chip,
 * null when the document carries no times (an old cache) and the chip is hidden.
 */
data class TranscriptRow(
    val index: Int,
    val paragraph: TranscriptParagraph,
    val showSpeaker: Boolean,
    val timeLabel: String?
) {
    val speaker: String? get() = paragraph.speaker
    val text: String get() = paragraph.text
    val isBookmarked: Boolean get() = paragraph.isBookmarked
}

/** Pure mapping from paragraphs to rows and from a playhead to the row it falls in. */
object TranscriptRows {

    fun build(paragraphs: List<TranscriptParagraph>): List<TranscriptRow> {
        var previous: String? = null
        return paragraphs.mapIndexed { i, p ->
            val show = p.speaker != null && p.speaker != previous
            previous = p.speaker
            TranscriptRow(i, p, show, p.start?.let { TranscriptMarkdown.formatTimestamp(it) })
        }
    }

    /**
     * The paragraph the playhead at [seconds] is in: the one whose span contains it, else the
     * last one that started before it (silence between turns still belongs to the turn before),
     * else -1 before the first paragraph or when no paragraph carries a time. Paragraphs are in
     * document order with non-decreasing starts, so the scan is a simple walk.
     */
    fun nowPlayingIndex(paragraphs: List<TranscriptParagraph>, seconds: Double): Int {
        var result = -1
        for ((i, p) in paragraphs.withIndex()) {
            val start = p.start ?: continue
            if (start > seconds) break
            result = i
            val end = p.end
            if (end != null && seconds < end) break
        }
        return result
    }
}
