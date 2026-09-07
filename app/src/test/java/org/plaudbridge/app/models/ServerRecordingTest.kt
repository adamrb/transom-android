package org.plaudbridge.app.models

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** JSON mapping of the server's recording object: nulls, marks, ISO timestamps, fallbacks. */
@RunWith(RobolectricTestRunner::class)
class ServerRecordingTest {

    private val full = """{
        "id":"rec-1","device_sn":"SN1","session_id":42,"filename":"42.mp3","size_bytes":1234,
        "duration_s":61.4,"started_at":"2026-09-07T05:27:31Z","uploaded_at":"2026-09-07T05:30:00.250+00:00",
        "source":"plaud-bridge-android","status":"done","title":"Budget call","summary":"A greeting.",
        "marks":[6.0,125.5],"has_transcript":true,"text_preview":"Hello","error":null}"""

    @Test
    fun mapsEveryField() {
        val r = ServerRecording.fromJson(JSONObject(full))
        assertEquals("rec-1", r.id)
        assertEquals("SN1", r.deviceSn)
        assertEquals(42L, r.sessionId)
        assertEquals("42.mp3", r.filename)
        assertEquals(1234L, r.sizeBytes)
        assertEquals(61.4, r.durationS, 1e-9)
        assertEquals(61L, r.durationSeconds)
        assertEquals(1_788_758_851_000L, r.startedAt)
        assertEquals(1_788_759_000_250L, r.uploadedAt)
        assertEquals("done", r.status)
        assertEquals("Budget call", r.title)
        assertEquals("Budget call", r.displayTitle)
        assertEquals("A greeting.", r.summary)
        assertEquals(listOf(6.0, 125.5), r.marks)
        assertEquals(2, r.marksCount)
        assertTrue(r.hasTranscript)
        assertEquals("Hello", r.textPreview)
        assertNull(r.error)
        assertTrue(r.isDone)
        assertEquals(1_788_758_851_000L, r.recordedAt)
    }

    @Test
    fun nullsBecomeKotlinNullsAndDefaults() {
        val r = ServerRecording.fromJson(JSONObject("""{"id":"x","filename":"f.mp3","status":"stored",
            "title":null,"summary":null,"started_at":null,"uploaded_at":"2026-01-02T03:04:05",
            "marks":null,"text_preview":null,"error":"boom","session_id":null}"""))
        assertNull(r.title)
        assertNull(r.summary)
        assertNull(r.startedAt)
        assertNull(r.sessionId)
        assertEquals(emptyList<Double>(), r.marks)
        assertEquals(0, r.marksCount)
        assertFalse(r.hasTranscript)
        assertEquals("boom", r.error)
        assertEquals(0.0, r.durationS, 0.0)
        // No start time: the upload time anchors sorting and grouping.
        assertEquals(r.uploadedAt, r.recordedAt)
        assertEquals(1_767_323_045_000L, r.uploadedAt)
    }

    @Test
    fun displayTitleFallsBackToFilenameForNullOrBlankTitle() {
        val noTitle = ServerRecording.fromJson(JSONObject("""{"id":"a","filename":"a.mp3","title":null}"""))
        assertEquals("a.mp3", noTitle.displayTitle)
        val blank = ServerRecording.fromJson(JSONObject("""{"id":"a","filename":"a.mp3","title":"   "}"""))
        assertEquals("a.mp3", blank.displayTitle)
    }

    @Test
    fun missingIdIsRejected() {
        try {
            ServerRecording.fromJson(JSONObject("""{"filename":"a.mp3"}"""))
            throw AssertionError("expected failure")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            ServerRecording.fromJson(JSONObject("""{"id":123,"filename":"a.mp3"}"""))
            throw AssertionError("expected failure for numeric id")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun listParsesAndDropsMalformedRows() {
        val list = ServerRecording.listFromJson(
            """{"recordings":[{"id":"a","filename":"a.mp3"},{"filename":"no-id.mp3"},{"id":"b","filename":"b.mp3"}]}"""
        )
        assertEquals(listOf("a", "b"), list.map { it.id })
        assertEquals(emptyList<ServerRecording>(), ServerRecording.listFromJson("""{}"""))
    }

    @Test
    fun parsesIsoVariants() {
        val base = 1_788_758_851_000L // 2026-09-07T05:27:31Z
        assertEquals(base, ServerRecording.parseIso("2026-09-07T05:27:31Z"))
        assertEquals(base, ServerRecording.parseIso("2026-09-07T05:27:31"))          // naive = UTC
        assertEquals(base, ServerRecording.parseIso("2026-09-07T05:27:31+00:00"))
        assertEquals(base, ServerRecording.parseIso("2026-09-07T07:27:31+02:00"))    // ahead of UTC
        assertEquals(base, ServerRecording.parseIso("2026-09-07T01:27:31-04:00"))    // behind UTC
        assertEquals(base, ServerRecording.parseIso("2026-09-07T05:27:31-0000"))
        assertEquals(base + 123, ServerRecording.parseIso("2026-09-07T05:27:31.123456Z")) // micros truncated
        assertEquals(base + 500, ServerRecording.parseIso("2026-09-07T05:27:31.5Z"))     // short fraction
        assertEquals(base, ServerRecording.parseIso("2026-09-07 05:27:31Z"))         // space separator
    }

    @Test
    fun malformedIsoIsNull() {
        assertNull(ServerRecording.parseIso(null))
        assertNull(ServerRecording.parseIso(""))
        assertNull(ServerRecording.parseIso("yesterday"))
        assertNull(ServerRecording.parseIso("2026-09-07"))
    }
}
