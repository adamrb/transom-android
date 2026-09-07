package org.plaudbridge.app.ui.settings

import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.R
import org.plaudbridge.app.models.VocabEntry
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * VocabularyActivity against a fake server seam: the editor shows the fetched text, Save sends
 * the parsed entries with sources preserved, and a failed load keeps Save disabled so an empty
 * editor can never replace the server's list.
 */
@RunWith(RobolectricTestRunner::class)
class VocabularyActivityTest {

    private lateinit var context: Context

    private val existing = listOf(
        VocabEntry("Morgan", emptyList(), "obsidian"),
        VocabEntry("Plaud Bridge", listOf("Plogged Bridge"), "manual")
    )

    private class FakeSource(
        var fetchResult: ApiClient.VocabularyResult,
        var saveResult: (List<VocabEntry>) -> ApiClient.VocabularyResult
    ) : VocabularyActivity.VocabularySource {
        val saved = mutableListOf<List<VocabEntry>>()
        override suspend fun fetch() = fetchResult
        override suspend fun save(entries: List<VocabEntry>): ApiClient.VocabularyResult {
            saved += entries
            return saveResult(entries)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "tok"
    }

    private fun launch(source: VocabularyActivity.VocabularySource): VocabularyActivity {
        VocabularyActivity.source = source
        return Robolectric.buildActivity(VocabularyActivity::class.java).setup().get()
    }

    @Test
    fun showsFetchedEditorTextAndStatus() {
        val fake = FakeSource(ApiClient.VocabularyResult.Ok(existing, "Morgan\nPlaud Bridge = Plogged Bridge")) {
            ApiClient.VocabularyResult.Ok(it, "")
        }
        val activity = launch(fake)
        assertEquals("Morgan\nPlaud Bridge = Plogged Bridge", activity.findViewById<EditText>(R.id.editor).text.toString())
        assertEquals("2 terms · 1 with corrections", activity.findViewById<TextView>(R.id.statusLabel).text.toString())
        assertTrue(activity.findViewById<Button>(R.id.saveButton).isEnabled)
    }

    @Test
    fun saveSendsParsedEntriesWithSourcesPreservedAndShowsServerResult() {
        val kept = listOf(
            VocabEntry("Morgan", listOf("Morgen"), "obsidian"),
            VocabEntry("Plaud Bridge", listOf("Plogged Bridge"), "manual"),
            VocabEntry("Scriberr", listOf("Scribber"), "manual")
        )
        val fake = FakeSource(ApiClient.VocabularyResult.Ok(existing, "Morgan\nPlaud Bridge = Plogged Bridge")) {
            ApiClient.VocabularyResult.Ok(kept, "Morgan = Morgen\nPlaud Bridge = Plogged Bridge\nScriberr = Scribber")
        }
        val activity = launch(fake)
        activity.findViewById<EditText>(R.id.editor).setText(
            "# people\nmorgan = Morgen\n\nPlaud Bridge = Plogged Bridge\nScriberr = Scribber\n"
        )
        activity.findViewById<Button>(R.id.saveButton).performClick()

        assertEquals(1, fake.saved.size)
        assertEquals(
            listOf(
                // Imported term keeps its source even though the user retyped it in lowercase.
                VocabEntry("morgan", listOf("Morgen"), "obsidian"),
                VocabEntry("Plaud Bridge", listOf("Plogged Bridge"), "manual"),
                VocabEntry("Scriberr", listOf("Scribber"), "manual")
            ),
            fake.saved[0]
        )
        // The editor shows what the server kept, and the status reflects it.
        assertEquals(
            "Morgan = Morgen\nPlaud Bridge = Plogged Bridge\nScriberr = Scribber",
            activity.findViewById<EditText>(R.id.editor).text.toString()
        )
        assertEquals("3 terms · 3 with corrections", activity.findViewById<TextView>(R.id.statusLabel).text.toString())
        assertEquals("Vocabulary saved. Applies to new transcriptions.", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.findViewById<Button>(R.id.saveButton).isEnabled)
    }

    @Test
    fun emptyListShowsTheEmptyHint() {
        val activity = launch(FakeSource(ApiClient.VocabularyResult.Ok(emptyList(), "")) { ApiClient.VocabularyResult.Ok(it, "") })
        assertEquals("No custom vocabulary yet.", activity.findViewById<TextView>(R.id.statusLabel).text.toString())
        assertEquals("", activity.findViewById<EditText>(R.id.editor).text.toString())
    }

    @Test
    fun failedLoadKeepsSaveDisabledAndNeverCallsSave() {
        val fake = FakeSource(ApiClient.VocabularyResult.Unsupported) { ApiClient.VocabularyResult.Ok(it, "") }
        val activity = launch(fake)
        assertFalse(activity.findViewById<Button>(R.id.saveButton).isEnabled)
        assertEquals(
            "Could not load vocabulary: This server does not support vocabulary yet. Update the server.",
            activity.findViewById<TextView>(R.id.statusLabel).text.toString()
        )
        activity.findViewById<Button>(R.id.saveButton).performClick()
        assertTrue(fake.saved.isEmpty())
    }

    @Test
    fun failedSaveShowsTheErrorAndKeepsTheUsersText() {
        val fake = FakeSource(ApiClient.VocabularyResult.Ok(existing, "Morgan\nPlaud Bridge = Plogged Bridge")) {
            ApiClient.VocabularyResult.Error("HTTP 500")
        }
        val activity = launch(fake)
        activity.findViewById<EditText>(R.id.editor).setText("Morgan\nNew Term")
        activity.findViewById<Button>(R.id.saveButton).performClick()
        assertEquals("Could not save vocabulary: HTTP 500", activity.findViewById<TextView>(R.id.statusLabel).text.toString())
        assertEquals("Morgan\nNew Term", activity.findViewById<EditText>(R.id.editor).text.toString())
        assertTrue(activity.findViewById<Button>(R.id.saveButton).isEnabled)
    }

    @Test
    fun unconfiguredServerDisablesTheEditor() {
        RecordingStore.serverBaseUrl = null
        RecordingStore.serverAuthToken = null
        val fake = FakeSource(ApiClient.VocabularyResult.Ok(existing, "x")) { ApiClient.VocabularyResult.Ok(it, "") }
        val activity = launch(fake)
        assertEquals("Not configured", activity.findViewById<TextView>(R.id.statusLabel).text.toString())
        assertFalse(activity.findViewById<EditText>(R.id.editor).isEnabled)
        assertFalse(activity.findViewById<Button>(R.id.saveButton).isEnabled)
    }
}
