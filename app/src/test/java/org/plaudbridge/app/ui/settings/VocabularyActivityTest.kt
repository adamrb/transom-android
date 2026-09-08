package org.plaudbridge.app.ui.settings

import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.R
import org.plaudbridge.app.models.VocabEntry
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.net.VocabularyImport
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * VocabularyActivity against a fake server seam: the list shows the fetched terms, Save is only
 * enabled once the list differs from the server's (dirty tracking), Save sends the entries with
 * sources preserved, leaving with unsaved edits asks first, the search box narrows the list, a
 * failed load keeps Save disabled so an empty list can never replace the server's, and the vault
 * import parses the gazetteer and merges through the import call.
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
        var saveResult: (List<VocabEntry>) -> ApiClient.VocabularyResult = { ApiClient.VocabularyResult.Ok(it, "") },
        var importResult: (List<VocabEntry>) -> ApiClient.VocabularyImportResult = { ApiClient.VocabularyImportResult.Ok(it, it.size) }
    ) : VocabularyActivity.VocabularySource {
        val saved = mutableListOf<List<VocabEntry>>()
        val imported = mutableListOf<List<VocabEntry>>()
        override suspend fun fetch() = fetchResult
        override suspend fun save(entries: List<VocabEntry>): ApiClient.VocabularyResult {
            saved += entries
            return saveResult(entries)
        }
        override suspend fun import(entries: List<VocabEntry>): ApiClient.VocabularyImportResult {
            imported += entries
            return importResult(entries)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "tok"
        ShadowDialog.reset()
    }

    @After
    fun tearDown() {
        ShadowDialog.reset()
    }

    private fun launch(source: VocabularyActivity.VocabularySource): VocabularyActivity {
        VocabularyActivity.source = source
        val activity = Robolectric.buildActivity(VocabularyActivity::class.java).setup().get()
        idle()
        return activity
    }

    /** ListAdapter delivers diffed lists through the main looper; drain it. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun VocabularyActivity.list() = findViewById<RecyclerView>(R.id.termsList)
    private fun VocabularyActivity.status() = findViewById<TextView>(R.id.statusLabel).text.toString()
    private fun VocabularyActivity.saveButton() = findViewById<Button>(R.id.saveButton)
    private fun VocabularyActivity.addButton() = findViewById<Button>(R.id.addButton)

    private fun latestDialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog
    private fun dialogMessage(): String = latestDialog().findViewById<TextView>(android.R.id.message)!!.text.toString()

    @Test
    fun showsFetchedTermsAndStatusWithSaveDisabledUntilSomethingChanges() {
        val activity = launch(FakeSource(ApiClient.VocabularyResult.Ok(existing, "")))
        assertEquals(2, activity.list().adapter!!.itemCount)
        assertEquals(existing, activity.currentEntries())
        assertEquals("2 terms · 1 with corrections", activity.status())
        assertFalse("nothing changed yet", activity.saveButton().isEnabled)
        assertTrue(activity.addButton().isEnabled)
        assertFalse(activity.isDirty)
    }

    @Test
    fun addingATermMakesTheListDirtyAndSaveSendsEverythingWithSourcesPreserved() {
        val kept = listOf(
            VocabEntry("Morgan", listOf("Morgen"), "obsidian"),
            VocabEntry("Plaud Bridge", listOf("Plogged Bridge"), "manual"),
            VocabEntry("Scriberr", listOf("Scribber"), "manual")
        )
        val fake = FakeSource(
            ApiClient.VocabularyResult.Ok(existing, ""),
            saveResult = { ApiClient.VocabularyResult.Ok(kept, "") }
        )
        val activity = launch(fake)

        assertNull(activity.addOrUpdateTerm("Scriberr", "Scribber, scriberr"))
        // Editing an imported term (retyped in lowercase, with a new mis-hearing) keeps its source.
        assertNull(activity.addOrUpdateTerm("morgan", "Morgen", existing[0]))
        idle()

        assertTrue(activity.isDirty)
        assertTrue(activity.saveButton().isEnabled)
        assertEquals("3 terms · 3 with corrections · Unsaved changes", activity.status())

        activity.saveButton().performClick()
        idle()

        assertEquals(1, fake.saved.size)
        assertEquals(
            listOf(
                VocabEntry("morgan", listOf("Morgen"), "obsidian"),
                VocabEntry("Plaud Bridge", listOf("Plogged Bridge"), "manual"),
                // The alias equal to the term itself was dropped.
                VocabEntry("Scriberr", listOf("Scribber"), "manual")
            ),
            fake.saved[0]
        )
        // The list shows what the server kept and is clean again.
        assertEquals(kept, activity.currentEntries())
        assertEquals("3 terms · 3 with corrections", activity.status())
        assertFalse(activity.saveButton().isEnabled)
        assertEquals("Saved. Applies to new transcriptions.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun undoingAnEditMakesTheListCleanAgain() {
        val activity = launch(FakeSource(ApiClient.VocabularyResult.Ok(existing, "")))
        activity.removeTerm(existing[1])
        idle()
        assertTrue(activity.isDirty)
        assertEquals(1, activity.currentEntries().size)

        assertNull(activity.addOrUpdateTerm("Plaud Bridge", "Plogged Bridge"))
        idle()
        assertFalse("same content as the server holds", activity.isDirty)
        assertFalse(activity.saveButton().isEnabled)
    }

    @Test
    fun blankAndDuplicateTermsAreRefused() {
        val activity = launch(FakeSource(ApiClient.VocabularyResult.Ok(existing, "")))
        assertEquals("Enter the term first.", activity.addOrUpdateTerm("   ", ""))
        assertEquals("That term is already in the list.", activity.addOrUpdateTerm("plaud bridge", ""))
        // Renaming a term onto itself with a different case is fine.
        assertNull(activity.addOrUpdateTerm("PLAUD BRIDGE", "Plogged Bridge", existing[1]))
        assertEquals(2, activity.currentEntries().size)
        assertEquals("PLAUD BRIDGE", activity.currentEntries().first { it.term.equals("plaud bridge", true) }.term)
    }

    @Test
    fun backWithUnsavedEditsAsksFirst() {
        val activity = launch(FakeSource(ApiClient.VocabularyResult.Ok(existing, "")))
        activity.addOrUpdateTerm("Scriberr", "")
        idle()

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals("Your edits to the vocabulary haven't been saved.", dialogMessage())
        latestDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse("Keep editing", activity.isFinishing)
        assertTrue("edits kept", activity.isDirty)

        activity.onBackPressedDispatcher.onBackPressed()
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue("Discard", activity.isFinishing)
    }

    @Test
    fun backWithoutEditsJustLeaves() {
        val activity = launch(FakeSource(ApiClient.VocabularyResult.Ok(existing, "")))
        activity.findViewById<View>(R.id.backButton).performClick()
        assertTrue(activity.isFinishing)
        assertNull(ShadowDialog.getLatestDialog())
    }

    @Test
    fun searchNarrowsTheListByTermOrMisHearing() {
        val activity = launch(FakeSource(ApiClient.VocabularyResult.Ok(existing, "")))
        val filter = activity.findViewById<EditText>(R.id.filterInput)

        filter.setText("plogged")
        idle()
        assertEquals(1, activity.list().adapter!!.itemCount)
        assertEquals("1 of 2 terms match", activity.status())

        filter.setText("zzz")
        idle()
        assertEquals(0, activity.list().adapter!!.itemCount)
        val empty = activity.findViewById<TextView>(R.id.emptyLabel)
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals("No terms match your search.", empty.text.toString())

        filter.setText("")
        idle()
        assertEquals(2, activity.list().adapter!!.itemCount)
        assertEquals(View.GONE, empty.visibility)
    }

    @Test
    fun emptyListShowsTheEmptyHint() {
        val activity = launch(FakeSource(ApiClient.VocabularyResult.Ok(emptyList(), "")))
        val empty = activity.findViewById<TextView>(R.id.emptyLabel)
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals("No custom vocabulary yet. Add a term to get started.", empty.text.toString())
        assertTrue(activity.addButton().isEnabled)
    }

    @Test
    fun failedLoadKeepsSaveDisabledAndNeverCallsSave() {
        val fake = FakeSource(ApiClient.VocabularyResult.Unsupported)
        val activity = launch(fake)
        assertFalse(activity.saveButton().isEnabled)
        assertFalse(activity.addButton().isEnabled)
        assertEquals(
            "Couldn't load the vocabulary. Your server doesn't support custom vocabulary yet. Update the server.",
            activity.status()
        )
        activity.saveButton().performClick()
        idle()
        assertTrue(fake.saved.isEmpty())
    }

    @Test
    fun failedSaveShowsASentenceNotTheStatusCodeAndKeepsTheEdits() {
        val fake = FakeSource(
            ApiClient.VocabularyResult.Ok(existing, ""),
            saveResult = { ApiClient.VocabularyResult.Error("HTTP 500") }
        )
        val activity = launch(fake)
        activity.addOrUpdateTerm("New Term", "")
        idle()
        activity.saveButton().performClick()
        idle()
        assertEquals("Couldn't save. Your server answered in an unexpected way. Try again.", activity.status())
        assertEquals(3, activity.currentEntries().size)
        assertTrue(activity.isDirty)
        assertTrue(activity.saveButton().isEnabled)
    }

    @Test
    fun unconfiguredServerDisablesTheEditor() {
        RecordingStore.serverBaseUrl = null
        RecordingStore.serverAuthToken = null
        val activity = launch(FakeSource(ApiClient.VocabularyResult.Ok(existing, "")))
        assertEquals("Not configured", activity.status())
        assertFalse(activity.saveButton().isEnabled)
        assertFalse(activity.addButton().isEnabled)
        assertFalse(activity.findViewById<EditText>(R.id.filterInput).isEnabled)
    }

    @Test
    fun importParsesTheGazetteerAndMergesThroughTheImportCall() {
        val merged = existing + VocabEntry("Dana Whitlock", listOf("Dana Whitlok"), "obsidian")
        val fake = FakeSource(ApiClient.VocabularyResult.Ok(existing, ""))
        fake.importResult = { ApiClient.VocabularyImportResult.Ok(merged, 1) }
        val activity = launch(fake)

        activity.importFromText(
            """
            # Names
            One line per name: Canonical | type | alias, alias
            Dana Whitlock | person | Dana Whitlok, Dana
            Morgan | person |
            the cleaner | role | cleaner
            """.trimIndent()
        )
        idle()

        assertEquals(1, fake.imported.size)
        assertEquals(
            listOf(
                VocabEntry("Dana Whitlock", listOf("Dana Whitlok"), "obsidian", VocabularyImport.GAZETTEER_WEIGHT),
                VocabEntry("Morgan", emptyList(), "obsidian", VocabularyImport.GAZETTEER_WEIGHT)
            ),
            fake.imported[0]
        )
        assertEquals(merged.sortedBy { it.term.lowercase() }, activity.currentEntries())
        assertFalse("the merged list is the new baseline", activity.isDirty)
        assertEquals("1 term added", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun importWithNothingUsableSaysSoAndSendsNothing() {
        val fake = FakeSource(ApiClient.VocabularyResult.Ok(existing, ""))
        val activity = launch(fake)
        activity.importFromText("just some prose\nwithout any gazetteer lines")
        idle()
        assertTrue(fake.imported.isEmpty())
        assertEquals("No names were found in that file.", activity.status())
    }

    @Test
    fun importIsRefusedWhileThereAreUnsavedEdits() {
        val fake = FakeSource(ApiClient.VocabularyResult.Ok(existing, ""))
        val activity = launch(fake)
        activity.addOrUpdateTerm("Scriberr", "")
        idle()
        activity.startImport()
        assertEquals("Save or discard your changes before importing.", dialogMessage())
        assertTrue(fake.imported.isEmpty())
    }

    @Test
    fun importErrorShowsTheServersOwnExplanation() {
        val fake = FakeSource(ApiClient.VocabularyResult.Ok(existing, ""))
        fake.importResult = { ApiClient.VocabularyImportResult.Error("HTTP 422", "Too many entries.") }
        val activity = launch(fake)
        activity.importFromText("Dana Whitlock | person | Dana Whitlok")
        idle()
        assertEquals("Couldn't import. Too many entries.", activity.status())
        assertEquals(existing, activity.currentEntries())
    }
}
