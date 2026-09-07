package org.plaudbridge.app.ui.recordings

import android.content.Context
import org.plaudbridge.app.R
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

    override fun rowName(item: RecordingItem): String = item.title

    override fun rowMeta(context: Context, item: RecordingItem): String = metaLine(context, item)

    override fun onRowTapped(item: RecordingItem) = onTapped(item)

    override fun onRowLongPressed(item: RecordingItem): Boolean {
        onLongPressed(item)
        return true
    }

    companion object {
        /** Status word for a row, or null when there is nothing to report. */
        fun statusLabelRes(status: RecordingItem.Status): Int? = when (status) {
            RecordingItem.Status.NONE -> null
            RecordingItem.Status.DOWNLOADING -> R.string.status_downloading
            RecordingItem.Status.UPLOADING -> R.string.status_uploading
            RecordingItem.Status.TRANSCRIBING -> R.string.status_transcribing
            RecordingItem.Status.FAILED -> R.string.status_failed
        }

        fun metaLine(context: Context, item: RecordingItem): String = buildString {
            append(DateGrouping.formatDateTime(item.recordedAt))
            append(DateGrouping.SEPARATOR)
            append(DateGrouping.formatDuration(item.durationSeconds))
            statusLabelRes(item.status)?.let {
                append(DateGrouping.SEPARATOR)
                append(context.getString(it))
            }
            if (item.marksCount > 0) {
                append(DateGrouping.SEPARATOR)
                append("★ ${item.marksCount}")
            }
        }
    }
}
