package org.plaudbridge.app.ui.recordings

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.google.android.material.R as MaterialR
import org.plaudbridge.app.R
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.ui.common.themeColor
import org.plaudbridge.app.ui.list.DateGroupedAdapter
import org.plaudbridge.app.ui.list.DateGrouping
import java.util.concurrent.Executor

/**
 * Merged recordings as rows. The meta line is "Today 6:47 PM  ·  4m 12s", then a status word
 * only while something is still happening to the recording (Failed and Upload failed in red),
 * then a star count when it carries button marks. A finished recording shows time and duration
 * only. During a search, a row that matched on text the title does not show gets a one-line
 * snippet with the term in bold.
 */
class RecordingsAdapter(
    private val onTapped: (RecordingItem) -> Unit,
    private val onActions: (RecordingItem) -> Unit,
    diffExecutor: Executor? = null
) : DateGroupedAdapter<RecordingItem>(timestamp = { it.recordedAt }, key = { it.key }, diffExecutor = diffExecutor) {

    /** The search the shown rows were filtered by; drives the snippets. */
    var query: String? = null
        private set

    /**
     * Replace the rows. A changed [query] rebinds the labels of every row that survives the
     * diff, since the same item may now need a snippet (or no longer need one).
     */
    fun submit(items: List<RecordingItem>, query: String?) {
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        val queryChanged = q != this.query
        this.query = q
        if (queryChanged && itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_LABELS)
        submit(items)
    }

    override fun rowName(context: Context, item: RecordingItem): CharSequence = rowTitle(context, item)

    override fun rowMeta(context: Context, item: RecordingItem): CharSequence = metaText(context, item)

    override fun rowSnippet(context: Context, item: RecordingItem): CharSequence? {
        val snippet = snippetFor(item, query, shownTitle = rowTitle(context, item)) ?: return null
        val text = SpannableString(snippet.text)
        if (snippet.hasMatch) {
            text.setSpan(StyleSpan(Typeface.BOLD), snippet.matchStart, snippet.matchEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    override fun rowFootnote(context: Context, item: RecordingItem): CharSequence? = automationsText(context, item)

    override fun onRowTapped(item: RecordingItem) = onTapped(item)

    override fun onRowLongPressed(item: RecordingItem): Boolean {
        onActions(item)
        return true
    }

    override fun onRowMore(item: RecordingItem) = onActions(item)

    override fun hasRowActions(item: RecordingItem): Boolean = true

    companion object {
        /**
         * The row's name. A recording in which the server heard no speech has no title of its own
         * (nothing to make one from); rather than the phone's "Untitled Recording" or the server's
         * file name, the row says why. Search still matches the plain [RecordingItem.title].
         */
        fun rowTitle(context: Context, item: RecordingItem): String =
            if (item.isNoSpeech && !item.hasTitle) context.getString(R.string.no_speech_title) else item.title

        /** Status words for a row, or null when there is nothing to report. */
        fun statusText(context: Context, item: RecordingItem): String? = when (item.status) {
            RecordingItem.Status.NONE -> null
            RecordingItem.Status.DOWNLOADING -> context.getString(R.string.status_downloading)
            RecordingItem.Status.UPLOADING -> context.getString(R.string.status_uploading)
            RecordingItem.Status.UPLOAD_FAILED -> context.getString(R.string.status_upload_failed)
            RecordingItem.Status.TRANSCRIBING -> transcribingText(context, item.server)
            RecordingItem.Status.FAILED -> context.getString(R.string.status_failed)
        }

        /** Statuses whose word is drawn in the failure colour. */
        fun isFailureStatus(status: RecordingItem.Status): Boolean =
            status == RecordingItem.Status.FAILED || status == RecordingItem.Status.UPLOAD_FAILED

        /**
         * What the server is doing right now, in user words: waiting in the queue, transcribing
         * (with the percentage when the server reports one, rounded down, never 100 while still
         * going), identifying speakers, or summarizing. Older servers send no stage; then it is
         * just "Transcribing".
         */
        fun transcribingText(context: Context, rec: ServerRecording?): String {
            if (rec == null) return context.getString(R.string.status_transcribing)
            if (rec.status == ServerRecording.STATUS_PENDING) return context.getString(R.string.status_waiting_to_transcribe)
            return when (rec.stage) {
                ServerRecording.STAGE_QUEUED -> context.getString(R.string.status_waiting_to_transcribe)
                ServerRecording.STAGE_DIARIZING -> context.getString(R.string.status_identifying_speakers)
                ServerRecording.STAGE_SUMMARIZING -> context.getString(R.string.status_summarizing)
                else -> rec.progressPercent?.let { context.getString(R.string.status_transcribing_percent_fmt, it) }
                    ?: context.getString(R.string.status_transcribing)
            }
        }

        /** The meta line as plain text (what tests compare against). */
        fun metaLine(context: Context, item: RecordingItem): String = buildString {
            append(DateGrouping.formatDateTime(item.recordedAt))
            append(DateGrouping.SEPARATOR)
            append(DateGrouping.formatDuration(item.durationSeconds))
            statusText(context, item)?.let {
                append(DateGrouping.SEPARATOR)
                append(it)
            }
            if (item.marksCount > 0) {
                append(DateGrouping.SEPARATOR)
                append("★ ${item.marksCount}")
            }
        }

        /**
         * The automations line under a row: the server's one-liner ("Vault notes: Filed:
         * Life/Topics/Dogs.md"), the route name in the body colour so it reads as a label, the
         * whole line in the error colour when the automation failed or never reported. Null when
         * the automations never ran for the recording (most rows), so the row stays two lines.
         */
        fun automationsText(context: Context, item: RecordingItem): CharSequence? {
            val a = item.automations ?: return null
            val line = a.line.takeIf { it.isNotBlank() } ?: return null
            val text = SpannableString(line)
            if (a.isFailure) {
                text.setSpan(ForegroundColorSpan(context.themeColor(MaterialR.attr.colorError)), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                return text
            }
            // Bold each route label ("Vault notes:") so the eye finds what ran before what it did.
            for (it in a.items) {
                val label = it.routeName.takeIf { n -> n.isNotBlank() }?.plus(":") ?: continue
                val start = line.indexOf(label)
                if (start >= 0) text.setSpan(StyleSpan(Typeface.BOLD), start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return text
        }

        /** [metaLine] with the status word coloured when it is a failure. */
        fun metaText(context: Context, item: RecordingItem): CharSequence {
            val line = metaLine(context, item)
            if (!isFailureStatus(item.status)) return line
            val word = statusText(context, item) ?: return line
            val start = line.indexOf(word)
            if (start < 0) return line
            return SpannableString(line).apply {
                setSpan(
                    ForegroundColorSpan(context.themeColor(MaterialR.attr.colorError)),
                    start, start + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        /**
         * The search snippet for a row, or null when there is no search or the title the row
         * SHOWS ([shownTitle], see [rowTitle]) already contains the match. A no-speech row shows
         * "No speech detected" while search matched its hidden plain title or file name, so that
         * hidden name is the first snippet source: the user sees why the row is in the results.
         * A row that came back from a server-side search carries the server's own
         * `match_snippet` (CONTRACTS §4), which wins over anything derived here.
         */
        fun snippetFor(item: RecordingItem, query: String?, shownTitle: String = item.title): SearchSnippet.Snippet? =
            SearchSnippet.derive(
                query = query,
                title = shownTitle,
                bodies = listOf(
                    item.title.takeIf { it != shownTitle },
                    item.server?.textPreview, item.server?.summary, item.local?.summaryText
                ),
                serverSnippet = item.server?.matchSnippet
            )
    }
}
