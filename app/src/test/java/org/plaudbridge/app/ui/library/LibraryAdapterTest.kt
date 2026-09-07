package org.plaudbridge.app.ui.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.ui.list.DateGrouping
import org.robolectric.RobolectricTestRunner

/** The Library row meta line: Files format first, then status (only when not done) and marks. */
@RunWith(RobolectricTestRunner::class)
class LibraryAdapterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun rec(status: String, marks: String = "[]", durationS: Double = 61.0) = ServerRecording.fromJson(
        JSONObject("""{"id":"r","filename":"r.mp3","status":"$status","duration_s":$durationS,
            "started_at":"2026-09-07T05:27:31Z","marks":$marks}""")
    )

    @Test
    fun doneRowShowsOnlyDateTimeAndDuration() {
        val r = rec("done")
        val expected = DateGrouping.formatDateTime(r.recordedAt) + DateGrouping.SEPARATOR + "1m 1s"
        assertEquals(expected, LibraryAdapter.metaLine(context, r))
    }

    @Test
    fun nonDoneStatusesAppendAWord() {
        assertTrue(LibraryAdapter.metaLine(context, rec("transcribing")).endsWith("Transcribing…"))
        assertTrue(LibraryAdapter.metaLine(context, rec("pending")).endsWith("Pending"))
        assertTrue(LibraryAdapter.metaLine(context, rec("failed")).endsWith("Failed"))
        assertTrue(LibraryAdapter.metaLine(context, rec("stored")).endsWith("Stored"))
        assertFalse(LibraryAdapter.metaLine(context, rec("done")).contains("Transcribed"))
    }

    @Test
    fun marksAppendStarCount() {
        val line = LibraryAdapter.metaLine(context, rec("done", marks = "[6.0, 125.5]"))
        assertTrue(line, line.endsWith(DateGrouping.SEPARATOR + "★ 2"))
        val pendingWithMarks = LibraryAdapter.metaLine(context, rec("pending", marks = "[6.0]"))
        assertTrue(pendingWithMarks, pendingWithMarks.endsWith("Pending" + DateGrouping.SEPARATOR + "★ 1"))
    }
}
