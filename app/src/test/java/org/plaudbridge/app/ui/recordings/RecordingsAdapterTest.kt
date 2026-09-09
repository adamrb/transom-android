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

    private fun serverWithAutomations(json: String?) = ServerRecording.fromJson(
        JSONObject("""{"id":"s1","device_sn":"SN","session_id":1,"filename":"a.mp3","status":"done","duration_s":61.0,"automations":${json ?: "null"}}""")
    )

    @Test
    fun automationsLineIsAbsentWhenTheyNeverRan() {
        assertEquals(null, RecordingsAdapter.automationsText(context, RecordingItem(null, serverWithAutomations(null))))
        assertEquals(null, RecordingsAdapter.automationsText(context, RecordingItem(local("/a", true), null)))
    }

    @Test
    fun automationsLineShowsTheServersOneLinerWithRouteLabelsBold() {
        val item = RecordingItem(null, serverWithAutomations(
            """{"state":"done","line":"Vault notes: Filed: Life/Topics/Dogs.md","items":[{"route_name":"Vault notes","state":"done","summary":"Filed: Life/Topics/Dogs.md"}]}"""
        ))
        val text = RecordingsAdapter.automationsText(context, item) as android.text.Spanned
        assertEquals("Vault notes: Filed: Life/Topics/Dogs.md", text.toString())
        val bold = text.getSpans(0, text.length, android.text.style.StyleSpan::class.java)
        assertEquals(1, bold.size)
        assertEquals("Vault notes:", text.subSequence(text.getSpanStart(bold[0]), text.getSpanEnd(bold[0])).toString())
        assertEquals(0, text.getSpans(0, text.length, android.text.style.ForegroundColorSpan::class.java).size)
    }

    @Test
    fun failedAutomationsLineIsColouredAsAnError() {
        val item = RecordingItem(null, serverWithAutomations(
            """{"state":"failed","line":"Ask Claude: HTTP 500","items":[{"route_name":"Ask Claude","state":"failed","summary":"HTTP 500"}]}"""
        ))
        val text = RecordingsAdapter.automationsText(context, item) as android.text.Spanned
        assertEquals(1, text.getSpans(0, text.length, android.text.style.ForegroundColorSpan::class.java).size)
        val unknown = RecordingItem(null, serverWithAutomations("""{"state":"unknown","line":"Ask Claude: No result was reported","items":[]}"""))
        assertEquals(1, (RecordingsAdapter.automationsText(context, unknown) as android.text.Spanned)
            .getSpans(0, 5, android.text.style.ForegroundColorSpan::class.java).size)
    }

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
        assertTrue(meta(RecordingItem(null, server("transcribing"))).endsWith("Transcribing"))
        assertTrue(meta(RecordingItem(null, server("pending"))).endsWith("Waiting to transcribe"))
        assertTrue(meta(RecordingItem(null, server("failed"))).endsWith("Failed"))
        assertTrue(meta(RecordingItem(null, server("stored"))).endsWith("1m 1s"))
    }

    private fun transcribing(extra: String) = ServerRecording.fromJson(
        JSONObject("""{"id":"r","device_sn":"SN-A","session_id":1,"filename":"r.mp3","status":"transcribing",
            "duration_s":61.0,"started_at":"2026-09-07T05:27:31Z",$extra}""")
    )

    private fun status(rec: ServerRecording) = RecordingsAdapter.statusText(context, RecordingItem(null, rec))

    @Test
    fun transcribingStagesReadInUserWords() {
        assertEquals("Waiting to transcribe", status(transcribing(""""stage":"queued"""")))
        assertEquals("Transcribing", status(transcribing(""""stage":"transcribing"""")))
        assertEquals("Transcribing · 42%", status(transcribing(""""stage":"transcribing","progress":0.428""")))
        assertEquals("Identifying speakers", status(transcribing(""""stage":"diarizing"""")))
        assertEquals("Summarizing", status(transcribing(""""stage":"summarizing"""")))
        // An older server sends no stage: the percentage still shows when it is there.
        assertEquals("Transcribing · 7%", status(transcribing(""""progress":0.0799""")))
        assertEquals("Transcribing", status(transcribing(""""stage":null,"progress":null""")))
        // A stage this app does not know reads as plain transcribing rather than leaking the word.
        assertEquals("Transcribing", status(transcribing(""""stage":"aligning"""")))
    }

    @Test
    fun percentRoundsDownAndNeverReachesOneHundredWhileWorking() {
        assertEquals("Transcribing · 0%", status(transcribing(""""stage":"transcribing","progress":0.0""")))
        assertEquals("Transcribing · 0%", status(transcribing(""""stage":"transcribing","progress":0.009""")))
        assertEquals("Transcribing · 99%", status(transcribing(""""stage":"transcribing","progress":0.999""")))
        assertEquals("Transcribing · 99%", status(transcribing(""""stage":"transcribing","progress":1.0""")))
        assertEquals("Transcribing · 0%", status(transcribing(""""stage":"transcribing","progress":-0.5""")))
        // Stages that carry no percentage ignore a stale one.
        assertEquals("Identifying speakers", status(transcribing(""""stage":"diarizing","progress":1.0""")))
        // Pending is waiting, whatever else the object says.
        assertEquals("Waiting to transcribe", status(server("pending")))
        val line = meta(RecordingItem(null, transcribing(""""stage":"transcribing","progress":0.5""")))
        assertTrue(line, line.endsWith(DateGrouping.SEPARATOR + "Transcribing · 50%"))
    }

    // MARK: - Recordings with no speech

    private fun noSpeech(title: String? = null) = ServerRecording.fromJson(
        JSONObject("""{"id":"r","device_sn":"SN-A","session_id":1,"filename":"r.mp3","status":"done",
            "duration_s":61.0,"started_at":"2026-09-07T05:27:31Z","title":${title?.let { "\"$it\"" } ?: "null"},
            "summary":null,"no_speech":true}""")
    )

    private fun title(item: RecordingItem) = RecordingsAdapter.rowTitle(context, item)

    @Test
    fun noSpeechRowSaysSoInsteadOfAPlaceholderName() {
        assertEquals("No speech detected", title(RecordingItem(null, noSpeech())))
        // Phone copy paired with it: the phone's "Untitled Recording" is no better a name.
        assertEquals("No speech detected", title(RecordingItem(local("/tmp/1.opus", uploaded = true), noSpeech())))
        // A real title, from wherever, wins.
        assertEquals("Silent walk", title(RecordingItem(null, noSpeech(title = "Silent walk"))))
        val renamed = local("/tmp/1.opus", uploaded = true).apply { name = "My quiet room"; nameEditedByUser = true }
        assertEquals("My quiet room", title(RecordingItem(renamed, noSpeech())))
        val cached = local("/tmp/1.opus", uploaded = true).apply { serverTitle = "Cached title" }
        assertEquals("Cached title", title(RecordingItem(cached, noSpeech())))
        // A finished recording with speech keeps its normal fallback.
        assertEquals("r.mp3", title(RecordingItem(null, server("done"))))
        // No status word: it is done.
        val line = meta(RecordingItem(null, noSpeech()))
        assertEquals(DateGrouping.formatDateTime(1_788_758_851_000L) + DateGrouping.SEPARATOR + "1m 1s", line)
    }

    @Test
    fun cachedNoSpeechDocumentNamesTheRowWhenThereIsNoServerObject() {
        // Offline, or before the first list refresh after a restart: the row is the phone copy
        // alone, and the transcript document it cached is what says the recording was silent.
        val noSpeechDoc = """{"text":"","segments":[],"no_speech":true}"""
        val cachedSilent = local("/tmp/1.opus", uploaded = true).apply { transcriptJSON = noSpeechDoc }
        assertTrue(RecordingItem(cachedSilent, null).isNoSpeech)
        assertEquals("No speech detected", title(RecordingItem(cachedSilent, null)))
        val cachedSpoken = local("/tmp/1.opus", uploaded = true).apply { transcriptJSON = """{"text":"Hello","segments":[]}""" }
        assertFalse(RecordingItem(cachedSpoken, null).isNoSpeech)
        assertEquals("Untitled Recording", title(RecordingItem(cachedSpoken, null)))
        assertFalse(RecordingItem(local("/tmp/1.opus", uploaded = true), null).isNoSpeech)
        // Once the server object is there it is the word on it, whatever the cache says.
        assertFalse(RecordingItem(cachedSilent, server("done")).isNoSpeech)
        // ... and the row falls back to its ordinary stand-in name (the phone's, when paired).
        assertEquals("Untitled Recording", title(RecordingItem(cachedSilent, server("done"))))
        assertEquals("r.mp3", title(RecordingItem(null, server("done"))))
        assertTrue(RecordingItem(cachedSpoken, noSpeech()).isNoSpeech)
        assertEquals("No speech detected", title(RecordingItem(cachedSpoken, noSpeech())))
        // Garbage in the cache is not a verdict.
        val garbage = local("/tmp/1.opus", uploaded = true).apply { transcriptJSON = "<html>" }
        assertFalse(RecordingItem(garbage, null).isNoSpeech)
    }

    @Test
    fun searchDoesNotMatchTheNoSpeechWording() {
        val quiet = RecordingItem(null, noSpeech())
        val spoken = RecordingItem(null, ServerRecording.fromJson(JSONObject(
            """{"id":"s","filename":"s.mp3","status":"done","title":"Speech day","text_preview":"we talked"}"""
        )))
        assertEquals(listOf(spoken), RecordingsMerger.filter(listOf(quiet, spoken), "speech"))
        assertEquals(emptyList<RecordingItem>(), RecordingsMerger.filter(listOf(quiet), "detected"))
        // The plain name is still searchable, as for any untitled row.
        assertEquals(listOf(quiet), RecordingsMerger.filter(listOf(quiet, spoken), "r.mp3"))
    }

    @Test
    fun listPollsOnlyWhileAShownRowIsStillBeingTranscribed() {
        assertTrue(RecordingsFragment.shouldPoll(listOf(RecordingItem(null, server("done")), RecordingItem(null, server("pending")))))
        assertTrue(RecordingsFragment.shouldPoll(listOf(RecordingItem(null, server("transcribing")))))
        assertFalse(RecordingsFragment.shouldPoll(listOf(RecordingItem(null, server("done")), RecordingItem(null, server("failed")))))
        assertFalse(RecordingsFragment.shouldPoll(listOf(RecordingItem(local("/tmp/1.opus", uploaded = false), null))))
        assertFalse(RecordingsFragment.shouldPoll(emptyList()))
    }

    @Test
    fun phoneOnlyStatusWords() {
        assertTrue(meta(RecordingItem(local(null, uploaded = false), null)).endsWith("Downloading"))
        assertTrue(meta(RecordingItem(local("/tmp/1.opus", uploaded = false), null)).endsWith("Uploading"))
        assertTrue(meta(RecordingItem(local("/tmp/1.opus", uploaded = true), null)).endsWith("1m 1s"))
    }

    // MARK: - Upload failed

    @Test
    fun aFailedUploadSaysSoInsteadOfUploadingForever() {
        val failed = RecordingItem(local("/tmp/1.opus", uploaded = false), null, uploadFailed = true)
        assertEquals(RecordingItem.Status.UPLOAD_FAILED, failed.status)
        assertTrue(failed.canRetryUpload)
        assertTrue(meta(failed).endsWith("Upload failed"))
        // Nothing to retry once it is uploading again, downloaded-not-yet, or on the server.
        assertEquals(RecordingItem.Status.UPLOADING, RecordingItem(local("/tmp/1.opus", uploaded = false), null).status)
        assertEquals(RecordingItem.Status.DOWNLOADING, RecordingItem(local(null, uploaded = false), null, uploadFailed = true).status)
        assertEquals(RecordingItem.Status.NONE, RecordingItem(local("/tmp/1.opus", uploaded = false), server("done"), uploadFailed = true).status)
        assertFalse(RecordingItem(local("/tmp/1.opus", uploaded = false), server("done"), uploadFailed = true).canRetryUpload)
    }

    @Test
    fun failureWordsAreDrawnInTheFailureColourOnlyWhileInFlightWordsAreNot() {
        val failedUpload = RecordingsAdapter.metaText(context, RecordingItem(local("/tmp/1.opus", uploaded = false), null, uploadFailed = true))
        val spans = (failedUpload as android.text.Spanned).getSpans(0, failedUpload.length, android.text.style.ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        val start = failedUpload.getSpanStart(spans[0])
        assertEquals("Upload failed", failedUpload.subSequence(start, failedUpload.getSpanEnd(spans[0])).toString())

        val failed = RecordingsAdapter.metaText(context, RecordingItem(null, server("failed")))
        assertEquals(1, (failed as android.text.Spanned).getSpans(0, failed.length, android.text.style.ForegroundColorSpan::class.java).size)

        val uploading = RecordingsAdapter.metaText(context, RecordingItem(local("/tmp/1.opus", uploaded = false), null))
        assertFalse(uploading is android.text.Spanned && uploading.getSpans(0, uploading.length, Any::class.java).isNotEmpty())
        assertEquals(meta(RecordingItem(local("/tmp/1.opus", uploaded = false), null)), uploading.toString())
    }

    // MARK: - Search snippets

    @Test
    fun snippetOnlyWhenTheMatchIsNotInTheTitle() {
        val rec = ServerRecording.fromJson(JSONObject(
            """{"id":"s","filename":"s.mp3","status":"done","title":"Standup","text_preview":"we discussed the BUDGET for Q3"}"""
        ))
        val item = RecordingItem(null, rec)
        val snippet = RecordingsAdapter.snippetFor(item, "budget")!!
        assertTrue(snippet.text, snippet.text.contains("BUDGET"))
        assertEquals("BUDGET", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
        assertEquals(null, RecordingsAdapter.snippetFor(item, "standup"))
        assertEquals(null, RecordingsAdapter.snippetFor(item, null))
        // The phone's cached summary counts too.
        val cached = local("/tmp/1.opus", uploaded = true).apply { summaryText = "Notes about the garden fence" }
        assertTrue(RecordingsAdapter.snippetFor(RecordingItem(cached, null), "fence")!!.text.contains("fence"))
    }

    @Test
    fun noSpeechRowMatchedByItsHiddenNameShowsThatNameAsTheSnippet() {
        // The row reads "No speech detected"; search matched the file name it does not show.
        val quiet = RecordingItem(null, noSpeech())
        val shown = title(quiet)
        assertEquals("No speech detected", shown)
        val snippet = RecordingsAdapter.snippetFor(quiet, "r.mp3", shownTitle = shown)!!
        assertEquals("r.mp3", snippet.text)
        assertEquals("r.mp3", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
        // Searching for the wording of the stand-in itself matches nothing (see searchDoesNotMatchTheNoSpeechWording).
        assertEquals(null, RecordingsAdapter.snippetFor(quiet, "detected", shownTitle = shown))
        // An ordinary row whose shown title is its title behaves as before.
        val plain = RecordingItem(null, server("done"))
        assertEquals(null, RecordingsAdapter.snippetFor(plain, "r.mp3", shownTitle = title(plain)))
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
        assertTrue(pendingWithMarks, pendingWithMarks.endsWith("Waiting to transcribe" + DateGrouping.SEPARATOR + "★ 1"))
        val phoneMarks = meta(RecordingItem(local("/tmp/1.opus", uploaded = false, marks = listOf(1.0, 2.0, 3.0)), null))
        assertTrue(phoneMarks, phoneMarks.endsWith("Uploading" + DateGrouping.SEPARATOR + "★ 3"))
    }
}
