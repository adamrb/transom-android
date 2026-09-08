package org.plaudbridge.app.ui.recordings

import android.content.Context
import org.plaudbridge.app.R
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.ui.list.DateGroupedAdapter
import org.plaudbridge.app.ui.list.DateGrouping

/**
 * Merged recordings drawn with the Files rows. The meta line is "MMM d  ·  HH:mm  ·  Xm Ys",
 * then a status word only while something is still happening to the recording, then a star
 * count when it carries button marks. A finished recording shows date, time and duration only.
 */
class RecordingsAdapter(
    private val onTapped: (RecordingItem) -> Unit,
    private val onLongPressed: (RecordingItem) -> Unit
) : DateGroupedAdapter<RecordingItem>(timestamp = { it.recordedAt }) {

    override fun rowName(context: Context, item: RecordingItem): String = rowTitle(context, item)

    override fun rowMeta(context: Context, item: RecordingItem): String = metaLine(context, item)

    override fun onRowTapped(item: RecordingItem) = onTapped(item)

    override fun onRowLongPressed(item: RecordingItem): Boolean {
        onLongPressed(item)
        return true
    }

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
            RecordingItem.Status.TRANSCRIBING -> transcribingText(context, item.server)
            RecordingItem.Status.FAILED -> context.getString(R.string.status_failed)
        }

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
    }
}
