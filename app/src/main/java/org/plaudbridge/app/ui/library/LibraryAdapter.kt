package org.plaudbridge.app.ui.library

import android.content.Context
import org.plaudbridge.app.R
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.ui.list.DateGroupedAdapter
import org.plaudbridge.app.ui.list.DateGrouping

/**
 * Server recordings drawn with the Files tab's rows. The meta line starts exactly like Files
 * ("MMM d  ·  HH:mm  ·  Xm Ys") and only adds what the server knows and Files cannot: a status
 * word while a recording is not transcribed yet, and a star count when it carries button marks.
 */
class LibraryAdapter(
    private val onTapped: (ServerRecording) -> Unit,
    private val onLongPressed: (ServerRecording) -> Unit
) : DateGroupedAdapter<ServerRecording>(timestamp = { it.recordedAt }) {

    override fun rowName(item: ServerRecording): String = item.displayTitle

    override fun rowMeta(context: Context, item: ServerRecording): String = metaLine(context, item)

    override fun onRowTapped(item: ServerRecording) = onTapped(item)

    override fun onRowLongPressed(item: ServerRecording): Boolean {
        onLongPressed(item)
        return true
    }

    companion object {
        /** Status word for a row, or null for "done" (a finished recording needs no label). */
        fun statusLabelRes(status: String): Int? = when (status) {
            ServerRecording.STATUS_DONE -> null
            ServerRecording.STATUS_TRANSCRIBING -> R.string.status_transcribing
            ServerRecording.STATUS_PENDING -> R.string.status_pending
            ServerRecording.STATUS_FAILED -> R.string.status_failed
            ServerRecording.STATUS_STORED -> R.string.status_stored
            else -> null
        }

        fun metaLine(context: Context, item: ServerRecording): String = buildString {
            append(DateGrouping.formatDateTime(item.recordedAt))
            append(DateGrouping.SEPARATOR)
            append(DateGrouping.formatDuration(item.durationSeconds))
            val statusRes = statusLabelRes(item.status)
            if (statusRes != null) {
                append(DateGrouping.SEPARATOR)
                append(context.getString(statusRes))
            } else if (item.status != ServerRecording.STATUS_DONE && item.status.isNotBlank()) {
                // Unknown status from a newer server: show it rather than hide it.
                append(DateGrouping.SEPARATOR)
                append(item.status)
            }
            if (item.marksCount > 0) {
                append(DateGrouping.SEPARATOR)
                append("★ ${item.marksCount}")
            }
        }
    }
}
