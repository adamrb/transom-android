package org.plaudbridge.app.ui.filedetail

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.R
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * FileDetailActivity transcript actions: the Copy / Export pills appear only once a transcript
 * is stored, Copy puts the transcript on the clipboard with a confirmation toast, and Export
 * hands a markdown file (server-compatible layout) to the share sheet.
 */
@RunWith(RobolectricTestRunner::class)
class FileDetailActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.plaudbridge.app.export.FileProviderTestSupport.resetCache()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        File(context.filesDir, "recordings.json").delete()
    }

    private fun storeFile(transcriptJson: String?): RecordingFile {
        val file = RecordingFile(
            sessionId = 7L, deviceSN = "SN-A", name = "Untitled Recording", duration = 61,
            createdAt = 1_788_758_851_000L, uploaded = true, serverId = "srv-7",
            serverTitle = "Budget \"Q3\" call"
        )
        RecordingStore.addFiles(listOf(file))
        if (transcriptJson != null) RecordingStore.updateTranscript(file.id, transcriptJson)
        return file
    }

    private fun launch(fileId: String): FileDetailActivity = controller(fileId).get()

    private fun controller(fileId: String): org.robolectric.android.controller.ActivityController<FileDetailActivity> {
        val intent = Intent(context, FileDetailActivity::class.java).putExtra("file_id", fileId)
        return Robolectric.buildActivity(FileDetailActivity::class.java, intent).setup()
    }

    private val transcriptJson = """{"text":"Speaker 1: Hello there.\nSpeaker 2: Hi.",
        "summary":"A greeting.",
        "segments":[{"speaker_id":"SPEAKER_00","start":0.0,"text":"Hello there."},
                    {"speaker_id":"SPEAKER_01","start":2.5,"text":"Hi."}]}"""

    private val transcriptWithHighlights = """{"text":"Speaker 1: Hello there.\nSpeaker 2: Hi.",
        "summary":"A greeting.",
        "segments":[{"speaker_id":"SPEAKER_00","start":0.0,"text":"Hello there."}],
        "marks":[6.0, 125.5],
        "highlights":[{"at":6.0,"start":4.2,"end":12.9,"speakers":["Speaker 1"],"text":"Hello there."},
                      {"at":125.5,"start":125.5,"end":125.5,"speakers":[],"text":""}]}"""

    @Test
    fun highlightsHiddenWhenTranscriptHasNone() {
        val file = storeFile(transcriptJson)
        val activity = launch(file.id)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.highlightsHeader).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.highlightsList).visibility)
    }

    @Test
    fun highlightsRenderOneRowPerEntryAboveTheTranscript() {
        val file = storeFile(transcriptWithHighlights)
        val controller = controller(file.id)
        val activity = controller.get()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.highlightsHeader).visibility)
        val list = activity.findViewById<android.widget.LinearLayout>(R.id.highlightsList)
        assertEquals(View.VISIBLE, list.visibility)
        assertEquals(2, list.childCount)
        val first = (list.getChildAt(0) as android.widget.TextView).text.toString()
        val second = (list.getChildAt(1) as android.widget.TextView).text.toString()
        assertEquals("\u2605 0:06  Hello there.", first)
        assertEquals("\u2605 2:06  (no speech near this mark)", second)
        // Without a local audio file there is no player; tapping must be a harmless no-op.
        list.getChildAt(0).performClick()
        // Re-binding (the onResume reload after a rename) must not duplicate rows.
        controller.pause().resume()
        assertEquals(2, list.childCount)
    }

    @Test
    fun exportIncludesHighlightsSection() {
        val file = storeFile(transcriptWithHighlights)
        val activity = launch(file.id)
        activity.findViewById<View>(R.id.exportMarkdownButton).performClick()
        val exported = File(context.cacheDir, "exports/Budget -Q3- call.md").readText()
        val expectedTail = """
            |## Summary
            |
            |A greeting.
            |
            |## Highlights
            |
            |- **0:06** Hello there.
            |- **2:06** (no speech near this mark)
            |
            |## Transcript
            |
            |**Speaker 1:** Hello there.
            |
            |**Speaker 2:** Hi.
            |""".trimMargin()
        assertTrue(exported, exported.endsWith(expectedTail))
    }

    @Test
    fun actionsHiddenWithoutTranscript() {
        val file = storeFile(null)
        val activity = launch(file.id)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptActions).visibility)
    }

    @Test
    fun actionsVisibleWithTranscript() {
        val file = storeFile(transcriptJson)
        val activity = launch(file.id)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptActions).visibility)
    }

    @Test
    fun copyPutsTranscriptOnClipboardAndToasts() {
        val file = storeFile(transcriptJson)
        val activity = launch(file.id)
        activity.findViewById<View>(R.id.copyTranscriptButton).performClick()

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals("Transcript", clip!!.description.label)
        // The server's speaker turns as paragraphs: no timestamps, no per-segment blocks.
        assertEquals("Speaker 1: Hello there.\n\nSpeaker 2: Hi.", clip.getItemAt(0).text.toString())
        assertEquals("Transcript copied", ShadowToast.getTextOfLatestToast())
        // The on-screen rendering keeps its segment blocks with timestamps.
        val shown = activity.findViewById<android.widget.TextView>(R.id.transcriptText).text.toString()
        assertEquals("Speaker 00 \u00b7 00:00:00\nHello there.\n\nSpeaker 01 \u00b7 00:00:02\nHi.", shown)
    }

    @Test
    fun copyFallsBackToSegmentsMergedPerSpeakerWhenTextIsMissing() {
        val legacy = """{"segments":[{"speaker_id":"SPEAKER_00","start":0.0,"text":"Hello"},
            {"speaker_id":"SPEAKER_00","start":1.0,"text":"there."},
            {"speaker_id":"SPEAKER_01","start":2.5,"text":"Hi."}]}"""
        val file = storeFile(legacy)
        val activity = launch(file.id)
        activity.findViewById<View>(R.id.copyTranscriptButton).performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("Speaker 00: Hello there.\n\nSpeaker 01: Hi.", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun exportWritesMarkdownAndOpensChooser() {
        val file = storeFile(transcriptJson)
        val activity = launch(file.id)
        activity.findViewById<View>(R.id.exportMarkdownButton).performClick()

        val exported = File(context.cacheDir, "exports/Budget -Q3- call.md")
        assertTrue("export file missing: ${exported.path}", exported.exists())
        val expected = """
            |---
            |title: "Budget \"Q3\" call"
            |recorded: "2026-09-07T05:27:31Z"
            |duration_s: "61"
            |source: plaud-bridge
            |---
            |# Budget "Q3" call
            |
            |## Summary
            |
            |A greeting.
            |
            |## Transcript
            |
            |**Speaker 1:** Hello there.
            |
            |**Speaker 2:** Hi.
            |""".trimMargin()
        assertEquals(expected, exported.readText())

        val chooser = shadowOf(activity).nextStartedActivity
        assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/markdown", send.type)
        assertEquals("Budget \"Q3\" call", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(expected, send.getStringExtra(Intent.EXTRA_TEXT))
        assertNotNull(send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    // MARK: - Server mode (Library)

    /** Fake seam: answers the screen's server calls synchronously, no network. */
    private class FakeServerSource(
        val rec: org.plaudbridge.app.models.ServerRecording,
        val transcript: org.plaudbridge.app.net.ApiClient.TranscriptResult
    ) : FileDetailActivity.ServerDetailSource {
        val deleted = mutableListOf<String>()
        override suspend fun recording(id: String) =
            if (id == rec.id) org.plaudbridge.app.net.ApiClient.RecordingResult.Ok(rec)
            else org.plaudbridge.app.net.ApiClient.RecordingResult.NotFound
        override suspend fun transcript(id: String) = transcript
        override suspend fun rename(id: String, title: String) =
            org.plaudbridge.app.net.ApiClient.RecordingResult.Ok(rec.copy(title = title))
        override suspend fun retranscribe(id: String) = org.plaudbridge.app.net.ApiClient.ActionResult.Ok
        override suspend fun delete(id: String): org.plaudbridge.app.net.ApiClient.ActionResult {
            deleted += id
            return org.plaudbridge.app.net.ApiClient.ActionResult.Ok
        }
    }

    private fun serverRecording(status: String = "done") = org.plaudbridge.app.models.ServerRecording.fromJson(
        org.json.JSONObject(
            """{"id":"srv-9","device_sn":"SN-A","session_id":9,"filename":"9.mp3","size_bytes":1,
            "duration_s":61.0,"started_at":"2026-09-07T05:27:31Z","uploaded_at":"2026-09-07T05:30:00Z",
            "source":"plaud-bridge-android","status":"$status","title":"Server side \"Q3\" call",
            "summary":"From the list.","marks":[6.0],"has_transcript":true,"text_preview":null,"error":null}"""
        )
    )

    private fun launchServer(source: FileDetailActivity.ServerDetailSource, id: String = "srv-9"): FileDetailActivity {
        FileDetailActivity.serverSource = source
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "tok"
        val intent = Intent(context, FileDetailActivity::class.java)
            .putExtra(FileDetailActivity.EXTRA_SERVER_RECORDING_ID, id)
        return Robolectric.buildActivity(FileDetailActivity::class.java, intent).setup().get()
    }

    @Test
    fun serverModeShowsFetchedTitleAndTranscript() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptWithHighlights))
        val activity = launchServer(fake)
        assertEquals("Server side \"Q3\" call", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
        assertEquals("Transcribed", activity.findViewById<android.widget.TextView>(R.id.statusBadge).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptActions).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.highlightsHeader).visibility)
        assertEquals(2, activity.findViewById<android.widget.LinearLayout>(R.id.highlightsList).childCount)
        // The transcript's own summary wins over the list object's.
        assertEquals("A greeting.", activity.findViewById<android.widget.TextView>(R.id.summaryText).text.toString())
        // Nothing from the server is written into the phone's index.
        assertTrue(RecordingStore.allFiles.isEmpty())
    }

    @Test
    fun serverModePendingTranscriptShowsEmptyStateWithCheckButton() {
        val fake = FakeServerSource(serverRecording("transcribing"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val activity = launchServer(fake)
        assertEquals("Server side \"Q3\" call", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
        assertEquals("Transcribing…", activity.findViewById<android.widget.TextView>(R.id.statusBadge).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptActions).visibility)
        assertEquals("Check for transcript", activity.findViewById<android.widget.Button>(R.id.generateButton).text.toString())
    }

    @Test
    fun serverModeExportUsesFetchedFields() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        activity.findViewById<View>(R.id.exportMarkdownButton).performClick()
        val exported = File(context.cacheDir, "exports/Server side -Q3- call.md")
        assertTrue("export file missing: ${exported.path}", exported.exists())
        val text = exported.readText()
        assertTrue(text, text.startsWith("---\ntitle: \"Server side \\\"Q3\\\" call\"\nrecorded: \"2026-09-07T05:27:31Z\"\nduration_s: \"61\"\n"))
    }

    @Test
    fun serverModeCopyUsesSpeakerParagraphs() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        activity.findViewById<View>(R.id.copyTranscriptButton).performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("Speaker 1: Hello there.\n\nSpeaker 2: Hi.", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun serverModeNotFoundShowsMessageWithoutCrashing() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val activity = launchServer(fake, id = "missing")
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertEquals("This recording is no longer on the server.",
            activity.findViewById<android.widget.TextView>(R.id.emptySubtitle).text.toString())
    }
}
