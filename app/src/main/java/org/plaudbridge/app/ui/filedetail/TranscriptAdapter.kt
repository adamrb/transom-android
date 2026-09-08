package org.plaudbridge.app.ui.filedetail

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.plaudbridge.app.R
import org.plaudbridge.app.databinding.ItemTranscriptParagraphBinding
import org.plaudbridge.app.ui.common.themeColor

/**
 * The detail page as a list: position 0 is the page's header block (summary, highlights,
 * automations, the Transcript section header and the empty state, a view the activity owns and
 * fills in directly), every position after it one transcript paragraph, so a two-hour transcript
 * is measured and drawn a screen at a time instead of as one 150k-character TextView.
 *
 * Paragraph rows show the speaker only where it changes, a time chip that plays from the
 * paragraph's start ([onSeek]), the bookmark star and bar where a recorder button was pressed,
 * and selectable body text. The row being played ([nowPlayingIndex]) and the row a highlight or
 * Jump to entry just revealed ([flashIndex]) are tinted.
 */
class TranscriptAdapter(
    private val headerView: View,
    private val onSeek: (seconds: Double) -> Unit,
    private val onSpeakerTap: (speaker: String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<TranscriptRow> = emptyList()
        private set

    /** Speaker labels open the rename dialog only when there is a server to rename on. */
    var speakerRenameEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (rows.isNotEmpty()) notifyItemRangeChanged(HEADER_COUNT, rows.size)
        }

    /** Paragraph index (not adapter position) of the row being played, -1 for none. */
    var nowPlayingIndex: Int = -1
        private set

    /** Paragraph index of the row briefly tinted after a jump, -1 for none. */
    var flashIndex: Int = -1
        private set

    fun submit(rows: List<TranscriptRow>) {
        val old = this.rows
        this.rows = rows
        nowPlayingIndex = -1
        flashIndex = -1
        if (old.size == rows.size && rows.isNotEmpty()) notifyItemRangeChanged(HEADER_COUNT, rows.size)
        else notifyDataSetChanged()
    }

    fun setNowPlaying(index: Int) {
        if (index == nowPlayingIndex) return
        val previous = nowPlayingIndex
        nowPlayingIndex = index
        if (previous in rows.indices) notifyItemChanged(positionOf(previous))
        if (index in rows.indices) notifyItemChanged(positionOf(index))
    }

    fun setFlash(index: Int) {
        if (index == flashIndex) return
        val previous = flashIndex
        flashIndex = index
        if (previous in rows.indices) notifyItemChanged(positionOf(previous))
        if (index in rows.indices) notifyItemChanged(positionOf(index))
    }

    fun positionOf(paragraphIndex: Int): Int = paragraphIndex + HEADER_COUNT

    fun paragraphIndexOf(position: Int): Int = position - HEADER_COUNT

    override fun getItemCount(): Int = HEADER_COUNT + rows.size

    override fun getItemViewType(position: Int): Int = if (position < HEADER_COUNT) TYPE_HEADER else TYPE_PARAGRAPH

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) HeaderHolder(headerView)
        else ParagraphHolder(ItemTranscriptParagraphBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ParagraphHolder) {
            val index = paragraphIndexOf(position)
            holder.bind(rows[index], index == nowPlayingIndex, index == flashIndex, speakerRenameEnabled)
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view)

    inner class ParagraphHolder(private val b: ItemTranscriptParagraphBinding) : RecyclerView.ViewHolder(b.root) {
        private val accent = b.root.themeColor(R.attr.pbColorHighlightAccent)
        private val nowPlayingTint = b.root.themeColor(R.attr.pbColorSelectionTint)
        private val flashTint = b.root.themeColor(R.attr.pbColorHighlightFlash)

        fun bind(row: TranscriptRow, nowPlaying: Boolean, flash: Boolean, renameEnabled: Boolean) {
            val speaker = row.speaker
            b.speakerLabel.visibility = if (row.showSpeaker && speaker != null) View.VISIBLE else View.GONE
            b.speakerLabel.text = speaker
            b.speakerLabel.contentDescription = if (renameEnabled) b.root.context.getString(R.string.detail_rename_speaker_cd) else speaker
            // setOnClickListener (even with null) turns clickable on, so the flags come after it.
            b.speakerLabel.setOnClickListener(if (renameEnabled && speaker != null) View.OnClickListener { onSpeakerTap(speaker) } else null)
            b.speakerLabel.isClickable = renameEnabled
            b.speakerLabel.isFocusable = renameEnabled

            val time = row.timeLabel
            val start = row.paragraph.start
            b.timeChip.visibility = if (time != null) View.VISIBLE else View.GONE
            b.timeChip.text = time
            b.timeChip.contentDescription = time?.let { b.root.context.getString(R.string.detail_seek_to_fmt, it) }
            b.timeChip.setOnClickListener(if (start != null) View.OnClickListener { onSeek(start) } else null)
            b.metaRow.visibility = if (b.speakerLabel.visibility == View.VISIBLE || time != null) View.VISIBLE else View.GONE

            b.bookmarkBar.visibility = if (row.isBookmarked) View.VISIBLE else View.GONE
            b.paragraphText.text = if (row.isBookmarked) {
                SpannableStringBuilder(FileDetailActivity.BOOKMARK_STAR).apply {
                    setSpan(ForegroundColorSpan(accent), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(row.text)
                }
            } else row.text
            b.paragraphRoot.setBackgroundColor(
                when {
                    flash -> flashTint
                    nowPlaying -> nowPlayingTint
                    else -> Color.TRANSPARENT
                }
            )
        }
    }

    companion object {
        const val HEADER_COUNT = 1
        const val TYPE_HEADER = 0
        const val TYPE_PARAGRAPH = 1
    }
}
