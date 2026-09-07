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
 * FileDetailActivity: the Copy / Export pills appear only once a transcript is stored, Copy puts
 * the transcript on the clipboard with a confirmation toast, Export hands a markdown file
 * (server-compatible layout) to the share sheet, and the unified open (phone copy + server
 * copy) renders the cache first, refreshes from the server, and routes Delete / Remove from
 * phone through the shared action semantics.
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
        org.plaudbridge.app.ui.recordings.RecordingsRepository.reset()
    }

    private fun storeFile(
        transcriptJson: String?,
        serverId: String? = "srv-7",
        uploaded: Boolean = true,
        localPath: String? = null
    ): RecordingFile {
        val file = RecordingFile(
            sessionId = 7L, deviceSN = "SN-A", name = "Untitled Recording", duration = 61,
            createdAt = 1_788_758_851_000L, uploaded = uploaded, serverId = serverId, localPath = localPath,
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

    private fun serverRecording(status: String = "done", error: String? = null) = org.plaudbridge.app.models.ServerRecording.fromJson(
        org.json.JSONObject(
            """{"id":"srv-9","device_sn":"SN-A","session_id":9,"filename":"9.mp3","size_bytes":1,
            "duration_s":61.0,"started_at":"2026-09-07T05:27:31Z","uploaded_at":"2026-09-07T05:30:00Z",
            "source":"plaud-bridge-android","status":"$status","title":"Server side \"Q3\" call",
            "summary":"From the list.","marks":[6.0],"has_transcript":true,"text_preview":null,
            "error":${if (error == null) "null" else "\"$error\""}}"""
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
        // A finished recording wears no badge.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
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

    // MARK: - Unified open (phone copy + server copy)

    private fun configureServer(source: FileDetailActivity.ServerDetailSource) {
        FileDetailActivity.serverSource = source
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "tok"
    }

    private fun launchBoth(fileId: String, serverId: String = "srv-9"): FileDetailActivity {
        val intent = Intent(context, FileDetailActivity::class.java)
            .putExtra(FileDetailActivity.EXTRA_FILE_ID, fileId)
            .putExtra(FileDetailActivity.EXTRA_SERVER_RECORDING_ID, serverId)
        return Robolectric.buildActivity(FileDetailActivity::class.java, intent).setup().get()
    }

    /**
     * Tap the confirm button of the dialog on screen. AlertController delivers button clicks
     * through a Handler message, so the paused main looper must run before the action lands.
     */
    private fun confirmLatestDialog() {
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun intentForCarriesBothIdsWhenKnown() {
        val file = storeFile(null, serverId = "srv-9")
        val item = org.plaudbridge.app.ui.recordings.RecordingItem(file, serverRecording())
        val intent = FileDetailActivity.intentFor(context, item)
        assertEquals(file.id, intent.getStringExtra(FileDetailActivity.EXTRA_FILE_ID))
        assertEquals("srv-9", intent.getStringExtra(FileDetailActivity.EXTRA_SERVER_RECORDING_ID))
        val serverOnly = FileDetailActivity.intentFor(context, org.plaudbridge.app.ui.recordings.RecordingItem(null, serverRecording()))
        assertEquals(null, serverOnly.getStringExtra(FileDetailActivity.EXTRA_FILE_ID))
        assertEquals("srv-9", serverOnly.getStringExtra(FileDetailActivity.EXTRA_SERVER_RECORDING_ID))
    }

    @Test
    fun unifiedOpenRefreshesTitleAndTranscriptFromTheServerAndCachesThem() {
        val file = storeFile(transcriptJson, serverId = "srv-9")
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptWithHighlights))
        configureServer(fake)
        val activity = launchBoth(file.id)
        // Server title wins over the cached AI title; the fresh transcript (with highlights) replaces the cache.
        assertEquals("Server side \"Q3\" call", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
        assertEquals(2, activity.findViewById<android.widget.LinearLayout>(R.id.highlightsList).childCount)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyState).visibility)
        // The phone copy now caches the server transcript so the next open is instant.
        assertEquals(transcriptWithHighlights, RecordingStore.allFiles.single().transcriptJSON)
    }

    @Test
    fun unifiedOpenKeepsThePhoneContentWhenTheServerIsUnreachable() {
        val file = storeFile(transcriptJson, serverId = "srv-9")
        val fake = object : FileDetailActivity.ServerDetailSource by FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending) {
            override suspend fun recording(id: String) = org.plaudbridge.app.net.ApiClient.RecordingResult.Error("offline")
        }
        configureServer(fake)
        val activity = launchBoth(file.id)
        assertEquals("Budget \"Q3\" call", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
    }

    @Test
    fun failedTranscriptionShowsFailedBadgeAndTheSingleCheckButton() {
        val fake = FakeServerSource(serverRecording("failed", error = "GPU on fire"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val activity = launchServer(fake)
        val badge = activity.findViewById<android.widget.TextView>(R.id.statusBadge)
        assertEquals(View.VISIBLE, badge.visibility)
        assertEquals("Failed", badge.text.toString())
        assertEquals("GPU on fire", activity.findViewById<android.widget.TextView>(R.id.emptySubtitle).text.toString())
        val button = activity.findViewById<android.widget.Button>(R.id.generateButton)
        assertEquals(View.VISIBLE, button.visibility)
        assertEquals("Check for transcript", button.text.toString())
    }

    @Test
    fun phoneOnlyRecordingHasNoBadgeAndNoButton() {
        val file = storeFile(null, serverId = null, uploaded = false, localPath = null)
        val activity = launch(file.id)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.generateButton).visibility)
        assertEquals("This recording is still only on the recorder. Sync it first.",
            activity.findViewById<android.widget.TextView>(R.id.emptySubtitle).text.toString())
    }

    @Test
    fun menuOffersOnlyWhatAppliesToTheRecording() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        configureServer(fake)
        val file = storeFile(null, serverId = "srv-9")
        val both = launchBoth(file.id)
        val menuBoth = android.widget.PopupMenu(both, both.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            both.applyMenuVisibility(it.menu)
        }.menu
        assertTrue(menuBoth.findItem(R.id.action_retranscribe).isVisible)
        assertTrue(menuBoth.findItem(R.id.action_remove_from_phone).isVisible)
        assertTrue(menuBoth.findItem(R.id.action_delete).isVisible)
        assertTrue(menuBoth.findItem(R.id.action_export_markdown).isVisible)

        RecordingStore.clearAll()
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "tok"
        val serverOnly = launchServer(fake)
        val menuServer = android.widget.PopupMenu(serverOnly, serverOnly.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            serverOnly.applyMenuVisibility(it.menu)
        }.menu
        assertTrue(menuServer.findItem(R.id.action_retranscribe).isVisible)
        assertEquals(false, menuServer.findItem(R.id.action_remove_from_phone).isVisible)
        assertEquals(false, menuServer.findItem(R.id.action_export).isVisible)
        assertTrue(menuServer.findItem(R.id.action_delete).isVisible)
    }

    @Test
    fun deleteRemovesTheServerCopyAndThePhoneCopyThenCloses() {
        val file = storeFile(transcriptJson, serverId = "srv-9")
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        configureServer(fake)
        val activity = launchBoth(file.id)
        assertTrue(activity.onMenuAction(R.id.action_delete))
        confirmLatestDialog()
        assertEquals(listOf("srv-9"), fake.deleted)
        assertTrue(RecordingStore.allFiles.isEmpty())
        assertEquals("Recording deleted", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun removeFromPhoneKeepsTheServerCopyAndStaysOpen() {
        val file = storeFile(transcriptJson, serverId = "srv-9")
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        configureServer(fake)
        val activity = launchBoth(file.id)
        assertTrue(activity.onMenuAction(R.id.action_remove_from_phone))
        confirmLatestDialog()
        assertTrue(fake.deleted.isEmpty())
        assertTrue(RecordingStore.allFiles.isEmpty())
        assertEquals("Removed from this phone", ShadowToast.getTextOfLatestToast())
        assertEquals(false, activity.isFinishing)
        // Still showing the server copy.
        assertEquals("Server side \"Q3\" call", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
    }

    @Test
    fun deleteOfPhoneOnlyRecordingNeverAsksTheServer() {
        val file = storeFile(null, serverId = null, uploaded = false, localPath = null)
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        configureServer(fake)
        val activity = launch(file.id)
        assertTrue(activity.onMenuAction(R.id.action_delete))
        confirmLatestDialog()
        assertTrue(fake.deleted.isEmpty())
        assertTrue(RecordingStore.allFiles.isEmpty())
        assertTrue(activity.isFinishing)
    }
}
