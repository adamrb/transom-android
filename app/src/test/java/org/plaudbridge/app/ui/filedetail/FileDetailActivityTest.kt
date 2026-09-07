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
            |Speaker 1: Hello there.
            |Speaker 2: Hi.
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
        val copied = clip.getItemAt(0).text.toString()
        assertTrue(copied, copied.contains("Speaker 00"))
        assertTrue(copied, copied.contains("Hello there."))
        assertEquals("Transcript copied", ShadowToast.getTextOfLatestToast())
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
            |Speaker 1: Hello there.
            |Speaker 2: Hi.
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
}
