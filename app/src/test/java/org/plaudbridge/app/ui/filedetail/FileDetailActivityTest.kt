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
        // Animations off, as for any UI test. The transcription progress bar is a Material
        // indicator whose animators (the indeterminate sweep, the determinate spring) request
        // Choreographer frames; Robolectric advances the paused clock per frame, which would
        // run every delayed poll early and make the timing assertions here meaningless.
        android.provider.Settings.Global.putFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f
        )
        org.plaudbridge.app.export.FileProviderTestSupport.resetCache()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        File(context.filesDir, "recordings.json").delete()
        org.plaudbridge.app.ui.recordings.RecordingsRepository.reset()
        FileDetailActivity.resetProcessStateForTests()
    }

    private fun storeFile(
        transcriptJson: String?,
        serverId: String? = "srv-7",
        uploaded: Boolean = true,
        localPath: String? = null,
        serverTitle: String? = "Budget \"Q3\" call"
    ): RecordingFile {
        val file = RecordingFile(
            sessionId = 7L, deviceSN = "SN-A", name = "Untitled Recording", duration = 61,
            createdAt = 1_788_758_851_000L, uploaded = uploaded, serverId = serverId, localPath = localPath,
            serverTitle = serverTitle
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
        var rec: org.plaudbridge.app.models.ServerRecording,
        var transcript: org.plaudbridge.app.net.ApiClient.TranscriptResult
    ) : FileDetailActivity.ServerDetailSource {
        val deleted = mutableListOf<String>()
        /** What GET routing answers; Ok(empty) by default so the section stays quiet in older tests. */
        var routing: org.plaudbridge.app.net.ApiClient.RoutingResult =
            org.plaudbridge.app.net.ApiClient.RoutingResult.Ok(emptyList())
        val routingCalls = mutableListOf<String>()
        val reruns = mutableListOf<String>()
        val retries = mutableListOf<String>()
        /** How many of the next recording reads answer with a transient error. */
        var recordingFailures = 0
        /** When set, recording reads park on it so a test can hold one "on the wire". */
        var recordingGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun recording(id: String): org.plaudbridge.app.net.ApiClient.RecordingResult {
            recordingCalls += id
            recordingGate?.await()
            if (recordingFailures > 0) {
                recordingFailures--
                return org.plaudbridge.app.net.ApiClient.RecordingResult.Error("timeout")
            }
            return if (id == rec.id) org.plaudbridge.app.net.ApiClient.RecordingResult.Ok(rec)
            else org.plaudbridge.app.net.ApiClient.RecordingResult.NotFound
        }
        /** When set, the transcript read parks on it so a test can look at the screen in between. */
        var transcriptGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val transcriptCalls = mutableListOf<String>()
        override suspend fun transcript(id: String): org.plaudbridge.app.net.ApiClient.TranscriptResult {
            transcriptCalls += id
            transcriptGate?.await()
            return transcript
        }
        /** When set, routing parks on it so a test can hold a read "on the wire". */
        var routingGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun routing(id: String): org.plaudbridge.app.net.ApiClient.RoutingResult {
            routingCalls += id
            routingGate?.await()
            return routing
        }
        /** When set, rerunRouting parks on it so a test can hold the call "on the wire". */
        var rerunGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var rerunResult: org.plaudbridge.app.net.ApiClient.ActionResult = org.plaudbridge.app.net.ApiClient.ActionResult.Ok
        val rerunKeys = mutableListOf<String>()
        val rerunInstructions = mutableListOf<String?>()
        override suspend fun rerunRouting(id: String, idempotencyKey: String, instructions: String?): org.plaudbridge.app.net.ApiClient.ActionResult {
            reruns += id
            rerunKeys += idempotencyKey
            rerunInstructions += instructions
            rerunGate?.await()
            return rerunResult
        }
        /** Recording reads, so a test can count the transcription polls. */
        val recordingCalls = mutableListOf<String>()
        var retryResult: org.plaudbridge.app.net.ApiClient.RetryResult = org.plaudbridge.app.net.ApiClient.RetryResult.Ok
        override suspend fun retryDelivery(deliveryId: String): org.plaudbridge.app.net.ApiClient.RetryResult {
            retries += deliveryId
            return retryResult
        }
        override suspend fun rename(id: String, title: String) =
            org.plaudbridge.app.net.ApiClient.RecordingResult.Ok(rec.copy(title = title))
        override suspend fun retranscribe(id: String) = org.plaudbridge.app.net.ApiClient.ActionResult.Ok
        override suspend fun delete(id: String): org.plaudbridge.app.net.ApiClient.ActionResult {
            deleted += id
            return org.plaudbridge.app.net.ApiClient.ActionResult.Ok
        }
    }

    /** Uploaded a week ago by default: an old recording, routed long ago, nothing to wait for. */
    private fun serverRecording(
        status: String = "done", error: String? = null, uploadedAt: String = "2026-09-01T05:30:00Z",
        /** Extra JSON members (e.g. `"stage":"diarizing","progress":0.5`), appended verbatim. */
        extra: String = ""
    ) = org.plaudbridge.app.models.ServerRecording.fromJson(
        org.json.JSONObject(
            """{"id":"srv-9","device_sn":"SN-A","session_id":9,"filename":"9.mp3","size_bytes":1,
            "duration_s":61.0,"started_at":"2026-09-07T05:27:31Z","uploaded_at":"$uploadedAt",
            "source":"plaud-bridge-android","status":"$status","title":"Server side \"Q3\" call",
            "summary":"From the list.","marks":[6.0],"has_transcript":true,"text_preview":null,
            "error":${if (error == null) "null" else "\"$error\""}${if (extra.isEmpty()) "" else ",$extra"}}"""
        )
    )

    /** A finished recording the server heard nothing in: no title, no summary, flagged no_speech. */
    private fun noSpeechRecording(uploadedAt: String = "2026-09-01T05:30:00Z") = org.plaudbridge.app.models.ServerRecording.fromJson(
        org.json.JSONObject(
            """{"id":"srv-9","device_sn":"SN-A","session_id":9,"filename":"9.mp3","size_bytes":1,
            "duration_s":61.0,"started_at":"2026-09-07T05:27:31Z","uploaded_at":"$uploadedAt",
            "source":"plaud-bridge-android","status":"done","title":null,"summary":null,"marks":[],
            "has_transcript":true,"text_preview":"","error":null,"no_speech":true}"""
        )
    )

    private val noSpeechTranscript = """{"text":"","segments":[],"no_speech":true}"""

    /** An upload time a minute ago: inside the window in which the router may not have run yet. */
    private fun justNowIso(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(java.util.Date(System.currentTimeMillis() - 60_000L))

    private fun menuOf(activity: FileDetailActivity): android.view.Menu =
        android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu

    private fun launchServer(source: FileDetailActivity.ServerDetailSource, id: String = "srv-9"): FileDetailActivity =
        serverController(source, id).get()

    private fun serverController(
        source: FileDetailActivity.ServerDetailSource, id: String = "srv-9"
    ): org.robolectric.android.controller.ActivityController<FileDetailActivity> {
        FileDetailActivity.serverSource = source
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "tok"
        val intent = Intent(context, FileDetailActivity::class.java)
            .putExtra(FileDetailActivity.EXTRA_SERVER_RECORDING_ID, id)
        return Robolectric.buildActivity(FileDetailActivity::class.java, intent).setup()
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
    fun summaryMarkdownIsRenderedNotShownRaw() {
        val markdownSummary = transcriptJson.replace(
            "\"summary\":\"A greeting.\"",
            "\"summary\":\"## Summary\\n\\n**Key point.** A greeting.\\n\\n- Hello\\n- Hi\""
        )
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(markdownSummary))
        val activity = launchServer(fake)
        val view = activity.findViewById<android.widget.TextView>(R.id.summaryText)
        val text = view.text.toString()
        assertEquals(false, text.contains("**") || text.contains("##") || text.contains("- Hello"))
        assertTrue(text.contains("Key point."))
        val spanned = view.text as android.text.Spanned
        assertEquals(1, spanned.getSpans(0, spanned.length, io.noties.markwon.core.spans.StrongEmphasisSpan::class.java).size)
    }

    @Test
    fun serverModePendingTranscriptShowsEmptyStateWithCheckButton() {
        val fake = FakeServerSource(serverRecording("transcribing"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val activity = launchServer(fake)
        assertEquals("Server side \"Q3\" call", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
        assertEquals("Transcribing", activity.findViewById<android.widget.TextView>(R.id.statusBadge).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertEquals("Transcribing", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptActions).visibility)
        assertEquals("Check for transcript", activity.findViewById<android.widget.Button>(R.id.generateButton).text.toString())
        // No percentage from the server: the bar is there but indeterminate.
        val bar = activity.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.transcriptionProgress)
        assertEquals(View.VISIBLE, bar.visibility)
        assertTrue(bar.isIndeterminate)
    }

    // MARK: - Recordings with no speech

    @Test
    fun noSpeechRecordingShowsItsOwnEmptyStateKeepsThePlayerAndHidesTheTextActions() {
        val fake = FakeServerSource(noSpeechRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(noSpeechTranscript))
        val activity = launchServer(fake)
        // The header says why there is no title rather than showing the server's file name.
        assertEquals("No speech detected", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptionProgress).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertEquals("No speech detected", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
        assertEquals("No speech was detected in this recording.",
            activity.findViewById<android.widget.TextView>(R.id.emptySubtitle).text.toString())
        // Nothing to check for, copy or export; nothing to summarize.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.generateButton).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptActions).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptText).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.summaryHeader).visibility)
        // Nothing was routed either, and no "No automations ran" verdict is needed.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        // The audio is still there to listen to (the player binds when ExoPlayer is available;
        // on the JVM it may not be, so only check that the bind was attempted through the
        // server stream path, which never throws).
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
        assertEquals(false, menu.findItem(R.id.action_run_automations_with_instructions).isVisible)
        assertEquals(false, menu.findItem(R.id.action_copy_summary).isVisible)
        assertEquals(false, menu.findItem(R.id.action_copy_transcript).isVisible)
        assertEquals(false, menu.findItem(R.id.action_export_markdown).isVisible)
        assertTrue(menu.findItem(R.id.action_retranscribe).isVisible)
        assertTrue(menu.findItem(R.id.action_delete).isVisible)
        // Nothing to wait for: no routing polls.
        val before = fake.routingCalls.size
        shadowOf(android.os.Looper.getMainLooper()).idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before, fake.routingCalls.size)
    }

    @Test
    fun noSpeechTranscriptCachedOnThePhoneShowsTheEmptyStateBeforeTheServerAnswers() {
        // The background title sync stored the empty transcript document; opened offline, the
        // phone copy alone must already explain itself.
        val file = storeFile(noSpeechTranscript, serverId = "srv-9")
        val activity = launch(file.id)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertEquals("No speech detected", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.generateButton).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptActions).visibility)
    }

    @Test
    fun aFreshSpokenRecordingOverridesACachedNoSpeechDocument() {
        // Re-transcribed elsewhere: the phone still caches the empty document, the server now
        // has a spoken transcript. The server's word wins, in this direction too.
        val file = storeFile(noSpeechTranscript, serverId = "srv-9")
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        configureServer(fake)
        val activity = launchBoth(file.id)
        assertEquals("Server side \"Q3\" call", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptActions).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertTrue(menuOf(activity).findItem(R.id.action_copy_transcript).isVisible)
        assertTrue(menuOf(activity).findItem(R.id.action_run_automations).isVisible)
        // The cache is brought up to date.
        assertEquals(transcriptJson, RecordingStore.allFiles.single().transcriptJSON)
    }

    @Test
    fun aFreshNoSpeechRecordingSuppressesCachedSpokenText() {
        // The other direction: the phone caches a spoken transcript, the server has since
        // re-transcribed and heard nothing. Its object arrives first, the document later.
        val file = storeFile(transcriptJson, serverId = "srv-9")
        val fake = FakeServerSource(noSpeechRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(noSpeechTranscript))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.transcriptGate = gate
        configureServer(fake)
        val activity = launchBoth(file.id)
        // Recording object known, transcript document still on the wire: no stale text, no actions.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptText).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptActions).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertEquals("No speech detected", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
        assertEquals("No speech was detected in this recording.",
            activity.findViewById<android.widget.TextView>(R.id.emptySubtitle).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.summaryHeader).visibility)
        var menu = menuOf(activity)
        assertEquals(false, menu.findItem(R.id.action_copy_transcript).isVisible)
        assertEquals(false, menu.findItem(R.id.action_export_markdown).isVisible)
        assertEquals(false, menu.findItem(R.id.action_copy_summary).isVisible)
        assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
        fake.transcriptGate = null
        gate.complete(Unit)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptText).visibility)
        assertEquals("No speech detected", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
        menu = menuOf(activity)
        assertEquals(false, menu.findItem(R.id.action_copy_transcript).isVisible)
        // The phone's cache now holds the empty document, so the list agrees next time.
        assertEquals(noSpeechTranscript, RecordingStore.allFiles.single().transcriptJSON)
    }

    @Test
    fun aRecentNoSpeechUploadDoesNotWaitForARouterRun() {
        // Uploaded a minute ago, but silent: the server routes nothing, so there is no run to
        // wait for and no reason to poll the automations.
        val fake = FakeServerSource(noSpeechRecording(uploadedAt = justNowIso()), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(noSpeechTranscript))
        val controller = serverController(fake)
        val activity = controller.get()
        val looper = shadowOf(android.os.Looper.getMainLooper())
        assertEquals(1, fake.routingCalls.size)
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(1, fake.routingCalls.size)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        // Coming back re-reads once, as for any recording, and then stays quiet.
        controller.pause().resume()
        looper.idle()
        assertEquals(2, fake.routingCalls.size)
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(2, fake.routingCalls.size)
    }

    @Test
    fun learningThatARecordingIsSilentEndsAWaitForItsRouterRun() {
        // Opened while transcribing and recently uploaded: a wait for the router's run begins.
        // The transcription then finishes with no speech: the wait is over, and no poll follows.
        val fake = FakeServerSource(serverRecording("transcribing", uploadedAt = justNowIso()), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val activity = launchServer(fake)
        val looper = shadowOf(android.os.Looper.getMainLooper())
        fake.rec = noSpeechRecording(uploadedAt = justNowIso())
        fake.transcript = org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(noSpeechTranscript)
        val before = fake.routingCalls.size
        activity.findViewById<View>(R.id.generateButton).performClick()
        looper.idle()
        assertEquals("No speech detected", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
        // The load re-reads the automations (once for the object, once for the transcript's
        // arrival), then nothing: no run is coming.
        assertEquals(before + 2, fake.routingCalls.size)
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before + 2, fake.routingCalls.size)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
    }

    @Test
    fun noSpeechRecordingBeingReTranscribedShowsTheProgressNotTheEmptyVerdict() {
        val file = storeFile(noSpeechTranscript, serverId = "srv-9")
        val fake = FakeServerSource(serverRecording("pending").copy(noSpeech = false, title = null), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        configureServer(fake)
        val activity = launchBoth(file.id)
        assertEquals("Waiting to transcribe", activity.findViewById<android.widget.TextView>(R.id.statusBadge).text.toString())
        assertEquals("Waiting to transcribe", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptionProgress).visibility)
    }

    // MARK: - Transcription progress

    @Test
    fun transcribingStageAndPercentShowInTheBadgeAndTheBar() {
        val fake = FakeServerSource(
            serverRecording("transcribing", extra = """"stage":"transcribing","progress":0.428"""),
            org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending
        )
        val activity = launchServer(fake)
        assertEquals("Transcribing · 42%", activity.findViewById<android.widget.TextView>(R.id.statusBadge).text.toString())
        assertEquals("Transcribing · 42%", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
        val bar = activity.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.transcriptionProgress)
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals(false, bar.isIndeterminate)
        assertEquals(42, bar.progress)
    }

    @Test
    fun transcriptionPollsEveryFewSecondsWhileWorkingThenLoadsTheTranscriptAndStops() {
        val fake = FakeServerSource(
            serverRecording("transcribing", extra = """"stage":"transcribing","progress":0.1"""),
            org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending
        )
        val controller = serverController(fake)
        val activity = controller.get()
        val looper = shadowOf(android.os.Looper.getMainLooper())
        val badge = activity.findViewById<android.widget.TextView>(R.id.statusBadge)
        val bar = activity.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.transcriptionProgress)
        assertEquals("Transcribing · 10%", badge.text.toString())
        val before = fake.recordingCalls.size
        // The server moves on; nothing is read before the interval, one read at the interval.
        fake.rec = serverRecording("transcribing", extra = """"stage":"transcribing","progress":0.999""")
        looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS - 100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before, fake.recordingCalls.size)
        looper.idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 1, fake.recordingCalls.size)
        // Rounded down and capped: never 100% while still transcribing.
        assertEquals("Transcribing · 99%", badge.text.toString())
        assertEquals(99, bar.progress)
        // Stages without a percentage: words only, bar indeterminate.
        fake.rec = serverRecording("transcribing", extra = """"stage":"diarizing","progress":1.0""")
        looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 2, fake.recordingCalls.size)
        assertEquals("Identifying speakers", badge.text.toString())
        assertTrue(bar.isIndeterminate)
        fake.rec = serverRecording("transcribing", extra = """"stage":"summarizing"""")
        looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("Summarizing", badge.text.toString())
        // Done: the object the poll brought back is the one shown (no second read of it), the
        // transcript is loaded from it, the badge and bar go, polling stops.
        val beforeDone = fake.recordingCalls.size
        fake.rec = serverRecording()
        fake.transcript = org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson)
        looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(beforeDone + 1, fake.recordingCalls.size)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
        assertEquals(View.GONE, badge.visibility)
        assertEquals(View.GONE, bar.visibility)
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(beforeDone + 1, fake.recordingCalls.size)
    }

    @Test
    fun aPollThatSeesTheTranscriptionFailShowsThatAndStops() {
        val fake = FakeServerSource(serverRecording("transcribing"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val activity = launchServer(fake)
        val looper = shadowOf(android.os.Looper.getMainLooper())
        val before = fake.recordingCalls.size
        fake.rec = serverRecording("failed", error = "GPU on fire")
        looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        // Exactly one read: the failed object the poll got is the one shown, not re-fetched.
        assertEquals(before + 1, fake.recordingCalls.size)
        assertEquals("Failed", activity.findViewById<android.widget.TextView>(R.id.statusBadge).text.toString())
        assertEquals("GPU on fire", activity.findViewById<android.widget.TextView>(R.id.emptySubtitle).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptionProgress).visibility)
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before + 1, fake.recordingCalls.size)
    }

    @Test
    fun legacyPhoneCopyKeepsWatchingTheTranscriptionAcrossALeaveAndATransientFailure() {
        // A phone copy without a server id: the lookup resolves it and the transcript answers 409.
        // No recording object yet, but the 409 is enough to keep watching, across a leave and
        // a failed first read.
        val fake = FakeServerSource(serverRecording("transcribing"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        FileDetailActivity.serverSource = fake
        val http = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            RecordingStore.serverBaseUrl = http.url("/").toString().trimEnd('/')
            RecordingStore.serverAuthToken = "tok"
            http.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("""{"id":"srv-9"}"""))
            http.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(409))
            val file = storeFile(null, serverId = null, uploaded = true)
            val controller = controller(file.id)
            val activity = controller.get()
            val looper = shadowOf(android.os.Looper.getMainLooper())
            val deadline = System.currentTimeMillis() + 10_000
            while (fake.routingCalls.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                looper.idle()
            }
            assertEquals("srv-9", RecordingStore.allFiles.single().serverId)
            assertTrue(fake.recordingCalls.isEmpty())
            // Leave before the first poll: nothing is read while away.
            controller.pause()
            looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
            assertTrue(fake.recordingCalls.isEmpty())
            // Back: asked at once. The read fails; the watch goes on and the next one lands.
            fake.recordingFailures = 1
            controller.resume()
            looper.idle()
            assertEquals(1, fake.recordingCalls.size)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
            looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertEquals(2, fake.recordingCalls.size)
            assertEquals("Transcribing", activity.findViewById<android.widget.TextView>(R.id.statusBadge).text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptionProgress).visibility)
            // Finished: the transcript comes through the object the poll returned.
            fake.rec = serverRecording()
            fake.transcript = org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson)
            looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertEquals(3, fake.recordingCalls.size)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
            looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
            assertEquals(3, fake.recordingCalls.size)
        } finally {
            http.shutdown()
        }
    }

    @Test
    fun transcriptionPollingPausesWithTheScreenAndAsksAtOnceOnReturn() {
        val fake = FakeServerSource(serverRecording("pending"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val controller = serverController(fake)
        val activity = controller.get()
        val looper = shadowOf(android.os.Looper.getMainLooper())
        assertEquals("Waiting to transcribe", activity.findViewById<android.widget.TextView>(R.id.statusBadge).text.toString())
        controller.pause()
        val before = fake.recordingCalls.size
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before, fake.recordingCalls.size)
        fake.rec = serverRecording("transcribing", extra = """"stage":"transcribing","progress":0.5""")
        controller.resume()
        looper.idle()
        assertEquals(before + 1, fake.recordingCalls.size)
        assertEquals("Transcribing · 50%", activity.findViewById<android.widget.TextView>(R.id.statusBadge).text.toString())
        looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 2, fake.recordingCalls.size)
    }

    @Test
    fun aFinishedRecordingIsNeverPolled() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        launchServer(fake)
        val before = fake.recordingCalls.size
        shadowOf(android.os.Looper.getMainLooper()).idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before, fake.recordingCalls.size)
    }

    // MARK: - Run automations with instructions

    private fun latestDialog() = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog

    @Test
    fun runWithInstructionsSendsTheTrimmedTextWithItsOwnKey() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertTrue(menu.findItem(R.id.action_run_automations_with_instructions).isVisible)
        assertTrue(menu.findItem(R.id.action_run_automations_with_instructions).isEnabled)
        assertTrue(activity.onMenuAction(R.id.action_run_automations_with_instructions))
        val dialog = latestDialog()
        val run = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        val field = dialog.findViewById<android.widget.EditText>(R.id.instructionsField)!!
        // Run is off until there is something to send; blanks do not count.
        assertEquals(false, run.isEnabled)
        field.setText("   ")
        assertEquals(false, run.isEnabled)
        field.setText("  file this as a work meeting  ")
        assertTrue(run.isEnabled)
        run.performClick()
        val looper = shadowOf(android.os.Looper.getMainLooper())
        looper.idle()
        assertEquals(listOf("srv-9"), fake.reruns)
        assertEquals(listOf<String?>("file this as a work meeting"), fake.rerunInstructions)
        assertEquals(1, fake.rerunKeys.size)
        assertEquals("Automations queued", ShadowToast.getTextOfLatestToast())
        // A plain run afterwards is a new intent: its own key, no instructions.
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(listOf<String?>("file this as a work meeting", null), fake.rerunInstructions)
        assertEquals(2, fake.rerunKeys.toSet().size)
    }

    @Test
    fun instructionsAreCappedAtTheServersLimit() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        assertTrue(activity.onMenuAction(R.id.action_run_automations_with_instructions))
        val dialog = latestDialog()
        val field = dialog.findViewById<android.widget.EditText>(R.id.instructionsField)!!
        field.setText("x".repeat(2500))
        // The field itself stops at the limit, and the counter shows it.
        assertEquals(2000, field.text.length)
        val layout = dialog.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.instructionsLayout)!!
        assertTrue(layout.isCounterEnabled)
        assertEquals(2000, layout.counterMaxLength)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("x".repeat(2000), fake.rerunInstructions.single())
    }

    @Test
    fun cancellingTheInstructionsDialogSendsNothing() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        assertTrue(activity.onMenuAction(R.id.action_run_automations_with_instructions))
        val dialog = latestDialog()
        dialog.findViewById<android.widget.EditText>(R.id.instructionsField)!!.setText("never mind")
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(fake.reruns.isEmpty())
        assertEquals(false, dialog.isShowing)
    }

    @Test
    fun anAmbiguousFailureReplaysTheSameKeyAndTheSameInstructions() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(id = "run-1", deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Old") + "]")}],"deliveries":[]}""")
        fake.rerunResult = org.plaudbridge.app.net.ApiClient.ActionResult.Error("timeout")
        val activity = launchServer(fake)
        val looper = shadowOf(android.os.Looper.getMainLooper())
        assertTrue(activity.onMenuAction(R.id.action_run_automations_with_instructions))
        latestDialog().apply {
            findViewById<android.widget.EditText>(R.id.instructionsField)!!.setText("file as work")
            getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        looper.idle()
        // Reconciliation reads exhausted with nothing new: the guard is back, the intent pending.
        looper.idleFor(FileDetailActivity.RERUN_REFRESH_DELAYS_MS[1], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(1, fake.reruns.size)
        // Same instructions again: the same intent, replayed with the same key.
        assertTrue(activity.onMenuAction(R.id.action_run_automations_with_instructions))
        latestDialog().apply {
            findViewById<android.widget.EditText>(R.id.instructionsField)!!.setText("file as work")
            getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        looper.idle()
        assertEquals(2, fake.reruns.size)
        assertEquals(fake.rerunKeys[0], fake.rerunKeys[1])
        assertEquals(listOf<String?>("file as work", "file as work"), fake.rerunInstructions)
        looper.idleFor(FileDetailActivity.RERUN_REFRESH_DELAYS_MS[1], java.util.concurrent.TimeUnit.MILLISECONDS)
        // Still unresolved. Different instructions now do NOT start a second request that could
        // run alongside the first: the earlier intent is replayed as it was, and the user is told.
        ShadowToast.reset()
        assertTrue(activity.onMenuAction(R.id.action_run_automations_with_instructions))
        latestDialog().apply {
            findViewById<android.widget.EditText>(R.id.instructionsField)!!.setText("file as personal")
            getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        looper.idle()
        assertEquals(3, fake.reruns.size)
        assertEquals(fake.rerunKeys[0], fake.rerunKeys[2])
        assertEquals("file as work", fake.rerunInstructions[2])
        assertEquals("Retrying the earlier run first. Run again once it has finished.", ShadowToast.getTextOfLatestToast())
        looper.idleFor(FileDetailActivity.RERUN_REFRESH_DELAYS_MS[1], java.util.concurrent.TimeUnit.MILLISECONDS)
        // A plain run while it is still unresolved replays it too, instructions included.
        ShadowToast.reset()
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(4, fake.reruns.size)
        assertEquals(fake.rerunKeys[0], fake.rerunKeys[3])
        assertEquals("file as work", fake.rerunInstructions[3])
        assertEquals("Retrying the earlier run first. Run again once it has finished.", ShadowToast.getTextOfLatestToast())
        looper.idleFor(FileDetailActivity.RERUN_REFRESH_DELAYS_MS[1], java.util.concurrent.TimeUnit.MILLISECONDS)
        // The server finally answers for real: the intent is settled, and the next tap is new.
        fake.rerunResult = org.plaudbridge.app.net.ApiClient.ActionResult.Ok
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(5, fake.reruns.size)
        assertEquals(fake.rerunKeys[0], fake.rerunKeys[4])
        assertEquals("Automations queued", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.onMenuAction(R.id.action_run_automations_with_instructions))
        latestDialog().apply {
            findViewById<android.widget.EditText>(R.id.instructionsField)!!.setText("file as personal")
            getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        looper.idle()
        assertEquals(6, fake.reruns.size)
        assertEquals(false, fake.rerunKeys[5] == fake.rerunKeys[0])
        assertEquals("file as personal", fake.rerunInstructions[5])
    }

    @Test
    fun destructivePromptsNameTheRecordingAsTheScreenDoes() {
        // Server-only, silent: the header says "No speech detected", so must the Delete prompt.
        val fake = FakeServerSource(noSpeechRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(noSpeechTranscript))
        val serverOnly = launchServer(fake)
        assertTrue(serverOnly.onMenuAction(R.id.action_delete))
        var message = latestDialog().findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message, message.contains("\"No speech detected\""))
        assertEquals(false, message.contains("9.mp3"))
        latestDialog().dismiss()
        // Paired with a phone copy that has audio: Remove from phone and Delete say the same.
        RecordingStore.clearAll()
        configureServer(fake)
        val file = storeFile(noSpeechTranscript, serverId = "srv-9", localPath = File(context.filesDir, "7.mp3").absolutePath, serverTitle = null)
        val both = launchBoth(file.id)
        assertEquals("No speech detected", both.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
        assertTrue(both.onMenuAction(R.id.action_remove_from_phone))
        message = latestDialog().findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message, message.contains("\"No speech detected\""))
        assertEquals(false, message.contains("Untitled Recording"))
        latestDialog().dismiss()
        assertTrue(both.onMenuAction(R.id.action_delete))
        message = latestDialog().findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message, message.contains("\"No speech detected\""))
        assertEquals(false, message.contains("Untitled Recording"))
    }

    @Test
    fun onlyOnePollReadIsOnTheWireAndALeaveAbandonsIt() {
        val fake = FakeServerSource(serverRecording("transcribing"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val controller = serverController(fake)
        val activity = controller.get()
        val looper = shadowOf(android.os.Looper.getMainLooper())
        val before = fake.recordingCalls.size
        // Hold the first poll's read on the wire.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.recordingGate = gate
        looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 1, fake.recordingCalls.size)
        // However long it takes, no second read starts behind it, whatever re-renders happen.
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        controller.pause().resume() // a pause abandons it; the return asks afresh, once
        looper.idle()
        assertEquals(before + 2, fake.recordingCalls.size)
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before + 2, fake.recordingCalls.size)
        // Both reads come back with "done": only the current one is applied (one transcript load).
        val transcriptReadsBefore = fake.transcriptCalls.size
        fake.rec = serverRecording()
        fake.transcript = org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson)
        fake.recordingGate = null
        gate.complete(Unit)
        looper.idle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
        assertEquals(transcriptReadsBefore + 1, fake.transcriptCalls.size)
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before + 2, fake.recordingCalls.size)
    }

    @Test
    fun aStaleAnswerFromBeforeALeaveCannotOverrideTheCurrentOne() {
        // The read abandoned on pause would have said "done"; the one the return fires says the
        // server is transcribing again (a re-transcribe from elsewhere). The stale "done" must
        // not land afterwards and end the polling.
        val fake = FakeServerSource(serverRecording("transcribing"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val controller = serverController(fake)
        val activity = controller.get()
        val looper = shadowOf(android.os.Looper.getMainLooper())
        val staleGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.recordingGate = staleGate
        looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        controller.pause()
        // The return's read answers at once with "transcribing".
        fake.recordingGate = null
        controller.resume()
        looper.idle()
        assertEquals("Transcribing", activity.findViewById<android.widget.TextView>(R.id.statusBadge).text.toString())
        // Now the stale read would answer "done": it was abandoned, so nothing changes.
        val transcriptReads = fake.transcriptCalls.size
        fake.rec = serverRecording()
        fake.transcript = org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson)
        staleGate.complete(Unit)
        looper.idle()
        assertEquals("Transcribing", activity.findViewById<android.widget.TextView>(R.id.statusBadge).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptText).visibility)
        assertEquals(transcriptReads, fake.transcriptCalls.size)
        // Polling is still alive and picks the real finish up on its next tick.
        val before = fake.recordingCalls.size
        looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 1, fake.recordingCalls.size)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
    }

    @Test
    fun aRunStartedWithInstructionsShowsThemOnItsCard() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val withInstructions = run(deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Saved") + "]")
            .replaceFirst("\"model\":\"claude-acp\",", "\"model\":\"claude-acp\",\"instructions\":\"file this as a work meeting\",")
        fake.routing = runsOf("""{"runs":[$withInstructions],"deliveries":[]}""")
        val activity = launchServer(fake)
        val block = automationsList(activity).getChildAt(0)
        assertEquals("“Instructions: file this as a work meeting”", textOf(block, R.id.automation_run_instructions))
        // A plain run carries no such line.
        fake.routing = runsOf("""{"runs":[${run()}],"deliveries":[]}""")
        activity.onMenuAction(R.id.action_run_automations)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(FileDetailActivity.RERUN_REFRESH_DELAYS_MS[0], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(null, automationsList(activity).getChildAt(0).findViewById<View>(R.id.automation_run_instructions))
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
    fun manualRenameOnThePhoneHeadsThePageOverTheServerTitle() {
        val file = storeFile(transcriptJson, serverId = "srv-9")
        RecordingStore.renameFile(file, "Walk with Sam")
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        configureServer(fake)
        val activity = launchBoth(file.id)
        // The server says "Server side Q3 call"; the user's own name is pinned and wins.
        assertEquals("Walk with Sam", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
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
        // Remove from phone needs audio on the phone, so the phone copy has a path here.
        val file = storeFile(null, serverId = "srv-9", localPath = File(context.filesDir, "7.mp3").absolutePath)
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
        // The entry stays, flagged, so the row keeps its server link and the sync flows do not
        // download the session again; only the audio path is gone.
        val kept = RecordingStore.allFiles.single()
        assertTrue(kept.removedFromPhone)
        assertEquals(null, kept.localPath)
        assertEquals("srv-9", kept.serverId)
        assertEquals("Removed from this phone", ShadowToast.getTextOfLatestToast())
        // Phone actions are gone with the audio; the server ones stay.
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertEquals(false, menu.findItem(R.id.action_export).isVisible)
        assertEquals(false, menu.findItem(R.id.action_remove_from_phone).isVisible)
        assertTrue(menu.findItem(R.id.action_retranscribe).isVisible)
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

    // MARK: - Automations section

    private fun runsOf(json: String) = org.plaudbridge.app.net.ApiClient.RoutingResult.Ok(
        org.plaudbridge.app.models.RoutingRun.listFromJson(json)
    )

    private fun metaDate(iso: String): String =
        java.text.SimpleDateFormat("MMM d, yyyy \u00b7 HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(org.plaudbridge.app.models.ServerRecording.parseIso(iso)!!))

    private fun delivery(
        id: String, route: String, status: String, resultStatus: String? = null, summary: String? = null,
        lastError: String? = null, resultAt: String? = null, actionType: String = "webhook"
    ) = """{"id":"$id","router_run_id":"run-1","route_name":"$route","action_type":"$actionType","status":"$status",
        "attempts":1,"last_error":${lastError?.let { "\"$it\"" } ?: "null"},"created_at":"2026-09-07T06:39:05Z",
        "result_status":${resultStatus?.let { "\"$it\"" } ?: "null"},
        "result_summary":${summary?.let { "\"$it\"" } ?: "null"},
        "result_at":${resultAt?.let { "\"$it\"" } ?: "null"},"payload":{"x":1}}"""

    private fun run(
        id: String = "run-1", createdAt: String = "2026-09-07T06:39:00Z", error: String? = null,
        routes: String = """[{"name":"meetings","reason":"The speaker explicitly directs how this recording should be filed."}]""",
        deliveries: String = "[]"
    ) = """{"id":"$id","recording_id":"srv-9","created_at":"$createdAt","model":"claude-acp",
        "error":${error?.let { "\"$it\"" } ?: "null"},"decision":{"routes":$routes},"deliveries":$deliveries}"""

    private fun automationsList(activity: FileDetailActivity) =
        activity.findViewById<android.widget.LinearLayout>(R.id.automationsList)

    private fun textOf(parent: View, id: Int): String = parent.findViewById<android.widget.TextView>(id).text.toString()

    @Test
    fun automationsShowTheMatchedRouteAndTheAgentsSummary() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", "done",
            "Saved to Meetings/Garage Inventory.md", resultAt = "2026-09-07T06:40:10Z") + "]")}],"deliveries":[]}""")
        val controller = serverController(fake)
        val activity = controller.get()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsShowEarlier).visibility)
        val list = automationsList(activity)
        assertEquals(View.VISIBLE, list.visibility)
        assertEquals(1, list.childCount)
        val block = list.getChildAt(0)
        assertEquals("meetings", textOf(block, R.id.automation_route_name))
        assertEquals("The speaker explicitly directs how this recording should be filed.", textOf(block, R.id.automation_route_reason))
        assertEquals("Done", textOf(block, R.id.automation_delivery_pill))
        assertEquals("Saved to Meetings/Garage Inventory.md", textOf(block, R.id.automation_delivery_text))
        // The agent's report time, not the hand-off time, in the meta line's format.
        assertEquals(metaDate("2026-09-07T06:40:10Z"), textOf(block, R.id.automation_delivery_time))
        assertEquals(null, block.findViewById<View>(R.id.automation_retry))
        // Re-binding (the resume reload) must not duplicate blocks.
        controller.pause().resume()
        assertEquals(1, automationsList(activity).childCount)
    }

    @Test
    fun automationsReasonUnfoldsOnTap() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run()}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val reason = automationsList(activity).getChildAt(0).findViewById<android.widget.TextView>(R.id.automation_route_reason)
        assertEquals(3, reason.maxLines)
        reason.performClick()
        assertEquals(Int.MAX_VALUE, reason.maxLines)
        reason.performClick()
        assertEquals(3, reason.maxLines)
    }

    @Test
    fun queuedDeliveryShowsWorkingAndPollsAgainThenStops() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "ask-claude", "ok", "queued") + "]")}],"deliveries":[]}""")
        val controller = serverController(fake)
        val activity = controller.get()
        val block = automationsList(activity).getChildAt(0)
        assertEquals("Working", textOf(block, R.id.automation_delivery_pill))
        assertEquals("Working\u2026", textOf(block, R.id.automation_delivery_text))
        assertEquals(null, block.findViewById<View>(R.id.automation_retry))
        val before = fake.routingCalls.size
        val looper = shadowOf(android.os.Looper.getMainLooper())
        // Nothing before the first poll delay has passed, then one read per scheduled delay. The
        // 100 ms margin absorbs the few milliseconds Robolectric's clock moves during setup().
        looper.idleFor(FileDetailActivity.ROUTING_POLL_DELAYS_MS[0] - 100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before, fake.routingCalls.size)
        looper.idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 1, fake.routingCalls.size)
        looper.idleFor(FileDetailActivity.ROUTING_POLL_DELAYS_MS[1], java.util.concurrent.TimeUnit.MILLISECONDS)
        looper.idleFor(FileDetailActivity.ROUTING_POLL_DELAYS_MS[2], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 3, fake.routingCalls.size)
        // Budget spent: still queued, but no more polling until something triggers a refresh.
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before + 3, fake.routingCalls.size)
        // The agent finished in the meantime; coming back to the screen picks that up.
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "ask-claude", "ok", "done", "Session started") + "]")}],"deliveries":[]}""")
        controller.pause().resume()
        looper.idle()
        assertEquals(before + 4, fake.routingCalls.size)
        assertEquals("Session started", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
    }

    @Test
    fun failedDeliveryOffersRetryWhichCallsTheApiAndRefreshes() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(routes = """[{"name":"obsidian-inbox","reason":"A note request."}]""",
            deliveries = "[" + delivery("d-7", "obsidian-inbox", "failed", lastError = "webhook: 502 Bad Gateway") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val block = automationsList(activity).getChildAt(0)
        assertEquals("Failed", textOf(block, R.id.automation_delivery_pill))
        assertEquals("webhook: 502 Bad Gateway", textOf(block, R.id.automation_delivery_text))
        val retry = block.findViewById<android.widget.TextView>(R.id.automation_retry)
        assertNotNull(retry)
        assertEquals("Retry", retry.text.toString())
        val before = fake.routingCalls.size
        fake.routing = runsOf("""{"runs":[${run(routes = """[{"name":"obsidian-inbox","reason":"A note request."}]""",
            deliveries = "[" + delivery("d-7", "obsidian-inbox", "ok", "done", "Saved to 0_Quick Add/Note.md") + "]")}],"deliveries":[]}""")
        retry.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf("d-7"), fake.retries)
        assertEquals("Retry queued", ShadowToast.getTextOfLatestToast())
        assertEquals(before + 1, fake.routingCalls.size)
        val refreshed = automationsList(activity).getChildAt(0)
        assertEquals("Done", textOf(refreshed, R.id.automation_delivery_pill))
        assertEquals(null, refreshed.findViewById<View>(R.id.automation_retry))
    }

    @Test
    fun agentFailureShowsItsSummaryAndRetry() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-2", "meetings", "ok", "failed", "Vault path not writable") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val block = automationsList(activity).getChildAt(0)
        assertEquals("Failed", textOf(block, R.id.automation_delivery_pill))
        assertEquals("Vault path not writable", textOf(block, R.id.automation_delivery_text))
        assertNotNull(block.findViewById<View>(R.id.automation_retry))
    }

    @Test
    fun handedOffWithoutAReportShowsTheHandOffState() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-3", "meetings", "ok") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val block = automationsList(activity).getChildAt(0)
        assertEquals("Handed off", textOf(block, R.id.automation_delivery_pill))
        assertEquals(metaDate("2026-09-07T06:39:05Z"), textOf(block, R.id.automation_delivery_time))
        assertEquals(null, block.findViewById<View>(R.id.automation_retry))
    }

    @Test
    fun emptyRoutesSayNothingMatchedWithTheRunTime() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(routes = "[]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        val block = automationsList(activity).getChildAt(0)
        assertEquals("No automation matched \u00b7 " + metaDate("2026-09-07T06:39:00Z"), textOf(block, R.id.automation_no_match))
        assertEquals(null, block.findViewById<View>(R.id.automation_route_name))
    }

    @Test
    fun runErrorShowsInTheErrorColor() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(routes = "[]", error = "router: model timed out")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val block = automationsList(activity).getChildAt(0)
        val error = block.findViewById<android.widget.TextView>(R.id.automation_run_error)
        assertEquals("router: model timed out", error.text.toString())
        assertEquals(androidx.core.content.ContextCompat.getColor(activity, R.color.red), error.currentTextColor)
        assertEquals(null, block.findViewById<View>(R.id.automation_no_match))
    }

    @Test
    fun noRunsOnATranscribedRecordingShowsTheOneLineEmptyState() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        assertEquals("No automations ran for this recording",
            activity.findViewById<android.widget.TextView>(R.id.automationsEmpty).text.toString())
        assertEquals(View.GONE, automationsList(activity).visibility)
    }

    @Test
    fun noRunsWhileStillTranscribingKeepsTheSectionHiddenAndTheMenuActionAway() {
        val fake = FakeServerSource(serverRecording("transcribing"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val activity = launchServer(fake)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        // Without a transcript the router has nothing to read (the server would answer 409).
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
        assertTrue(menu.findItem(R.id.action_retranscribe).isVisible)
    }

    @Test
    fun routingFetchFailureIsSilent() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = org.plaudbridge.app.net.ApiClient.RoutingResult.NotFound
        val activity = launchServer(fake)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
    }

    @Test
    fun phoneOnlyRecordingNeverAsksForRouting() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        configureServer(fake)
        val file = storeFile(transcriptJson, serverId = null, uploaded = false)
        val activity = launch(file.id)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertTrue(fake.routingCalls.isEmpty())
    }

    @Test
    fun onlyTheLatestRunShowsUntilEarlierRunsAreRequested() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[
            ${run(id = "run-2", createdAt = "2026-09-07T07:00:00Z", routes = """[{"name":"ask-claude","reason":"Second pass."}]""")},
            ${run(id = "run-1", createdAt = "2026-09-07T06:39:00Z")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val list = automationsList(activity)
        assertEquals(1, list.childCount)
        assertEquals("ask-claude", textOf(list.getChildAt(0), R.id.automation_route_name))
        val more = activity.findViewById<android.widget.TextView>(R.id.automationsShowEarlier)
        assertEquals(View.VISIBLE, more.visibility)
        assertEquals("Show earlier runs", more.text.toString())
        more.performClick()
        assertEquals(2, list.childCount)
        assertEquals("meetings", textOf(list.getChildAt(1), R.id.automation_route_name))
        assertEquals("Earlier run \u00b7 " + metaDate("2026-09-07T06:39:00Z"), textOf(list.getChildAt(1), R.id.automation_run_header))
        assertEquals(View.GONE, more.visibility)
    }

    @Test
    fun runAutomationsMenuCallsTheApiAndRefreshesOnSchedule() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertTrue(menu.findItem(R.id.action_run_automations).isVisible)
        val before = fake.routingCalls.size
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        val looper = shadowOf(android.os.Looper.getMainLooper())
        looper.idle()
        assertEquals(listOf("srv-9"), fake.reruns)
        assertEquals("Automations queued", ShadowToast.getTextOfLatestToast())
        assertEquals(before, fake.routingCalls.size)
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Saved") + "]")}],"deliveries":[]}""")
        looper.idleFor(FileDetailActivity.RERUN_REFRESH_DELAYS_MS[0], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 1, fake.routingCalls.size)
        assertEquals("Saved", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
        looper.idleFor(FileDetailActivity.RERUN_REFRESH_DELAYS_MS[1] - FileDetailActivity.RERUN_REFRESH_DELAYS_MS[0], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 2, fake.routingCalls.size)
    }

    @Test
    fun phoneOnlyMenuHidesRunAutomations() {
        val file = storeFile(transcriptJson, serverId = null, uploaded = false)
        val activity = launch(file.id)
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
    }

    @Test
    fun queuedDeliveryOnAnOlderRunKeepsPolling() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        // Latest run finished; a Retry on the older run left its delivery queued.
        fake.routing = runsOf("""{"runs":[
            ${run(id = "run-2", createdAt = "2026-09-07T07:00:00Z", deliveries = "[" + delivery("d-2", "meetings", "ok", "done", "Saved") + "]")},
            ${run(id = "run-1", createdAt = "2026-09-07T06:39:00Z", deliveries = "[" + delivery("d-1", "meetings", "ok", "queued") + "]")}],
            "deliveries":[]}""")
        val activity = launchServer(fake)
        val before = fake.routingCalls.size
        shadowOf(android.os.Looper.getMainLooper()).idleFor(FileDetailActivity.ROUTING_POLL_DELAYS_MS[0], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 1, fake.routingCalls.size)
        assertEquals(1, automationsList(activity).childCount)
    }

    /**
     * Open while transcribing, then Check for transcript with the transcript now ready: the
     * router runs detached after transcription, so the first routing read is empty.
     */
    private fun openTranscribingThenTranscriptArrives(
        fake: FakeServerSource, launched: FileDetailActivity? = null
    ): FileDetailActivity {
        val activity = launched ?: launchServer(fake)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        fake.rec = serverRecording()
        fake.transcript = org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson)
        activity.findViewById<View>(R.id.generateButton).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
        return activity
    }

    @Test
    fun transcriptArrivalWaitsForTheRouterRunInsteadOfSayingNothingRan() {
        val fake = FakeServerSource(serverRecording("transcribing"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val activity = openTranscribingThenTranscriptArrives(fake)
        // Routing answered with no runs yet: no verdict on screen, a poll pending.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        val before = fake.routingCalls.size
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Saved to Meetings/Note.md") + "]")}],"deliveries":[]}""")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(FileDetailActivity.ROUTING_POLL_DELAYS_MS[0], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 1, fake.routingCalls.size)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertEquals("Saved to Meetings/Note.md", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
        // The run is there and finished: nothing more to poll for.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before + 1, fake.routingCalls.size)
    }

    @Test
    fun waitingForARunStopsPollingAfterTheBudgetButKeepsTheVerdictOpen() {
        val fake = FakeServerSource(serverRecording("transcribing"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val controller = serverController(fake)
        val activity = controller.get()
        openTranscribingThenTranscriptArrives(fake, activity)
        val before = fake.routingCalls.size
        val looper = shadowOf(android.os.Looper.getMainLooper())
        for (delay in FileDetailActivity.ROUTING_POLL_DELAYS_MS) {
            assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
            looper.idleFor(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        assertEquals(before + FileDetailActivity.ROUTING_POLL_DELAYS_MS.size, fake.routingCalls.size)
        // Budget spent: no more reads, and no "No automations ran" either, since a slow router
        // may still deliver a run. The section simply stays out of the way.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before + FileDetailActivity.ROUTING_POLL_DELAYS_MS.size, fake.routingCalls.size)
        // Coming back asks again, and the late run is shown.
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Late but done") + "]")}],"deliveries":[]}""")
        controller.pause().resume()
        looper.idle()
        assertEquals("Late but done", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
    }

    @Test
    fun openingAnAlreadyTranscribedRecordingDoesNotWaitBeforeSayingNothingRan() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        val before = fake.routingCalls.size
        shadowOf(android.os.Looper.getMainLooper()).idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before, fake.routingCalls.size)
    }

    @Test
    fun recentlyUploadedRecordingWaitsForItsRouterRun() {
        // Uploaded a minute ago: the transcript may be done while the detached router has not
        // inserted its run yet, so an empty first answer is not a verdict.
        val justNow = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(System.currentTimeMillis() - 60_000L))
        val fake = FakeServerSource(serverRecording(uploadedAt = justNow), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        val before = fake.routingCalls.size
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Saved") + "]")}],"deliveries":[]}""")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(FileDetailActivity.ROUTING_POLL_DELAYS_MS[0], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 1, fake.routingCalls.size)
        assertEquals("Saved", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
    }

    @Test
    fun retranscribeWaitsForARunNewerThanTheOneOnScreen() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(id = "run-1", deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Old outcome") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        assertEquals("Old outcome", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
        // Re-transcribe; the replacement transcript (byte for byte the same text, the hard case)
        // is ready by the time the screen re-reads it, but the router has only the old run so far.
        assertTrue(activity.onMenuAction(R.id.action_retranscribe))
        val looper = shadowOf(android.os.Looper.getMainLooper())
        looper.idle()
        assertEquals("Transcription queued", ShadowToast.getTextOfLatestToast())
        assertEquals("Old outcome", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
        val before = fake.routingCalls.size
        fake.routing = runsOf("""{"runs":[
            ${run(id = "run-2", createdAt = "2026-09-07T07:00:00Z", deliveries = "[" + delivery("d-2", "meetings", "ok", "done", "New outcome") + "]")},
            ${run(id = "run-1")}],"deliveries":[]}""")
        looper.idleFor(FileDetailActivity.ROUTING_POLL_DELAYS_MS[0], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 1, fake.routingCalls.size)
        assertEquals("New outcome", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automationsShowEarlier).visibility)
        // The newer run arrived: the wait is over.
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before + 1, fake.routingCalls.size)
    }

    @Test
    fun legacyPhoneCopyLoadsRoutingOnceTheServerIdIsResolved() {
        // A phone copy uploaded before the app kept server ids: the id comes from the lookup
        // endpoint (a real HTTP call, hence MockWebServer), and only then can routing be read.
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Filed") + "]")}],"deliveries":[]}""")
        FileDetailActivity.serverSource = fake
        val http = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            RecordingStore.serverBaseUrl = http.url("/").toString().trimEnd('/')
            RecordingStore.serverAuthToken = "tok"
            http.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("""{"id":"srv-9"}"""))
            http.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(transcriptJson))
            val file = storeFile(null, serverId = null, uploaded = true)
            val activity = launch(file.id)
            val looper = shadowOf(android.os.Looper.getMainLooper())
            // The lookup and transcript fetch run on Dispatchers.IO; give them a moment to land.
            val deadline = System.currentTimeMillis() + 10_000
            while (fake.routingCalls.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                looper.idle()
            }
            assertEquals(listOf("srv-9"), fake.routingCalls)
            assertEquals("srv-9", RecordingStore.allFiles.single().serverId)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automationsHeader).visibility)
            assertEquals("Filed", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
            assertEquals("/api/v1/recordings/lookup?device_sn=SN-A&session_id=7", http.takeRequest().path)
            assertEquals("/api/v1/recordings/srv-9/transcript", http.takeRequest().path)
        } finally {
            http.shutdown()
        }
    }

    @Test
    fun staleQueuedDeliveryShowsNoReportAndOffersRetry() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        // The server's own verdict on a job that never reported back within its deadline.
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-9", "ask-claude", "ok", "unknown", "No result was reported") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val block = automationsList(activity).getChildAt(0)
        val pill = block.findViewById<android.widget.TextView>(R.id.automation_delivery_pill)
        assertEquals("No report", pill.text.toString())
        // Neutral, not alarming: the job was accepted, the outcome is simply not known.
        assertEquals(androidx.core.content.ContextCompat.getColor(activity, R.color.gray7), pill.currentTextColor)
        assertEquals("No result reported", textOf(block, R.id.automation_delivery_text))
        assertNotNull(block.findViewById<View>(R.id.automation_retry))
        // Not in progress: nothing to poll for.
        val before = fake.routingCalls.size
        shadowOf(android.os.Looper.getMainLooper()).idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before, fake.routingCalls.size)
    }

    @Test
    fun aFailedPollStillCountsAndTheNextOneRuns() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "ask-claude", "ok", "queued") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val before = fake.routingCalls.size
        val looper = shadowOf(android.os.Looper.getMainLooper())
        // The first poll hits a blip; the Working line stays and the next poll is still scheduled.
        fake.routing = org.plaudbridge.app.net.ApiClient.RoutingResult.Error("timeout")
        looper.idleFor(FileDetailActivity.ROUTING_POLL_DELAYS_MS[0], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 1, fake.routingCalls.size)
        assertEquals("Working", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_pill))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "ask-claude", "ok", "done", "Session started") + "]")}],"deliveries":[]}""")
        looper.idleFor(FileDetailActivity.ROUTING_POLL_DELAYS_MS[1], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 2, fake.routingCalls.size)
        assertEquals("Session started", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
    }

    @Test
    fun anOlderServerWithoutRoutingHidesTheSectionAndTheMenuAction() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = org.plaudbridge.app.net.ApiClient.RoutingResult.NotFound
        val activity = launchServer(fake)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
        assertTrue(menu.findItem(R.id.action_retranscribe).isVisible)
    }

    @Test
    fun waitingForARunWhosePollsAllFailStopsWithoutAVerdict() {
        val fake = FakeServerSource(serverRecording("transcribing"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        val activity = openTranscribingThenTranscriptArrives(fake)
        fake.routing = org.plaudbridge.app.net.ApiClient.RoutingResult.Error("offline")
        val before = fake.routingCalls.size
        val looper = shadowOf(android.os.Looper.getMainLooper())
        for (delay in FileDetailActivity.ROUTING_POLL_DELAYS_MS) {
            assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
            looper.idleFor(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        assertEquals(before + FileDetailActivity.ROUTING_POLL_DELAYS_MS.size, fake.routingCalls.size)
        // Budget spent on failures: nothing is known, so nothing is claimed.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before + FileDetailActivity.ROUTING_POLL_DELAYS_MS.size, fake.routingCalls.size)
    }

    /** Legacy phone copy (no server id) opened against a MockWebServer that answers the id lookup. */
    private fun launchLegacyAgainst(
        http: okhttp3.mockwebserver.MockWebServer, fake: FakeServerSource, cachedTranscript: String?,
        transcriptResponse: okhttp3.mockwebserver.MockResponse =
            okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(transcriptJson)
    ): FileDetailActivity {
        FileDetailActivity.serverSource = fake
        RecordingStore.serverBaseUrl = http.url("/").toString().trimEnd('/')
        RecordingStore.serverAuthToken = "tok"
        http.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("""{"id":"srv-9"}"""))
        http.enqueue(transcriptResponse)
        val file = storeFile(cachedTranscript, serverId = null, uploaded = true)
        val activity = launch(file.id)
        val looper = shadowOf(android.os.Looper.getMainLooper())
        // The lookup and transcript fetch run on Dispatchers.IO; give them a moment to land.
        val deadline = System.currentTimeMillis() + 10_000
        while (fake.routingCalls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            looper.idle()
        }
        return activity
    }

    @Test
    fun legacyPhoneCopyWithACachedTranscriptStillResolvesItsIdAndLoadsRouting() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Filed") + "]")}],"deliveries":[]}""")
        val http = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val activity = launchLegacyAgainst(http, fake, cachedTranscript = transcriptJson)
            assertEquals(listOf("srv-9"), fake.routingCalls)
            assertEquals("srv-9", RecordingStore.allFiles.single().serverId)
            assertEquals("Filed", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
            val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
                it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
                activity.applyMenuVisibility(it.menu)
            }.menu
            assertTrue(menu.findItem(R.id.action_run_automations).isVisible)
        } finally {
            http.shutdown()
        }
    }

    @Test
    fun legacyPhoneCopyOnAnOlderServerHidesRunAutomations() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        fake.routing = org.plaudbridge.app.net.ApiClient.RoutingResult.NotFound
        val http = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val activity = launchLegacyAgainst(http, fake, cachedTranscript = null)
            assertEquals(listOf("srv-9"), fake.routingCalls)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
            val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
                it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
                activity.applyMenuVisibility(it.menu)
            }.menu
            assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
            assertTrue(menu.findItem(R.id.action_retranscribe).isVisible)
        } finally {
            http.shutdown()
        }
    }

    @Test
    fun legacyPhoneCopyLoadsRoutingEvenWhenTheTranscriptAnswerIsNotReady() {
        // Cached transcript on screen, server says the re-transcription is pending (409): the id
        // is still resolved, and that alone is enough to read the automations.
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Filed") + "]")}],"deliveries":[]}""")
        val http = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val activity = launchLegacyAgainst(
                http, fake, cachedTranscript = transcriptJson,
                transcriptResponse = okhttp3.mockwebserver.MockResponse().setResponseCode(409)
            )
            assertEquals(listOf("srv-9"), fake.routingCalls)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
            assertEquals("Filed", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
            // The text on screen is the phone's cache; the server has no routable transcript now.
            val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
                it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
                activity.applyMenuVisibility(it.menu)
            }.menu
            assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
        } finally {
            http.shutdown()
        }
    }

    @Test
    fun runAutomationsIsNotSentTwiceWhileTheFirstCallIsStillRunning() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val controller = serverController(fake)
        var activity = controller.get()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.rerunGate = gate
        val looper = shadowOf(android.os.Looper.getMainLooper())
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        // The router is still working: the menu item is greyed out and a second tap is dropped.
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertEquals(false, menu.findItem(R.id.action_run_automations).isEnabled)
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(listOf("srv-9"), fake.reruns)
        // A rotation replaces the screen while the router is still working: the new instance
        // must know the call is on the wire and drop the tap too.
        controller.recreate()
        activity = controller.get()
        looper.idle()
        activity.applyMenuVisibility(menu)
        assertEquals(false, menu.findItem(R.id.action_run_automations).isEnabled)
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(listOf("srv-9"), fake.reruns)
        ShadowToast.reset()
        val before = fake.routingCalls.size
        gate.complete(Unit)
        looper.idle()
        // The outcome lands on the replacement screen: it gets the toast and the refreshes.
        assertEquals("Automations queued", ShadowToast.getTextOfLatestToast())
        looper.idleFor(FileDetailActivity.RERUN_REFRESH_DELAYS_MS[0], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 1, fake.routingCalls.size)
        activity.applyMenuVisibility(menu)
        assertTrue(menu.findItem(R.id.action_run_automations).isEnabled)
        // Free again: a new tap goes through.
        fake.rerunGate = null
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(listOf("srv-9", "srv-9"), fake.reruns)
    }

    @Test
    fun legacyDeliveriesWithoutARunStillShowWithRetry() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[],"deliveries":[{"id":"old-1","router_run_id":null,"route_name":"obsidian-inbox",
            "action_type":"webhook","status":"failed","attempts":2,"last_error":"webhook: connection refused",
            "created_at":"2026-09-01T10:00:00Z","result_status":null,"result_summary":null,"result_at":null}]}""")
        val activity = launchServer(fake)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        val block = automationsList(activity).getChildAt(0)
        assertEquals(null, block.findViewById<View>(R.id.automation_no_match))
        assertEquals("obsidian-inbox", textOf(block, R.id.automation_route_name))
        assertEquals("Failed", textOf(block, R.id.automation_delivery_pill))
        assertEquals("webhook: connection refused", textOf(block, R.id.automation_delivery_text))
        assertNotNull(block.findViewById<View>(R.id.automation_retry))
    }

    @Test
    fun runAutomationsHidesWhileTheServerIsReTranscribingEvenThoughTheOldTextIsStillShown() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        // Re-transcribe: the server now reports pending and has no transcript to route (409).
        fake.rec = serverRecording("pending")
        fake.transcript = org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending
        assertTrue(activity.onMenuAction(R.id.action_retranscribe))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
        assertTrue(menu.findItem(R.id.action_copy_transcript).isVisible)
        // Back to done: offered again.
        fake.rec = serverRecording()
        fake.transcript = org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson)
        activity.findViewById<View>(R.id.generateButton).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        activity.applyMenuVisibility(menu)
        assertTrue(menu.findItem(R.id.action_run_automations).isVisible)
    }

    @Test
    fun cachedTranscriptWithTheServerStillTranscribingIsNoVerdictAndNoAction() {
        // Phone copy with the old transcript cached; the server is re-transcribing and has no
        // runs yet. "No automations ran" would be premature, and Run automations would 409.
        val file = storeFile(transcriptJson, serverId = "srv-9")
        val fake = FakeServerSource(serverRecording("transcribing"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        configureServer(fake)
        val activity = launchBoth(file.id)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptText).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
        assertTrue(menu.findItem(R.id.action_copy_transcript).isVisible)
    }

    @Test
    fun aFailedRerunStillReReadsTheSectionInCaseTheServerRanIt() {
        // A lost response or timeout is ambiguous: the run may exist. Show it before another tap.
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.rerunResult = org.plaudbridge.app.net.ApiClient.ActionResult.Error("timeout")
        val activity = launchServer(fake)
        val before = fake.routingCalls.size
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", "queued") + "]")}],"deliveries":[]}""")
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        val looper = shadowOf(android.os.Looper.getMainLooper())
        looper.idle()
        assertNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        assertEquals(before + 1, fake.routingCalls.size)
        assertEquals("Working", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_pill))
        // A run newer than the one before the tap showed up: reconciled, the action is back.
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertTrue(menu.findItem(R.id.action_run_automations).isEnabled)
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(2, fake.reruns.size)
        // The lost request's run was found, so the second tap is a new intent with its own key.
        assertEquals(2, fake.rerunKeys.toSet().size)
        assertTrue(fake.rerunKeys.all { it.length in 8..128 && it.matches(Regex("[A-Za-z0-9_-]+")) })
    }

    @Test
    fun anAmbiguousRerunFailureWithUnchangedHistoryStaysGuardedUntilTheScheduledReadsAreDone() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(id = "run-1", deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Old") + "]")}],"deliveries":[]}""")
        fake.rerunResult = org.plaudbridge.app.net.ApiClient.ActionResult.Error("timeout")
        val activity = launchServer(fake)
        val looper = shadowOf(android.os.Looper.getMainLooper())
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        // The immediate re-read found only the old run: the server may still be working. Guarded.
        assertEquals(false, menu.findItem(R.id.action_run_automations).isEnabled)
        looper.idleFor(FileDetailActivity.RERUN_REFRESH_DELAYS_MS[0], java.util.concurrent.TimeUnit.MILLISECONDS)
        activity.applyMenuVisibility(menu)
        assertEquals(false, menu.findItem(R.id.action_run_automations).isEnabled)
        // The last scheduled read still shows nothing new: give the guard back.
        looper.idleFor(FileDetailActivity.RERUN_REFRESH_DELAYS_MS[1] - FileDetailActivity.RERUN_REFRESH_DELAYS_MS[0], java.util.concurrent.TimeUnit.MILLISECONDS)
        activity.applyMenuVisibility(menu)
        assertTrue(menu.findItem(R.id.action_run_automations).isEnabled)
        assertEquals(1, fake.reruns.size)
        // Nothing showed up, so the request may still land: the next tap replays the same key.
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(2, fake.reruns.size)
        assertEquals(fake.rerunKeys[0], fake.rerunKeys[1])
    }

    @Test
    fun leavingTheScreenDuringReconciliationReleasesTheGuardForTheNextScreen() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.rerunResult = org.plaudbridge.app.net.ApiClient.ActionResult.Error("timeout")
        val controller = serverController(fake)
        val activity = controller.get()
        val looper = shadowOf(android.os.Looper.getMainLooper())
        fake.routingGate = kotlinx.coroutines.CompletableDeferred()
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        controller.pause().stop().destroy()
        looper.idle()
        fake.routingGate = null
        val next = launchServer(fake)
        val menu = android.widget.PopupMenu(next, next.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            next.applyMenuVisibility(it.menu)
        }.menu
        assertTrue(menu.findItem(R.id.action_run_automations).isEnabled)
    }

    @Test
    fun aRetryThatFailsAmbiguouslyReReadsTheSection() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-7", "meetings", "failed", lastError = "502") + "]")}],"deliveries":[]}""")
        fake.retryResult = org.plaudbridge.app.net.ApiClient.RetryResult.Error("timeout")
        val activity = launchServer(fake)
        val before = fake.routingCalls.size
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-7", "meetings", "ok", "queued") + "]")}],"deliveries":[]}""")
        automationsList(activity).getChildAt(0).findViewById<View>(R.id.automation_retry).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        assertEquals(before + 1, fake.routingCalls.size)
        assertEquals("Working", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_pill))
    }

    @Test
    fun awaitBaselineComesFromTheReadAlreadyInFlight() {
        // The transcript arrives while the first routing read is still on the wire. That read
        // predates the arrival, so its run (from the previous transcription) is the baseline, not
        // the awaited result.
        val fake = FakeServerSource(serverRecording("transcribing"), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        fake.routing = runsOf("""{"runs":[${run(id = "run-1", deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Old") + "]")}],"deliveries":[]}""")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.routingGate = gate
        val activity = launchServer(fake)
        val looper = shadowOf(android.os.Looper.getMainLooper())
        // Check for transcript: recording done, transcript ready, while the first read is parked.
        fake.rec = serverRecording()
        fake.transcript = org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson)
        activity.findViewById<View>(R.id.generateButton).performClick()
        looper.idle()
        fake.routingGate = null
        gate.complete(Unit)
        looper.idle()
        // The old run is on screen, but the wait goes on: a poll is pending.
        assertEquals("Old", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
        fake.routing = runsOf("""{"runs":[
            ${run(id = "run-2", createdAt = "2026-09-07T07:00:00Z", deliveries = "[" + delivery("d-2", "meetings", "ok", "done", "New") + "]")},
            ${run(id = "run-1")}],"deliveries":[]}""")
        looper.idleFor(FileDetailActivity.ROUTING_POLL_DELAYS_MS[0], java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("New", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
    }

    @Test
    fun anAmbiguousRerunFailureKeepsTheGuardUntilTheSectionIsReRead() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.rerunResult = org.plaudbridge.app.net.ApiClient.ActionResult.Error("timeout")
        val activity = launchServer(fake)
        val looper = shadowOf(android.os.Looper.getMainLooper())
        // Hold the routing read that follows the failure so the reconciliation is still pending.
        val routingGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.routingGate = routingGate
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(1, fake.reruns.size)
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertEquals(false, menu.findItem(R.id.action_run_automations).isEnabled)
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(1, fake.reruns.size)
        // The parked read comes back with a run that was not there before the tap: the server
        // did run it after all. Shown, and the guard is released.
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", "queued") + "]")}],"deliveries":[]}""")
        routingGate.complete(Unit)
        looper.idle()
        assertEquals("Working", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_pill))
        activity.applyMenuVisibility(menu)
        assertTrue(menu.findItem(R.id.action_run_automations).isEnabled)
    }

    @Test
    fun aMarkdownActionThatReportedOkWithoutAResultIsDone() {
        // Pre-result-reporting rows: a markdown file write or a decision-only route finished
        // synchronously, so "ok" means done, not merely handed off (that wording is for webhooks).
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", actionType = "markdown") + "," +
            delivery("d-2", "meetings", "ok", actionType = "webhook") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val block = automationsList(activity).getChildAt(0) as android.view.ViewGroup
        val pills = (0 until block.childCount).map { block.getChildAt(it) }
            .mapNotNull { it.findViewById<android.widget.TextView>(R.id.automation_delivery_pill) }
            .map { it.text.toString() }
        assertEquals(listOf("Done", "Handed off"), pills)
    }

    @Test
    fun runAutomationsWaitsForTheHistoryToLoad() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.routingGate = gate
        val activity = launchServer(fake)
        val looper = shadowOf(android.os.Looper.getMainLooper())
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        // The first routing read is still on the wire: no baseline to reconcile against yet.
        assertTrue(menu.findItem(R.id.action_run_automations).isVisible)
        assertEquals(false, menu.findItem(R.id.action_run_automations).isEnabled)
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertTrue(fake.reruns.isEmpty())
        gate.complete(Unit)
        looper.idle()
        activity.applyMenuVisibility(menu)
        assertTrue(menu.findItem(R.id.action_run_automations).isEnabled)
    }

    @Test
    fun legacyLookupAnsweredAfterAServerSwitchIsDiscarded() {
        val fake = FakeServerSource(serverRecording(), org.plaudbridge.app.net.ApiClient.TranscriptResult.Pending)
        FileDetailActivity.serverSource = fake
        val http = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            RecordingStore.serverBaseUrl = http.url("/").toString().trimEnd('/')
            RecordingStore.serverAuthToken = "tok"
            // The old server answers the lookup only after a delay; the user switches servers meanwhile.
            http.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("""{"id":"foreign-1"}""")
                .setBodyDelay(600, java.util.concurrent.TimeUnit.MILLISECONDS))
            http.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(transcriptJson))
            val file = storeFile(null, serverId = null, uploaded = true)
            launch(file.id)
            RecordingStore.clearServerState()
            RecordingStore.serverBaseUrl = "https://other.example.com"
            RecordingStore.serverAuthToken = "tok2"
            val looper = shadowOf(android.os.Looper.getMainLooper())
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
                looper.idle()
            }
            // Nothing from the old server reached the index, and no routing was read for it.
            assertEquals(null, RecordingStore.allFiles.single().serverId)
            assertTrue(fake.routingCalls.isEmpty())
        } finally {
            http.shutdown()
        }
    }
}
