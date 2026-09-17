package cloud.adamrb.transom.ui.filedetail

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
import cloud.adamrb.transom.R
import cloud.adamrb.transom.models.RecordingFile
import cloud.adamrb.transom.storage.RecordingStore
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * FileDetailActivity: the Copy / Export pills appear only once a transcript is stored, Copy puts
 * the transcript on the clipboard, Export hands a markdown file (server-compatible layout) to the
 * share sheet, the transcript is a list of paragraph rows (speaker where it changes, a time chip
 * that seeks, bookmarks starred), the player is driven through the Playback seam, and the
 * unified open (phone copy + server copy) renders the cache first, refreshes from the server,
 * and routes Delete / Remove from phone through the shared action semantics.
 */
@RunWith(RobolectricTestRunner::class)
class FileDetailActivityTest {

    private lateinit var context: Context
    private lateinit var fakePlayback: FakePlayback

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
        cloud.adamrb.transom.export.FileProviderTestSupport.resetCache()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        File(context.filesDir, "recordings.json").delete()
        context.getSharedPreferences(TranscriptPositionStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        cloud.adamrb.transom.ui.recordings.RecordingsRepository.reset()
        FileDetailActivity.resetProcessStateForTests()
        resetSnackbarManager()
        // No session service on the JVM: every screen gets the same fake player, so a test can
        // see what the screen asked of it and feed it positions.
        fakePlayback = FakePlayback()
        FileDetailActivity.playbackFactory = cloud.adamrb.transom.playback.Playback.Factory { fakePlayback }
    }

    /** A [cloud.adamrb.transom.playback.Playback] that records calls and lets a test move the playhead. */
    class FakePlayback : cloud.adamrb.transom.playback.Playback {
        val items = mutableListOf<cloud.adamrb.transom.playback.Playback.Item>()
        private val listeners = mutableListOf<cloud.adamrb.transom.playback.Playback.Listener>()
        override var isConnected = true
        override var currentMediaId: String? = null
        override var isPlaying = false
        override var positionMs = 0L
        override var durationMs = -1L
        var currentSpeed = 1f
        override val speed: Float get() = currentSpeed
        override var hasError = false
        var released = false
        override fun setItem(item: cloud.adamrb.transom.playback.Playback.Item) {
            if (currentMediaId == item.mediaId && !hasError) return
            items += item
            currentMediaId = item.mediaId
            hasError = false
            positionMs = 0L
            isPlaying = false
        }
        override fun play() { isPlaying = true; notifyChanged() }
        override fun pause() { isPlaying = false; notifyChanged() }
        override fun seekTo(positionMs: Long) { this.positionMs = positionMs }
        override fun setSpeed(speed: Float) { currentSpeed = speed }
        override fun addListener(listener: cloud.adamrb.transom.playback.Playback.Listener) { listeners += listener }
        override fun removeListener(listener: cloud.adamrb.transom.playback.Playback.Listener) { listeners -= listener }
        override fun release() { released = true; listeners.clear() }
        /** The player moved on its own (playback progressed, another screen changed it). */
        fun notifyChanged() = listeners.toList().forEach { it.onPlaybackChanged() }
    }

    /** The paragraphs on screen as one string, the way the old single TextView laid them out. */
    private fun shownTranscript(activity: FileDetailActivity): String =
        activity.transcriptRowsForTests().joinToString("\n\n") { row ->
            (if (row.showSpeaker) row.speaker + "\n" else "") + (if (row.isBookmarked) FileDetailActivity.BOOKMARK_STAR else "") + row.text
        }

    private fun transcriptShown(activity: FileDetailActivity): Boolean = activity.transcriptRowsForTests().isNotEmpty()

    /**
     * SnackbarManager is a process singleton and Robolectric resets the main looper between
     * tests, so a snackbar left showing by an earlier test (its timeout message gone) would make
     * the next test's snackbar queue behind a dismissal that never fully happens. Start clean.
     */
    private fun resetSnackbarManager() {
        val cls = Class.forName("com.google.android.material.snackbar.SnackbarManager")
        val instance = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
        for (name in listOf("currentSnackbar", "nextSnackbar")) {
            cls.getDeclaredField(name).apply { isAccessible = true }.set(instance, null)
        }
    }

    /**
     * The text of the snackbar on screen, or null when none is showing. Shown through a couple
     * of handler hops, so the looper is idled in short steps (well inside the snackbar's own
     * 1.5 s life) until it is up.
     */
    private fun latestSnackbarText(activity: FileDetailActivity): String? {
        val looper = shadowOf(android.os.Looper.getMainLooper())
        repeat(20) {
            looper.idle()
            activity.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)?.let { return it.text.toString() }
            looper.idleFor(50, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        return null
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
    fun copyPutsTranscriptOnClipboardAndConfirms() {
        val file = storeFile(transcriptJson)
        val activity = launch(file.id)
        activity.findViewById<View>(R.id.copyTranscriptButton).performClick()

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals("Transcript", clip!!.description.label)
        // The server's speaker turns as paragraphs: no timestamps, no per-segment blocks.
        assertEquals("Speaker 1: Hello there.\n\nSpeaker 2: Hi.", clip.getItemAt(0).text.toString())
        // Android 13+ shows its own clipboard chip; before that the screen confirms itself.
        if (android.os.Build.VERSION.SDK_INT < 33) assertEquals("Transcript copied", latestSnackbarText(activity))
        else assertEquals(null, latestSnackbarText(activity))
        // A document without `paragraphs` is grouped locally from its segments: speaker label
        // above each turn, no clock times in the text (the time chips are separate views).
        val shown = shownTranscript(activity)
        assertEquals("Speaker 00\nHello there.\n\nSpeaker 01\nHi.", shown)
        assertNoClockTimes(shown)
    }

    @Test
    fun copySummaryPutsTheSummaryOnTheClipboard() {
        val file = storeFile(transcriptJson)
        val activity = launch(file.id)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.summaryHeaderRow).visibility)
        activity.findViewById<View>(R.id.copySummaryButton).performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("A greeting.", clipboard.primaryClip!!.getItemAt(0).text.toString())
        if (android.os.Build.VERSION.SDK_INT < 33) assertEquals("Summary copied", latestSnackbarText(activity))
    }

    private fun assertNoClockTimes(text: String) {
        assertTrue("clock time in transcript: $text", !Regex("\\d:\\d\\d").containsMatchIn(text))
    }

    /** A server document with the reader layout: three turns, the middle one holding a bookmark. */
    private val transcriptWithParagraphs = """{"text":"Speaker 1: Hello there. How are you?\nSpeaker 2: Fine. And you?\nSpeaker 1: Good.",
        "summary":"A greeting.",
        "segments":[{"speaker":"Speaker 1","start":0.0,"end":2.0,"text":"Hello there."},
                    {"speaker":"Speaker 1","start":2.0,"end":4.0,"text":"How are you?"},
                    {"speaker":"Speaker 2","start":4.0,"end":5.0,"text":"Fine."},
                    {"speaker":"Speaker 2","start":5.0,"end":7.0,"text":"And you?"},
                    {"speaker":"Speaker 1","start":7.0,"end":9.0,"text":"Good."}],
        "marks":[4.5],
        "highlights":[{"at":4.5,"start":4.0,"end":7.0,"speakers":["Speaker 2"],"text":"Fine. And you?"}],
        "paragraphs":[{"speaker":"Speaker 1","text":"Hello there. How are you?","start":0.0,"end":4.0,"bookmarks":[]},
                      {"speaker":"Speaker 2","text":"Fine. And you?","start":4.0,"end":7.0,"bookmarks":[0]},
                      {"speaker":"Speaker 1","text":"Good.","start":7.0,"end":9.0,"bookmarks":[]}]}"""

    @Test
    fun transcriptRendersServerParagraphsAsRowsWithSpeakerChangesAndTimeChips() {
        val file = storeFile(transcriptWithParagraphs)
        val activity = launch(file.id)
        assertTrue(transcriptShown(activity))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptSectionHeader).visibility)
        val shown = shownTranscript(activity)
        // Speaker label whenever the speaker changes; the bookmarked paragraph opens with the
        // Highlights rows' star; no "Speaker N \u00b7 HH:MM:SS" blocks anywhere in the text.
        assertEquals(
            "Speaker 1\nHello there. How are you?\n\nSpeaker 2\n\u2605 Fine. And you?\n\nSpeaker 1\nGood.",
            shown
        )
        assertNoClockTimes(shown)
        assertTrue(!shown.contains("\u00b7"))

        val rows = activity.transcriptRowsForTests()
        assertEquals(listOf("0:00", "0:04", "0:07"), rows.map { it.timeLabel })
        // Exactly the bookmarked paragraph carries the accent bar and the star in the accent colour.
        val bookmarked = activity.bindParagraphForTests(1)
        assertEquals(View.VISIBLE, bookmarked.findViewById<View>(R.id.bookmarkBar).visibility)
        val text = bookmarked.findViewById<android.widget.TextView>(R.id.paragraphText)
        assertEquals("\u2605 Fine. And you?", text.text.toString())
        assertTrue(text.isTextSelectable)
        val accent = androidx.core.content.ContextCompat.getColor(activity, R.color.highlight_accent)
        val spanned = text.text as android.text.Spanned
        val star = spanned.getSpans(0, spanned.length, android.text.style.ForegroundColorSpan::class.java).single()
        assertEquals(accent, star.foregroundColor)
        assertEquals(0 until 2, spanned.getSpanStart(star) until spanned.getSpanEnd(star))
        val plain = activity.bindParagraphForTests(0)
        assertEquals(View.GONE, plain.findViewById<View>(R.id.bookmarkBar).visibility)
        assertEquals("Speaker 1", plain.findViewById<android.widget.TextView>(R.id.speakerLabel).text.toString())
        assertEquals("0:00", plain.findViewById<android.widget.TextView>(R.id.timeChip).text.toString())
        // Not a link: the body text has no click listener of its own (the time chip seeks).
        assertEquals(false, text.hasOnClickListeners())
    }

    @Test
    fun consecutiveParagraphsBySameSpeakerShareOneLabel() {
        val json = """{"paragraphs":[
            {"speaker":"Speaker 1","text":"First stretch.","start":0.0,"end":30.0,"bookmarks":[]},
            {"speaker":"Speaker 1","text":"Second stretch after a pause.","start":33.0,"end":60.0,"bookmarks":[]},
            {"speaker":"Speaker 2","text":"Reply.","start":60.0,"end":61.0,"bookmarks":[]}]}"""
        val file = storeFile(json)
        val activity = launch(file.id)
        assertEquals("Speaker 1\nFirst stretch.\n\nSecond stretch after a pause.\n\nSpeaker 2\nReply.", shownTranscript(activity))
        // The second row still shows its time chip, just no label.
        val second = activity.bindParagraphForTests(1)
        assertEquals(View.GONE, second.findViewById<View>(R.id.speakerLabel).visibility)
        assertEquals("0:33", second.findViewById<android.widget.TextView>(R.id.timeChip).text.toString())
    }

    @Test
    fun undiarizedTranscriptShowsNoSpeakerLabels() {
        val json = """{"paragraphs":[
            {"speaker":null,"text":"Just me talking.","start":0.0,"end":3.0,"bookmarks":[]},
            {"speaker":null,"text":"Still me.","start":6.0,"end":9.0,"bookmarks":[]}]}"""
        val file = storeFile(json)
        val shown = shownTranscript(launch(file.id))
        assertEquals("Just me talking.\n\nStill me.", shown)
        assertNoClockTimes(shown)

        // The same for a legacy document whose segments never name a speaker.
        RecordingStore.clearAll()
        val legacy = storeFile("""{"segments":[{"start":0.0,"text":"Just me"},{"start":1.0,"text":"talking."}]}""")
        assertEquals("Just me talking.", shownTranscript(launch(legacy.id)))
    }

    @Test
    fun legacyDocumentWithoutParagraphsIsGroupedFromSegmentsWithBookmarks() {
        // Older cached documents (no `paragraphs`) never fall back to timestamped output: the
        // segments are grouped per speaker turn and the highlights attach to the turn they fall in.
        val legacy = """{"segments":[{"speaker":"Speaker 1","start":0.0,"end":2.0,"text":"Hello"},
            {"speaker":"Speaker 1","start":2.0,"end":4.0,"text":"there."},
            {"speaker":"Speaker 2","start":4.0,"end":7.0,"text":"Hi."}],
            "highlights":[{"at":5.0,"start":4.0,"end":7.0,"speakers":["Speaker 2"],"text":"Hi."}]}"""
        val file = storeFile(legacy)
        val activity = launch(file.id)
        assertEquals("Speaker 1\nHello there.\n\nSpeaker 2\n\u2605 Hi.", shownTranscript(activity))
        assertEquals(listOf(false, true), activity.transcriptRowsForTests().map { it.isBookmarked })
        // Copy of such a document is the same paragraphs without the star.
        activity.findViewById<View>(R.id.copyTranscriptButton).performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("Speaker 1: Hello there.\n\nSpeaker 2: Hi.", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun aFlatTextDocumentWithoutSegmentsShowsItsParagraphsWithoutTimeChips() {
        val file = storeFile("""{"text":"Speaker 1: Hello there.\nSpeaker 2: Hi."}""")
        val activity = launch(file.id)
        assertEquals("Speaker 1: Hello there.\n\nSpeaker 2: Hi.", shownTranscript(activity))
        val row = activity.bindParagraphForTests(0)
        assertEquals(View.GONE, row.findViewById<View>(R.id.timeChip).visibility)
        assertEquals(View.GONE, row.findViewById<View>(R.id.metaRow).visibility)
        // Nothing to jump to without times or speakers.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.jumpToButton).visibility)
    }

    @Test
    fun copyAndExportUseTheServerParagraphLayout() {
        val file = storeFile(transcriptWithParagraphs)
        val activity = launch(file.id)
        activity.findViewById<View>(R.id.copyTranscriptButton).performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // paragraphs_plain: "Speaker: text" paragraphs, no stars, no times.
        assertEquals(
            "Speaker 1: Hello there. How are you?\n\nSpeaker 2: Fine. And you?\n\nSpeaker 1: Good.",
            clipboard.primaryClip!!.getItemAt(0).text.toString()
        )
        activity.findViewById<View>(R.id.exportMarkdownButton).performClick()
        val exported = File(context.cacheDir, "exports/Budget -Q3- call.md").readText()
        // paragraphs_markdown: bold label, "\u2605 " on the bookmarked paragraph, one blank line between.
        val expectedTail = """
            |## Highlights
            |
            |- **0:04** Fine. And you?
            |
            |## Transcript
            |
            |**Speaker 1:** Hello there. How are you?
            |
            |**Speaker 2:** ${FileDetailActivity.BOOKMARK_STAR}Fine. And you?
            |
            |**Speaker 1:** Good.
            |""".trimMargin()
        assertTrue(exported, exported.endsWith(expectedTail))
    }

    @Test
    fun tappingATimeChipSeeksThePlayerToTheParagraphsStart() {
        // A server copy gives the page a streaming player, so the seek has somewhere to land.
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithParagraphs))
        configureServer(fake)
        val file = storeFile(transcriptWithParagraphs, serverId = "srv-9")
        val activity = launchBoth(file.id)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.audioPlayer).visibility)
        // The player was handed the server stream (with the recording's title for the notification).
        assertEquals("https://bridge.example.com/api/v1/recordings/srv-9/audio", fakePlayback.currentMediaId)
        assertEquals("Server side \"Q3\" call", fakePlayback.items.single().title)
        assertEquals("srv-9", fakePlayback.items.single().serverId)
        val clock = activity.findViewById<android.widget.TextView>(R.id.currentTimeLabel)
        val total = activity.findViewById<android.widget.TextView>(R.id.totalTimeLabel)
        // The length is known before the player is ready: from the recording itself.
        assertEquals("0:00", clock.text.toString())
        assertEquals("1:01", total.text.toString())
        val slider = activity.findViewById<com.google.android.material.slider.Slider>(R.id.progressSlider)
        assertEquals(61_000f, slider.valueTo)

        activity.bindParagraphForTests(2).findViewById<View>(R.id.timeChip).performClick()
        assertEquals(7_000L, fakePlayback.positionMs)
        assertEquals("0:07", clock.text.toString())
        assertEquals(7_000f, slider.value)
        activity.bindParagraphForTests(1).findViewById<View>(R.id.timeChip).performClick()
        assertEquals(4_000L, fakePlayback.positionMs)
        assertEquals("0:04", clock.text.toString())
        // The seek makes that paragraph the one being played, even before play is pressed.
        assertEquals(1, activity.nowPlayingIndexForTests())
        assertEquals(1, activity.bindParagraphForTests(1).findViewById<View>(R.id.paragraphRoot).let {
            if ((it.background as android.graphics.drawable.ColorDrawable).color == android.graphics.Color.TRANSPARENT) 0 else 1
        })
    }

    @Test
    fun skipButtonsMove15BackAnd30Forward() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithParagraphs))
        val activity = launchServer(fake)
        fakePlayback.positionMs = 40_000L
        activity.findViewById<View>(R.id.rewindButton).performClick()
        assertEquals(25_000L, fakePlayback.positionMs)
        activity.findViewById<View>(R.id.forwardButton).performClick()
        assertEquals(55_000L, fakePlayback.positionMs)
        // Never past the end, never before the start.
        activity.findViewById<View>(R.id.forwardButton).performClick()
        assertEquals(61_000L, fakePlayback.positionMs)
        fakePlayback.positionMs = 5_000L
        activity.findViewById<View>(R.id.rewindButton).performClick()
        assertEquals(0L, fakePlayback.positionMs)
    }

    @Test
    fun playPauseAndSpeedChipsDriveThePlayer() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithParagraphs))
        val activity = launchServer(fake)
        val play = activity.findViewById<android.widget.ImageButton>(R.id.playPauseButton)
        play.performClick()
        assertTrue(fakePlayback.isPlaying)
        play.performClick()
        assertEquals(false, fakePlayback.isPlaying)
        activity.findViewById<com.google.android.material.chip.Chip>(R.id.speed1_5x).performClick()
        assertEquals(1.5f, fakePlayback.speed)
        // The chosen speed is remembered and applied to the next recording too.
        val next = launchServer(FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson)))
        assertTrue(next.findViewById<com.google.android.material.chip.Chip>(R.id.speed1_5x).isChecked)
        next.findViewById<View>(R.id.playPauseButton).performClick()
        assertEquals(1.5f, fakePlayback.speed)
    }

    @Test
    fun theParagraphBeingPlayedIsTintedAndFollowedUntilTheReaderScrolls() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithParagraphs))
        val activity = launchServer(fake)
        val chip = activity.findViewById<View>(R.id.returnToPlayback)
        assertEquals(-1, activity.nowPlayingIndexForTests())
        activity.findViewById<View>(R.id.playPauseButton).performClick()
        val looper = shadowOf(android.os.Looper.getMainLooper())
        looper.idle()
        assertEquals(0, activity.nowPlayingIndexForTests())
        assertEquals(View.GONE, chip.visibility)
        // Playback moves into the second paragraph: the tint follows on the next tick.
        fakePlayback.positionMs = 5_000L
        looper.idleFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(1, activity.nowPlayingIndexForTests())
        assertTrue(activity.followPlaybackForTests())
        // The reader scrolls away: following stops and the way back is offered.
        activity.simulateUserScrollForTests()
        assertEquals(false, activity.followPlaybackForTests())
        assertEquals(View.VISIBLE, chip.visibility)
        chip.performClick()
        assertTrue(activity.followPlaybackForTests())
        assertEquals(View.GONE, chip.visibility)
        // Pausing hides the chip even when not following.
        activity.simulateUserScrollForTests()
        assertEquals(View.VISIBLE, chip.visibility)
        activity.findViewById<View>(R.id.playPauseButton).performClick()
        looper.idle()
        assertEquals(View.GONE, chip.visibility)
        // The playhead stays marked while paused.
        assertEquals(1, activity.nowPlayingIndexForTests())
    }

    @Test
    fun leavingTheScreenDoesNotStopPlayback() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithParagraphs))
        val controller = serverController(fake)
        controller.get().findViewById<View>(R.id.playPauseButton).performClick()
        assertTrue(fakePlayback.isPlaying)
        controller.pause().stop().destroy()
        // Still playing in the service; only this screen's handle on it was released.
        assertTrue(fakePlayback.isPlaying)
        assertTrue(fakePlayback.released)
    }

    @Test
    fun aRecordingLeftPlayingIsNotInterruptedByOpeningAnotherOne() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithParagraphs))
        fakePlayback.currentMediaId = "https://bridge.example.com/api/v1/recordings/other/audio"
        fakePlayback.isPlaying = true
        fakePlayback.positionMs = 9_000L
        val activity = launchServer(fake)
        // The other recording keeps playing; this screen shows its own recording at rest.
        assertEquals("https://bridge.example.com/api/v1/recordings/other/audio", fakePlayback.currentMediaId)
        assertTrue(fakePlayback.items.isEmpty())
        assertEquals("0:00", activity.findViewById<android.widget.TextView>(R.id.currentTimeLabel).text.toString())
        assertEquals(-1, activity.nowPlayingIndexForTests())
        // Play here takes the player over.
        activity.findViewById<View>(R.id.playPauseButton).performClick()
        assertEquals("https://bridge.example.com/api/v1/recordings/srv-9/audio", fakePlayback.currentMediaId)
        assertTrue(fakePlayback.isPlaying)
    }

    @Test
    fun aPlayerErrorHidesTheCard() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithParagraphs))
        val activity = launchServer(fake)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.audioPlayer).visibility)
        fakePlayback.hasError = true
        fakePlayback.notifyChanged()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.audioPlayer).visibility)
    }

    @Test
    fun tappingAHighlightRevealsAndFlashesItsParagraph() {
        val file = storeFile(transcriptWithParagraphs)
        val activity = launch(file.id)
        assertEquals(-1, activity.flashIndexForTests())
        activity.findViewById<android.widget.LinearLayout>(R.id.highlightsList).getChildAt(0).performClick()
        // The bookmarked paragraph (the second one) is tinted for a moment, then plain again.
        assertEquals(1, activity.flashIndexForTests())
        val tinted = activity.bindParagraphForTests(1).findViewById<View>(R.id.paragraphRoot).background as android.graphics.drawable.ColorDrawable
        assertEquals(androidx.core.content.ContextCompat.getColor(activity, R.color.highlight_flash), tinted.color)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(FileDetailActivity.PARAGRAPH_FLASH_MS + 100))
        assertEquals(-1, activity.flashIndexForTests())
        // Re-binding (the onResume reload) starts clean and a second tap flashes again.
        activity.findViewById<android.widget.LinearLayout>(R.id.highlightsList).getChildAt(0).performClick()
        assertEquals(1, activity.flashIndexForTests())
    }

    @Test
    fun jumpToListsBookmarksSpeakerChangesAndTimeMarks() {
        val fake = FakeServerSource(serverRecording().copy(durationS = 1500.0), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithParagraphs))
        val activity = launchServer(fake)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.jumpToButton).visibility)
        val items = activity.jumpToItems()
        assertEquals(
            listOf(JumpToItem.Kind.BOOKMARK, JumpToItem.Kind.SPEAKER, JumpToItem.Kind.SPEAKER, JumpToItem.Kind.TIME_MARK, JumpToItem.Kind.TIME_MARK),
            items.map { it.kind }
        )
        assertEquals("Fine. And you?", items[0].label)
        assertEquals(1, items[0].paragraphIndex)
        assertEquals(listOf("Speaker 2", "Speaker 1"), items.filter { it.kind == JumpToItem.Kind.SPEAKER }.map { it.label })
        assertEquals(listOf("10:00", "20:00"), items.filter { it.kind == JumpToItem.Kind.TIME_MARK }.map { it.timeLabel })
        // Choosing an entry seeks the player and reveals the paragraph.
        activity.onJumpTo(items[1])
        assertEquals(4_000L, fakePlayback.positionMs)
        assertEquals(1, activity.flashIndexForTests())
    }

    @Test
    fun theReadingPositionIsRememberedPerRecording() {
        FileDetailActivity.transcriptParseDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val store = TranscriptPositionStore(context)
        // A transcript far longer than the window, so there is somewhere to scroll to.
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(longTranscriptJson(300)))
        val controller = serverController(fake)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // Nothing scrolled: leaving remembers nothing.
        controller.get().savePosition()
        assertEquals(null, store.load("server:srv-9"))
        // A remembered place is restored into the list on the next open (and the header collapsed).
        store.save("server:srv-9", TranscriptPositionStore.Position(120, 0))
        val next = launchServer(fake)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val list = next.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.transcriptList)
        val lm = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        assertEquals(TranscriptAdapter.HEADER_COUNT + 120, lm.findFirstVisibleItemPosition())
        // Leaving from there writes that place back.
        next.savePosition()
        assertEquals(TranscriptPositionStore.Position(120, 0), store.load("server:srv-9"))
        // Back at the top on a later visit: the memory is cleared.
        lm.scrollToPositionWithOffset(0, 0)
        list.measure(
            View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(list.height, View.MeasureSpec.EXACTLY)
        )
        list.layout(list.left, list.top, list.right, list.bottom)
        next.savePosition()
        assertEquals(null, store.load("server:srv-9"))
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
            |source: transom
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
        var rec: cloud.adamrb.transom.models.ServerRecording,
        var transcript: cloud.adamrb.transom.net.ApiClient.TranscriptResult
    ) : FileDetailActivity.ServerDetailSource {
        val deleted = mutableListOf<String>()
        /** What GET routing answers; Ok(empty) by default so the section stays quiet in older tests. */
        var routing: cloud.adamrb.transom.net.ApiClient.RoutingResult =
            cloud.adamrb.transom.net.ApiClient.RoutingResult.Ok(emptyList())
        val routingCalls = mutableListOf<String>()
        val reruns = mutableListOf<String>()
        val retries = mutableListOf<String>()
        /** How many of the next recording reads answer with a transient error. */
        var recordingFailures = 0
        /** When set, recording reads park on it so a test can hold one "on the wire". */
        var recordingGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun recording(id: String): cloud.adamrb.transom.net.ApiClient.RecordingResult {
            recordingCalls += id
            recordingGate?.await()
            if (recordingFailures > 0) {
                recordingFailures--
                return cloud.adamrb.transom.net.ApiClient.RecordingResult.Error("timeout")
            }
            return if (id == rec.id) cloud.adamrb.transom.net.ApiClient.RecordingResult.Ok(rec)
            else cloud.adamrb.transom.net.ApiClient.RecordingResult.NotFound
        }
        /** When set, the transcript read parks on it so a test can look at the screen in between. */
        var transcriptGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val transcriptCalls = mutableListOf<String>()
        override suspend fun transcript(id: String): cloud.adamrb.transom.net.ApiClient.TranscriptResult {
            transcriptCalls += id
            transcriptGate?.await()
            return transcript
        }
        /** When set, routing parks on it so a test can hold a read "on the wire". */
        var routingGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun routing(id: String): cloud.adamrb.transom.net.ApiClient.RoutingResult {
            routingCalls += id
            routingGate?.await()
            return routing
        }
        /** When set, rerunRouting parks on it so a test can hold the call "on the wire". */
        var rerunGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var rerunResult: cloud.adamrb.transom.net.ApiClient.ActionResult = cloud.adamrb.transom.net.ApiClient.ActionResult.Ok
        val rerunKeys = mutableListOf<String>()
        val rerunInstructions = mutableListOf<String?>()
        override suspend fun rerunRouting(id: String, idempotencyKey: String, instructions: String?): cloud.adamrb.transom.net.ApiClient.ActionResult {
            reruns += id
            rerunKeys += idempotencyKey
            rerunInstructions += instructions
            rerunGate?.await()
            return rerunResult
        }
        /** Recording reads, so a test can count the transcription polls. */
        val recordingCalls = mutableListOf<String>()
        var retryResult: cloud.adamrb.transom.net.ApiClient.RetryResult = cloud.adamrb.transom.net.ApiClient.RetryResult.Ok
        override suspend fun retryDelivery(deliveryId: String): cloud.adamrb.transom.net.ApiClient.RetryResult {
            retries += deliveryId
            return retryResult
        }
        val renames = mutableListOf<Pair<String, String>>()
        override suspend fun rename(id: String, title: String): cloud.adamrb.transom.net.ApiClient.RecordingResult {
            renames += id to title
            return cloud.adamrb.transom.net.ApiClient.RecordingResult.Ok(rec.copy(title = title))
        }
        /** Speaker renames asked for; the answer is [speakerRenameResult], or the transcript with the label swapped. */
        val speakerRenames = mutableListOf<Map<String, String>>()
        var speakerRenameResult: cloud.adamrb.transom.net.ApiClient.TranscriptResult? = null
        override suspend fun renameSpeakers(id: String, renames: Map<String, String>): cloud.adamrb.transom.net.ApiClient.TranscriptResult {
            speakerRenames += renames
            speakerRenameResult?.let { return it }
            val ready = transcript as? cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready
                ?: return cloud.adamrb.transom.net.ApiClient.TranscriptResult.NotFound
            var json = ready.rawJson
            renames.forEach { (old, new) -> json = json.replace("\"$old\"", "\"$new\"") }
            return cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(json)
        }
        override suspend fun retranscribe(id: String) = cloud.adamrb.transom.net.ApiClient.ActionResult.Ok
        override suspend fun delete(id: String): cloud.adamrb.transom.net.ApiClient.ActionResult {
            deleted += id
            return cloud.adamrb.transom.net.ApiClient.ActionResult.Ok
        }
    }

    /** Uploaded a week ago by default: an old recording, routed long ago, nothing to wait for. */
    private fun serverRecording(
        status: String = "done", error: String? = null, uploadedAt: String = "2026-09-01T05:30:00Z",
        /** Extra JSON members (e.g. `"stage":"diarizing","progress":0.5`), appended verbatim. */
        extra: String = ""
    ) = cloud.adamrb.transom.models.ServerRecording.fromJson(
        org.json.JSONObject(
            """{"id":"srv-9","device_sn":"SN-A","session_id":9,"filename":"9.mp3","size_bytes":1,
            "duration_s":61.0,"started_at":"2026-09-07T05:27:31Z","uploaded_at":"$uploadedAt",
            "source":"transom-android","status":"$status","title":"Server side \"Q3\" call",
            "summary":"From the list.","marks":[6.0],"has_transcript":true,"text_preview":null,
            "error":${if (error == null) "null" else "\"$error\""}${if (extra.isEmpty()) "" else ",$extra"}}"""
        )
    )

    /** A finished recording the server heard nothing in: no title, no summary, flagged no_speech. */
    private fun noSpeechRecording(uploadedAt: String = "2026-09-01T05:30:00Z") = cloud.adamrb.transom.models.ServerRecording.fromJson(
        org.json.JSONObject(
            """{"id":"srv-9","device_sn":"SN-A","session_id":9,"filename":"9.mp3","size_bytes":1,
            "duration_s":61.0,"started_at":"2026-09-07T05:27:31Z","uploaded_at":"$uploadedAt",
            "source":"transom-android","status":"done","title":null,"summary":null,"marks":[],
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

    /** A two-hour-style document: hundreds of paragraphs, well past the off-main-thread threshold. */
    private fun longTranscriptJson(paragraphCount: Int): String {
        val sentence = "This is one more sentence of the very long recording that keeps going on and on."
        val segs = StringBuilder(); val paras = StringBuilder()
        for (i in 0 until paragraphCount) {
            val speaker = if (i % 2 == 0) "Speaker 1" else "Speaker 2"
            val text = (0 until 6).joinToString(" ") { sentence }
            if (i > 0) { segs.append(','); paras.append(',') }
            segs.append("""{"speaker":"$speaker","start":${i * 10}.0,"end":${i * 10 + 10}.0,"text":"$text"}""")
            paras.append("""{"speaker":"$speaker","text":"$text","start":${i * 10}.0,"end":${i * 10 + 10}.0,"bookmarks":${if (i == 200) "[0]" else "[]"}}""")
        }
        return """{"text":"long","summary":"Long.","segments":[$segs],"marks":[2005.0],
            "highlights":[{"at":2005.0,"start":2000.0,"end":2010.0,"speakers":["Speaker 1"],"text":"$sentence"}],
            "paragraphs":[$paras]}"""
    }

    @Test
    fun aLongTranscriptIsParsedOffTheMainThreadAndStillLandsWithItsParagraphs() {
        // Inline dispatcher so the background hop is deterministic here; the production default is
        // Dispatchers.Default, which is the whole point (the main thread never parses a megabyte).
        FileDetailActivity.transcriptParseDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val json = longTranscriptJson(300)
        assertTrue(json.length > FileDetailActivity.PARSE_OFF_MAIN_CHARS)
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(json))
        val activity = launchServer(fake)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val rows = activity.transcriptRowsForTests()
        assertEquals(300, rows.size)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptLoading).visibility)
        assertNoClockTimes(shownTranscript(activity))
        assertTrue(shownTranscript(activity).startsWith("Speaker 1\nThis is one more sentence"))
        // Every paragraph has its own time chip; the bookmarked one is found by its bookmark and
        // a highlight tap reveals it.
        assertEquals("33:20", rows[200].timeLabel)
        assertTrue(rows[200].isBookmarked)
        activity.findViewById<android.widget.LinearLayout>(R.id.highlightsList).getChildAt(0).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(200, activity.flashIndexForTests())
        // Short transcripts skip the hop and are on screen synchronously.
        val short = launchServer(FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithParagraphs)))
        assertTrue(transcriptShown(short))
    }

    @Test
    fun theLoadingBarShowsWhileALongTranscriptIsBeingRead() {
        // A dispatcher that holds the parse until told: the bar is up meanwhile, the empty state is not.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val held = kotlinx.coroutines.Dispatchers.Unconfined
        FileDetailActivity.transcriptParseDispatcher = held
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(longTranscriptJson(300)))
        fake.transcriptGate = kotlinx.coroutines.CompletableDeferred()
        val activity = launchServer(fake)
        // The recording is known and done, its transcript still on the wire: the bar, not the empty state.
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptLoading).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyState).visibility)
        fake.transcriptGate!!.complete(Unit)
        gate.complete(Unit)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptLoading).visibility)
        assertEquals(300, activity.transcriptRowsForTests().size)
    }

    // MARK: - Speaker rename

    @Test
    fun tappingASpeakerLabelRenamesTheSpeakerOnTheServerAndReRenders() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithParagraphs))
        val activity = launchServer(fake)
        val label = activity.bindParagraphForTests(1).findViewById<android.widget.TextView>(R.id.speakerLabel)
        assertTrue(label.isClickable)
        label.performClick()
        val dialog = latestDialog()
        val field = dialog.findViewById<android.widget.EditText>(R.id.textField)!!
        val layout = dialog.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.textFieldLayout)!!
        assertEquals("Speaker 2", field.text.toString())
        // A blank name is refused in place; a name another speaker has warns about the merge.
        field.setText("   ")
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals("Enter a name", layout.error.toString())
        field.setText("Speaker 1")
        assertEquals("Merges with Speaker 1", layout.helperText.toString())
        field.setText("Morgan")
        assertEquals(null, layout.helperText)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf(mapOf("Speaker 2" to "Morgan")), fake.speakerRenames)
        // The screen re-renders from the document the server sent back.
        assertEquals(
            "Speaker 1\nHello there. How are you?\n\nMorgan\n★ Fine. And you?\n\nSpeaker 1\nGood.",
            shownTranscript(activity)
        )
        assertEquals("Speaker renamed", latestSnackbarText(activity))
    }

    @Test
    fun speakerRenameOnAnOlderServerSaysItNeedsAnUpdate() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithParagraphs))
        fake.speakerRenameResult = cloud.adamrb.transom.net.ApiClient.TranscriptResult.NotFound
        val activity = launchServer(fake)
        activity.bindParagraphForTests(0).findViewById<View>(R.id.speakerLabel).performClick()
        latestDialog().apply {
            findViewById<android.widget.EditText>(R.id.textField)!!.setText("Alex")
            getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Your server needs an update for this", latestSnackbarText(activity))
        // Nothing changed on screen.
        assertTrue(shownTranscript(activity).startsWith("Speaker 1\n"))
        // A refusal with the server's own words shows those words, never the status code.
        fake.speakerRenameResult = cloud.adamrb.transom.net.ApiClient.TranscriptResult.Error("HTTP 422", "New name must not be blank")
        activity.bindParagraphForTests(0).findViewById<View>(R.id.speakerLabel).performClick()
        latestDialog().apply {
            findViewById<android.widget.EditText>(R.id.textField)!!.setText("Alex")
            getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("New name must not be blank", latestSnackbarText(activity))
    }

    @Test
    fun speakerLabelsAreNotRenamableWithoutAServerTranscript() {
        // A phone copy alone (offline cache): nothing to rename on.
        val file = storeFile(transcriptWithParagraphs, serverId = null, uploaded = false)
        val activity = launch(file.id)
        val label = activity.bindParagraphForTests(0).findViewById<android.widget.TextView>(R.id.speakerLabel)
        assertEquals(View.VISIBLE, label.visibility)
        assertEquals(false, label.isClickable)
        assertEquals("Speaker 1", label.contentDescription.toString())
    }

    @Test
    fun renameDialogUsesATextFieldAndRefusesABlankTitle() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        assertTrue(activity.onMenuAction(R.id.action_rename))
        val dialog = latestDialog()
        val field = dialog.findViewById<android.widget.EditText>(R.id.textField)!!
        val layout = dialog.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.textFieldLayout)!!
        assertEquals("Server side \"Q3\" call", field.text.toString())
        field.setText("")
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals("Enter a title", layout.error.toString())
        assertTrue(fake.renames.isEmpty())
        field.setText("  Budget call  ")
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf("srv-9" to "Budget call"), fake.renames)
        assertEquals(false, dialog.isShowing)
    }

    @Test
    fun serverModeShowsFetchedTitleAndTranscript() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithHighlights))
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
        // The title also sits in the toolbar, for when the header scrolls away; ⋮ is offered.
        assertEquals("Server side \"Q3\" call", activity.findViewById<android.widget.TextView>(R.id.toolbarTitle).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.moreButton).visibility)
    }

    @Test
    fun theSummaryDropsItsHighlightsSectionWhenTheHighlightsRowsShowThem() {
        val withHighlightsSection = transcriptWithHighlights.replace(
            "\"summary\":\"A greeting.\"",
            "\"summary\":\"A greeting.\\n\\nHighlights:\\n\\n- At 0:06: Hello there.\\n\\nNo action items.\""
        )
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(withHighlightsSection))
        val activity = launchServer(fake)
        assertEquals("A greeting.", activity.findViewById<android.widget.TextView>(R.id.summaryText).text.toString())
        assertEquals(2, activity.findViewById<android.widget.LinearLayout>(R.id.highlightsList).childCount)
        // Without highlight rows the model's list stays (there is nothing else showing it).
        val noRows = transcriptJson.replace("\"summary\":\"A greeting.\"", "\"summary\":\"A greeting.\\n\\n## Highlights\\n\\n- One\"")
        val other = launchServer(FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(noRows)))
        assertTrue(other.findViewById<android.widget.TextView>(R.id.summaryText).text.toString().contains("Highlights"))
    }

    @Test
    fun theMoreButtonIsHiddenUntilThereIsARecordingToActOn() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
        val activity = launchServer(fake, id = "missing")
        assertEquals(View.GONE, activity.findViewById<View>(R.id.moreButton).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.jumpToButton).visibility)
        assertEquals(null, activity.buildMoreMenu())
    }

    @Test
    fun theMoreMenuOffersNoCopyOrExportDuplicatesOfThePills() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        val menu = activity.buildMoreMenu()!!
        val titles = (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible }.map { it.title.toString() }
        assertEquals(listOf("Rename", "Run automations", "Run automations with instructions…", "Re-transcribe", "Delete"), titles)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptActions).visibility)
    }

    @Test
    fun summaryMarkdownIsRenderedNotShownRaw() {
        val markdownSummary = transcriptJson.replace(
            "\"summary\":\"A greeting.\"",
            "\"summary\":\"## Summary\\n\\n**Key point.** A greeting.\\n\\n- Hello\\n- Hi\""
        )
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(markdownSummary))
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
        val fake = FakeServerSource(serverRecording("transcribing"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
    fun noSpeechRecordingShowsOneLineKeepsThePlayerAndHidesTheTextActions() {
        val fake = FakeServerSource(noSpeechRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(noSpeechTranscript))
        val activity = launchServer(fake)
        // The header says why there is no title rather than showing the server's file name.
        assertEquals("No speech detected", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptionProgress).visibility)
        // One line and a mic-off icon; no subtitle repeating it, no section header for a transcript that is not there.
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertEquals("Nothing was said in this recording", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptySubtitle).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptSectionHeader).visibility)
        // Nothing to check for, copy or export; nothing to summarize.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.generateButton).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptActions).visibility)
        assertEquals(false, transcriptShown(activity))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.summaryHeaderRow).visibility)
        // Nothing was routed either, and no "No automations ran" verdict is needed.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        // The audio is still there to listen to.
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.audioPlayer).visibility)
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
        assertEquals(false, menu.findItem(R.id.action_run_automations_with_instructions).isVisible)
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
        assertEquals("Nothing was said in this recording", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.generateButton).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptActions).visibility)
    }

    @Test
    fun aFreshSpokenRecordingOverridesACachedNoSpeechDocument() {
        // Re-transcribed elsewhere: the phone still caches the empty document, the server now
        // has a spoken transcript. The server's word wins, in this direction too.
        val file = storeFile(noSpeechTranscript, serverId = "srv-9")
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        configureServer(fake)
        val activity = launchBoth(file.id)
        assertEquals("Server side \"Q3\" call", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
        assertTrue(transcriptShown(activity))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptActions).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertTrue(menuOf(activity).findItem(R.id.action_run_automations).isVisible)
        // The cache is brought up to date.
        assertEquals(transcriptJson, RecordingStore.allFiles.single().transcriptJSON)
    }

    @Test
    fun aFreshNoSpeechRecordingSuppressesCachedSpokenText() {
        // The other direction: the phone caches a spoken transcript, the server has since
        // re-transcribed and heard nothing. Its object arrives first, the document later.
        val file = storeFile(transcriptJson, serverId = "srv-9")
        val fake = FakeServerSource(noSpeechRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(noSpeechTranscript))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.transcriptGate = gate
        configureServer(fake)
        val activity = launchBoth(file.id)
        // Recording object known, transcript document still on the wire: no stale text, no actions.
        assertEquals(false, transcriptShown(activity))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcriptActions).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertEquals("Nothing was said in this recording", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptySubtitle).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.summaryHeaderRow).visibility)
        var menu = menuOf(activity)
        assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
        fake.transcriptGate = null
        gate.complete(Unit)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(false, transcriptShown(activity))
        assertEquals("Nothing was said in this recording", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
        menu = menuOf(activity)
        assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
        // The phone's cache now holds the empty document, so the list agrees next time.
        assertEquals(noSpeechTranscript, RecordingStore.allFiles.single().transcriptJSON)
    }

    @Test
    fun aRecentNoSpeechUploadDoesNotWaitForARouterRun() {
        // Uploaded a minute ago, but silent: the server routes nothing, so there is no run to
        // wait for and no reason to poll the automations.
        val fake = FakeServerSource(noSpeechRecording(uploadedAt = justNowIso()), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(noSpeechTranscript))
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
        val fake = FakeServerSource(serverRecording("transcribing", uploadedAt = justNowIso()), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
        val activity = launchServer(fake)
        val looper = shadowOf(android.os.Looper.getMainLooper())
        fake.rec = noSpeechRecording(uploadedAt = justNowIso())
        fake.transcript = cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(noSpeechTranscript)
        val before = fake.routingCalls.size
        activity.findViewById<View>(R.id.generateButton).performClick()
        looper.idle()
        assertEquals("Nothing was said in this recording", activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString())
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
        val fake = FakeServerSource(serverRecording("pending").copy(noSpeech = false, title = null), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
            cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending
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
            cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending
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
        fake.transcript = cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson)
        looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(beforeDone + 1, fake.recordingCalls.size)
        assertTrue(transcriptShown(activity))
        assertEquals(View.GONE, badge.visibility)
        assertEquals(View.GONE, bar.visibility)
        looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(beforeDone + 1, fake.recordingCalls.size)
    }

    @Test
    fun aPollThatSeesTheTranscriptionFailShowsThatAndStops() {
        val fake = FakeServerSource(serverRecording("transcribing"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
        val fake = FakeServerSource(serverRecording("transcribing"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
            fake.transcript = cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson)
            looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertEquals(3, fake.recordingCalls.size)
            assertTrue(transcriptShown(activity))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
            looper.idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
            assertEquals(3, fake.recordingCalls.size)
        } finally {
            http.shutdown()
        }
    }

    @Test
    fun transcriptionPollingPausesWithTheScreenAndAsksAtOnceOnReturn() {
        val fake = FakeServerSource(serverRecording("pending"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        launchServer(fake)
        val before = fake.recordingCalls.size
        shadowOf(android.os.Looper.getMainLooper()).idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before, fake.recordingCalls.size)
    }

    // MARK: - Run automations with instructions

    private fun latestDialog() = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog

    @Test
    fun runWithInstructionsSendsTheTrimmedTextWithItsOwnKey() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        assertEquals("Automations queued", latestSnackbarText(activity))
        // A plain run afterwards is a new intent: its own key, no instructions.
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(listOf<String?>("file this as a work meeting", null), fake.rerunInstructions)
        assertEquals(2, fake.rerunKeys.toSet().size)
    }

    @Test
    fun instructionsAreCappedAtTheServersLimit() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(id = "run-1", deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Old") + "]")}],"deliveries":[]}""")
        fake.rerunResult = cloud.adamrb.transom.net.ApiClient.ActionResult.Error("timeout")
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
        assertTrue(activity.onMenuAction(R.id.action_run_automations_with_instructions))
        latestDialog().apply {
            findViewById<android.widget.EditText>(R.id.instructionsField)!!.setText("file as personal")
            getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        looper.idle()
        assertEquals(3, fake.reruns.size)
        assertEquals(fake.rerunKeys[0], fake.rerunKeys[2])
        assertEquals("file as work", fake.rerunInstructions[2])
        assertEquals("Retrying the earlier run first. Run again once it has finished.", latestSnackbarText(activity))
        looper.idleFor(FileDetailActivity.RERUN_REFRESH_DELAYS_MS[1], java.util.concurrent.TimeUnit.MILLISECONDS)
        // A plain run while it is still unresolved replays it too, instructions included.
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(4, fake.reruns.size)
        assertEquals(fake.rerunKeys[0], fake.rerunKeys[3])
        assertEquals("file as work", fake.rerunInstructions[3])
        assertEquals("Retrying the earlier run first. Run again once it has finished.", latestSnackbarText(activity))
        looper.idleFor(FileDetailActivity.RERUN_REFRESH_DELAYS_MS[1], java.util.concurrent.TimeUnit.MILLISECONDS)
        // The server finally answers for real: the intent is settled, and the next tap is new.
        fake.rerunResult = cloud.adamrb.transom.net.ApiClient.ActionResult.Ok
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        looper.idle()
        assertEquals(5, fake.reruns.size)
        assertEquals(fake.rerunKeys[0], fake.rerunKeys[4])
        assertEquals("Automations queued", latestSnackbarText(activity))
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
        val fake = FakeServerSource(noSpeechRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(noSpeechTranscript))
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
        val fake = FakeServerSource(serverRecording("transcribing"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
        fake.transcript = cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson)
        fake.recordingGate = null
        gate.complete(Unit)
        looper.idle()
        assertTrue(transcriptShown(activity))
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
        val fake = FakeServerSource(serverRecording("transcribing"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
        fake.transcript = cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson)
        staleGate.complete(Unit)
        looper.idle()
        assertEquals("Transcribing", activity.findViewById<android.widget.TextView>(R.id.statusBadge).text.toString())
        assertEquals(false, transcriptShown(activity))
        assertEquals(transcriptReads, fake.transcriptCalls.size)
        // Polling is still alive and picks the real finish up on its next tick.
        val before = fake.recordingCalls.size
        looper.idleFor(FileDetailActivity.TRANSCRIPTION_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(before + 1, fake.recordingCalls.size)
        assertTrue(transcriptShown(activity))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
    }

    @Test
    fun aRunStartedWithInstructionsShowsThemOnItsCard() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        activity.findViewById<View>(R.id.exportMarkdownButton).performClick()
        val exported = File(context.cacheDir, "exports/Server side -Q3- call.md")
        assertTrue("export file missing: ${exported.path}", exported.exists())
        val text = exported.readText()
        assertTrue(text, text.startsWith("---\ntitle: \"Server side \\\"Q3\\\" call\"\nrecorded: \"2026-09-07T05:27:31Z\"\nduration_s: \"61\"\n"))
    }

    @Test
    fun serverModeCopyUsesSpeakerParagraphs() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        activity.findViewById<View>(R.id.copyTranscriptButton).performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("Speaker 1: Hello there.\n\nSpeaker 2: Hi.", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun serverModeNotFoundShowsMessageWithoutCrashing() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
        val item = cloud.adamrb.transom.ui.recordings.RecordingItem(file, serverRecording())
        val intent = FileDetailActivity.intentFor(context, item)
        assertEquals(file.id, intent.getStringExtra(FileDetailActivity.EXTRA_FILE_ID))
        assertEquals("srv-9", intent.getStringExtra(FileDetailActivity.EXTRA_SERVER_RECORDING_ID))
        val serverOnly = FileDetailActivity.intentFor(context, cloud.adamrb.transom.ui.recordings.RecordingItem(null, serverRecording()))
        assertEquals(null, serverOnly.getStringExtra(FileDetailActivity.EXTRA_FILE_ID))
        assertEquals("srv-9", serverOnly.getStringExtra(FileDetailActivity.EXTRA_SERVER_RECORDING_ID))
    }

    @Test
    fun unifiedOpenRefreshesTitleAndTranscriptFromTheServerAndCachesThem() {
        val file = storeFile(transcriptJson, serverId = "srv-9")
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptWithHighlights))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        configureServer(fake)
        val activity = launchBoth(file.id)
        // The server says "Server side Q3 call"; the user's own name is pinned and wins.
        assertEquals("Walk with Sam", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
    }

    @Test
    fun unifiedOpenKeepsThePhoneContentWhenTheServerIsUnreachable() {
        val file = storeFile(transcriptJson, serverId = "srv-9")
        val fake = object : FileDetailActivity.ServerDetailSource by FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending) {
            override suspend fun recording(id: String) = cloud.adamrb.transom.net.ApiClient.RecordingResult.Error("offline")
        }
        configureServer(fake)
        val activity = launchBoth(file.id)
        assertEquals("Budget \"Q3\" call", activity.findViewById<android.widget.TextView>(R.id.fileNameLabel).text.toString())
        assertTrue(transcriptShown(activity))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.statusBadge).visibility)
    }

    @Test
    fun failedTranscriptionShowsFailedBadgeAndTheSingleCheckButton() {
        val fake = FakeServerSource(serverRecording("failed", error = "GPU on fire"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
    fun aFailedRecordingShowsTheServersSentenceAndTheRawTextBehindDetails() {
        val fake = FakeServerSource(
            serverRecording("failed", error = "Transcription failed.", extra = """"error_detail":"RuntimeError: CUDA out of memory""""),
            cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending
        )
        val activity = launchServer(fake)
        assertEquals("Transcription failed.", activity.findViewById<android.widget.TextView>(R.id.emptySubtitle).text.toString())
        val toggle = activity.findViewById<View>(R.id.emptyDetailsToggle)
        val details = activity.findViewById<android.widget.TextView>(R.id.emptyDetails)
        assertEquals(View.VISIBLE, toggle.visibility)
        assertEquals(View.GONE, details.visibility)
        toggle.performClick()
        assertEquals(View.VISIBLE, details.visibility)
        assertEquals("RuntimeError: CUDA out of memory", details.text.toString())
        // Without a detail there is no disclosure to open.
        val plain = launchServer(FakeServerSource(serverRecording("failed", error = "Transcription failed."), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending))
        assertEquals(View.GONE, plain.findViewById<View>(R.id.emptyDetailsToggle).visibility)
    }

    @Test
    fun serverRefusalsShowTheServersOwnWordsNeverTheStatusCode() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.rerunResult = cloud.adamrb.transom.net.ApiClient.ActionResult.Error("HTTP 409", 409, "Automations are turned off on the server")
        val activity = launchServer(fake)
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val message = latestDialog().findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        assertEquals("Automations are turned off on the server", message)
        latestDialog().dismiss()
        // No detail from the server: a generic line, not "HTTP 500".
        fake.rerunResult = cloud.adamrb.transom.net.ApiClient.ActionResult.Error("HTTP 500", 500)
        assertTrue(activity.onMenuAction(R.id.action_run_automations))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val generic = latestDialog().findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        assertEquals("Couldn't reach your server. Try again.", generic)
        assertEquals(false, generic.contains("500"))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        assertEquals(View.VISIBLE, both.findViewById<View>(R.id.transcriptActions).visibility)

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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        assertEquals("Removed from this phone", latestSnackbarText(activity))
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
        assertTrue(transcriptShown(activity))
    }

    @Test
    fun removingThePhoneCopyWhileItPlaysHandsThePlayerTheServerStream() {
        val audio = File(context.filesDir, "7.mp3").apply { writeBytes(ByteArray(16)) }
        val file = storeFile(transcriptJson, serverId = "srv-9", localPath = audio.absolutePath)
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        configureServer(fake)
        val activity = launchBoth(file.id)
        // The phone's file is what plays.
        assertEquals(audio.absolutePath, fakePlayback.currentMediaId)
        activity.findViewById<View>(R.id.playPauseButton).performClick()
        assertTrue(fakePlayback.isPlaying)
        assertTrue(activity.onMenuAction(R.id.action_remove_from_phone))
        confirmLatestDialog()
        // The deleted file is no longer the source: the server stream is loaded, at rest.
        assertEquals("https://bridge.example.com/api/v1/recordings/srv-9/audio", fakePlayback.currentMediaId)
        assertEquals(false, fakePlayback.isPlaying)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.audioPlayer).visibility)
    }

    @Test
    fun deleteOfPhoneOnlyRecordingNeverAsksTheServer() {
        val file = storeFile(null, serverId = null, uploaded = false, localPath = null)
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
        configureServer(fake)
        val activity = launch(file.id)
        assertTrue(activity.onMenuAction(R.id.action_delete))
        confirmLatestDialog()
        assertTrue(fake.deleted.isEmpty())
        assertTrue(RecordingStore.allFiles.isEmpty())
        assertTrue(activity.isFinishing)
    }

    // MARK: - Automations section

    private fun runsOf(json: String) = cloud.adamrb.transom.net.ApiClient.RoutingResult.Ok(
        cloud.adamrb.transom.models.RoutingRun.listFromJson(json)
    )

    private fun metaDate(iso: String): String =
        java.text.SimpleDateFormat("MMM d, yyyy \u00b7 HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(cloud.adamrb.transom.models.ServerRecording.parseIso(iso)!!))

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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        // The agent's summary stands alone: no "Done" chip next to it.
        assertEquals(null, block.findViewById<View>(R.id.automation_delivery_pill))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "ask-claude", "ok", "queued") + "]")}],"deliveries":[]}""")
        val controller = serverController(fake)
        val activity = controller.get()
        val block = automationsList(activity).getChildAt(0)
        // In flight: the chip alone says so, no text repeating it.
        assertEquals("Working", textOf(block, R.id.automation_delivery_pill))
        assertEquals(null, block.findViewById<View>(R.id.automation_delivery_text))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
            deliveries = "[" + delivery("d-7", "obsidian-inbox", "ok", "done", "Saved to Inbox/Note.md") + "]")}],"deliveries":[]}""")
        retry.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf("d-7"), fake.retries)
        assertEquals("Retry queued", latestSnackbarText(activity))
        assertEquals(before + 1, fake.routingCalls.size)
        val refreshed = automationsList(activity).getChildAt(0)
        assertEquals(null, refreshed.findViewById<View>(R.id.automation_delivery_pill))
        assertEquals("Saved to Inbox/Note.md", textOf(refreshed, R.id.automation_delivery_text))
        assertEquals(null, refreshed.findViewById<View>(R.id.automation_retry))
    }

    @Test
    fun agentFailureShowsItsSummaryAndRetry() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-2", "meetings", "ok", "failed", "Vault path not writable") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val block = automationsList(activity).getChildAt(0)
        assertEquals("Failed", textOf(block, R.id.automation_delivery_pill))
        assertEquals("Vault path not writable", textOf(block, R.id.automation_delivery_text))
        assertNotNull(block.findViewById<View>(R.id.automation_retry))
    }

    @Test
    fun handedOffWithoutAReportShowsTheHandOffState() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-3", "meetings", "ok") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val block = automationsList(activity).getChildAt(0)
        // Said once, as text; no chip doubling it.
        assertEquals(null, block.findViewById<View>(R.id.automation_delivery_pill))
        assertEquals("Handed off", textOf(block, R.id.automation_delivery_text))
        assertEquals(metaDate("2026-09-07T06:39:05Z"), textOf(block, R.id.automation_delivery_time))
        assertEquals(null, block.findViewById<View>(R.id.automation_retry))
    }

    @Test
    fun emptyRoutesSayNothingMatchedWithTheRunTime() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(routes = "[]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        val block = automationsList(activity).getChildAt(0)
        assertEquals("No automation matched \u00b7 " + metaDate("2026-09-07T06:39:00Z"), textOf(block, R.id.automation_no_match))
        assertEquals(null, block.findViewById<View>(R.id.automation_route_name))
    }

    @Test
    fun runErrorShowsInTheErrorColor() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        assertEquals("No automations ran for this recording",
            activity.findViewById<android.widget.TextView>(R.id.automationsEmpty).text.toString())
        assertEquals(View.GONE, automationsList(activity).visibility)
    }

    @Test
    fun noRunsWhileStillTranscribingKeepsTheSectionHiddenAndTheMenuActionAway() {
        val fake = FakeServerSource(serverRecording("transcribing"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = cloud.adamrb.transom.net.ApiClient.RoutingResult.NotFound
        val activity = launchServer(fake)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertTrue(transcriptShown(activity))
    }

    @Test
    fun phoneOnlyRecordingNeverAsksForRouting() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
        configureServer(fake)
        val file = storeFile(transcriptJson, serverId = null, uploaded = false)
        val activity = launch(file.id)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertTrue(fake.routingCalls.isEmpty())
    }

    @Test
    fun onlyTheLatestRunShowsUntilEarlierRunsAreRequested() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        assertEquals("Automations queued", latestSnackbarText(activity))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        fake.transcript = cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson)
        activity.findViewById<View>(R.id.generateButton).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(transcriptShown(activity))
        return activity
    }

    @Test
    fun transcriptArrivalWaitsForTheRouterRunInsteadOfSayingNothingRan() {
        val fake = FakeServerSource(serverRecording("transcribing"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
        val fake = FakeServerSource(serverRecording("transcribing"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        val fake = FakeServerSource(serverRecording(uploadedAt = justNow), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(id = "run-1", deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Old outcome") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        assertEquals("Old outcome", textOf(automationsList(activity).getChildAt(0), R.id.automation_delivery_text))
        // Re-transcribe; the replacement transcript (byte for byte the same text, the hard case)
        // is ready by the time the screen re-reads it, but the router has only the old run so far.
        assertTrue(activity.onMenuAction(R.id.action_retranscribe))
        val looper = shadowOf(android.os.Looper.getMainLooper())
        looper.idle()
        assertEquals("Transcription queued", latestSnackbarText(activity))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
            assertTrue(transcriptShown(activity))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        // The server's own verdict on a job that never reported back within its deadline.
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-9", "ask-claude", "ok", "unknown", "No result was reported") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val block = automationsList(activity).getChildAt(0)
        // Neutral, not alarming: the job was accepted, the outcome is simply not known. Plain
        // text, no chip (chips are for Working and Failed only).
        assertEquals(null, block.findViewById<View>(R.id.automation_delivery_pill))
        assertEquals("No result reported", textOf(block, R.id.automation_delivery_text))
        assertNotNull(block.findViewById<View>(R.id.automation_retry))
        // Not in progress: nothing to poll for.
        val before = fake.routingCalls.size
        shadowOf(android.os.Looper.getMainLooper()).idleFor(5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(before, fake.routingCalls.size)
    }

    @Test
    fun aFailedPollStillCountsAndTheNextOneRuns() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "ask-claude", "ok", "queued") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val before = fake.routingCalls.size
        val looper = shadowOf(android.os.Looper.getMainLooper())
        // The first poll hits a blip; the Working line stays and the next poll is still scheduled.
        fake.routing = cloud.adamrb.transom.net.ApiClient.RoutingResult.Error("timeout")
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = cloud.adamrb.transom.net.ApiClient.RoutingResult.NotFound
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
        val fake = FakeServerSource(serverRecording("transcribing"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
        val activity = openTranscribingThenTranscriptArrives(fake)
        fake.routing = cloud.adamrb.transom.net.ApiClient.RoutingResult.Error("offline")
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
        fake.routing = cloud.adamrb.transom.net.ApiClient.RoutingResult.NotFound
        val http = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val activity = launchLegacyAgainst(http, fake, cachedTranscript = null)
            assertEquals(listOf("srv-9"), fake.routingCalls)
            assertTrue(transcriptShown(activity))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Filed") + "]")}],"deliveries":[]}""")
        val http = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val activity = launchLegacyAgainst(
                http, fake, cachedTranscript = transcriptJson,
                transcriptResponse = okhttp3.mockwebserver.MockResponse().setResponseCode(409)
            )
            assertEquals(listOf("srv-9"), fake.routingCalls)
            assertTrue(transcriptShown(activity))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        val before = fake.routingCalls.size
        gate.complete(Unit)
        looper.idle()
        // The outcome lands on the replacement screen: it gets the confirmation and the refreshes.
        assertEquals("Automations queued", latestSnackbarText(activity))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        val activity = launchServer(fake)
        // Re-transcribe: the server now reports pending and has no transcript to route (409).
        fake.rec = serverRecording("pending")
        fake.transcript = cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending
        assertTrue(activity.onMenuAction(R.id.action_retranscribe))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(transcriptShown(activity))
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptActions).visibility)
        // Back to done: offered again.
        fake.rec = serverRecording()
        fake.transcript = cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson)
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
        val fake = FakeServerSource(serverRecording("transcribing"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
        configureServer(fake)
        val activity = launchBoth(file.id)
        assertTrue(transcriptShown(activity))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsHeader).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.automationsEmpty).visibility)
        val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.moreButton)).also {
            it.menuInflater.inflate(R.menu.menu_file_detail, it.menu)
            activity.applyMenuVisibility(it.menu)
        }.menu
        assertEquals(false, menu.findItem(R.id.action_run_automations).isVisible)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcriptActions).visibility)
    }

    @Test
    fun aFailedRerunStillReReadsTheSectionInCaseTheServerRanIt() {
        // A lost response or timeout is ambiguous: the run may exist. Show it before another tap.
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.rerunResult = cloud.adamrb.transom.net.ApiClient.ActionResult.Error("timeout")
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(id = "run-1", deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Old") + "]")}],"deliveries":[]}""")
        fake.rerunResult = cloud.adamrb.transom.net.ApiClient.ActionResult.Error("timeout")
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.rerunResult = cloud.adamrb.transom.net.ApiClient.ActionResult.Error("timeout")
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-7", "meetings", "failed", lastError = "502") + "]")}],"deliveries":[]}""")
        fake.retryResult = cloud.adamrb.transom.net.ApiClient.RetryResult.Error("timeout")
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
        val fake = FakeServerSource(serverRecording("transcribing"), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
        fake.routing = runsOf("""{"runs":[${run(id = "run-1", deliveries = "[" + delivery("d-1", "meetings", "ok", "done", "Old") + "]")}],"deliveries":[]}""")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.routingGate = gate
        val activity = launchServer(fake)
        val looper = shadowOf(android.os.Looper.getMainLooper())
        // Check for transcript: recording done, transcript ready, while the first read is parked.
        fake.rec = serverRecording()
        fake.transcript = cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson)
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.rerunResult = cloud.adamrb.transom.net.ApiClient.ActionResult.Error("timeout")
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
        fake.routing = runsOf("""{"runs":[${run(deliveries = "[" + delivery("d-1", "meetings", "ok", actionType = "markdown") + "," +
            delivery("d-2", "meetings", "ok", actionType = "webhook") + "]")}],"deliveries":[]}""")
        val activity = launchServer(fake)
        val block = automationsList(activity).getChildAt(0) as android.view.ViewGroup
        val texts = (0 until block.childCount).map { block.getChildAt(it) }
            .mapNotNull { it.findViewById<android.widget.TextView>(R.id.automation_delivery_text) }
            .map { it.text.toString() }
        assertEquals(listOf("Done", "Handed off"), texts)
        assertEquals(null, block.findViewById<View>(R.id.automation_delivery_pill))
    }

    @Test
    fun runAutomationsWaitsForTheHistoryToLoad() {
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Ready(transcriptJson))
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
        val fake = FakeServerSource(serverRecording(), cloud.adamrb.transom.net.ApiClient.TranscriptResult.Pending)
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
