package org.plaudbridge.app.ui.recordings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.ui.list.DateGrouping
import org.robolectric.RobolectricTestRunner

/**
 * The Recordings row meta line: Files format first, then a status word only while something is
 * still happening, then the star count. Never "Synced", "Uploaded", "Stored" or "Transcribed".
 */
@RunWith(RobolectricTestRunner::class)
class RecordingsAdapterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun server(status: String, marks: String = "[]", durationS: Double = 61.0) = ServerRecording.fromJson(
        JSONObject("""{"id":"r","device_sn":"SN-A","session_id":1,"filename":"r.mp3","status":"$status",
            "duration_s":$durationS,"started_at":"2026-09-07T05:27:31Z","marks":$marks}""")
    )

    private fun local(localPath: String?, uploaded: Boolean, marks: List<Double>? = null) = RecordingFile(
        sessionId = 1, deviceSN = "SN-A", name = "Untitled Recording", duration = 61,
        createdAt = ServerRecording.parseIso("2026-09-07T05:27:31Z")!!, localPath = localPath, uploaded = uploaded,
        marks = marks
    )

    private fun meta(item: RecordingItem) = RecordingsAdapter.metaLine(context, item)

    @Test
    fun doneRowShowsOnlyDateTimeAndDuration() {
        val item = RecordingItem(null, server("done"))
        val expected = DateGrouping.formatDateTime(item.recordedAt) + DateGrouping.SEPARATOR + "1m 1s"
        assertEquals(expected, meta(item))
    }

    @Test
    fun matchedDoneRowShowsNoStatusEvenWhenThePhoneStillSaysNotUploaded() {
        val item = RecordingItem(local("/tmp/1.opus", uploaded = false), server("done"))
        val expected = DateGrouping.formatDateTime(item.recordedAt) + DateGrouping.SEPARATOR + "1m 1s"
        assertEquals(expected, meta(item))
    }

    @Test
    fun serverStatusWords() {
        assertTrue(meta(RecordingItem(null, server("transcribing"))).endsWith("Transcribing…"))
        assertTrue(meta(RecordingItem(null, server("pending"))).endsWith("Transcribing…"))
        assertTrue(meta(RecordingItem(null, server("failed"))).endsWith("Failed"))
        assertTrue(meta(RecordingItem(null, server("stored"))).endsWith("1m 1s"))
    }

    @Test
    fun phoneOnlyStatusWords() {
        assertTrue(meta(RecordingItem(local(null, uploaded = false), null)).endsWith("Downloading"))
        assertTrue(meta(RecordingItem(local("/tmp/1.opus", uploaded = false), null)).endsWith("Uploading"))
        assertTrue(meta(RecordingItem(local("/tmp/1.opus", uploaded = true), null)).endsWith("1m 1s"))
    }

    @Test
    fun implementationWordsNeverAppear() {
        val rows = listOf(
            RecordingItem(null, server("done")),
            RecordingItem(null, server("stored")),
            RecordingItem(local("/tmp/1.opus", uploaded = true), null),
            RecordingItem(local("/tmp/1.opus", uploaded = true), server("done"))
        )
        for (row in rows) {
            val line = meta(row)
            for (word in listOf("Synced", "Uploaded", "Stored", "Transcribed", "On device", "Upload pending")) {
                assertFalse("'$word' in '$line'", line.contains(word))
            }
        }
    }

    @Test
    fun marksAppendStarCount() {
        val line = meta(RecordingItem(null, server("done", marks = "[6.0, 125.5]")))
        assertTrue(line, line.endsWith(DateGrouping.SEPARATOR + "★ 2"))
        val pendingWithMarks = meta(RecordingItem(null, server("pending", marks = "[6.0]")))
        assertTrue(pendingWithMarks, pendingWithMarks.endsWith("Transcribing…" + DateGrouping.SEPARATOR + "★ 1"))
        val phoneMarks = meta(RecordingItem(local("/tmp/1.opus", uploaded = false, marks = listOf(1.0, 2.0, 3.0)), null))
        assertTrue(phoneMarks, phoneMarks.endsWith("Uploading" + DateGrouping.SEPARATOR + "★ 3"))
    }
}
