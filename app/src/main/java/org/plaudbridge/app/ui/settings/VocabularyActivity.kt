package org.plaudbridge.app.ui.settings

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.plaudbridge.app.R
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.common.ServerErrorText
import org.plaudbridge.app.databinding.ActivityVocabularyBinding
import org.plaudbridge.app.databinding.DialogVocabTermBinding
import org.plaudbridge.app.models.VocabEntry
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.net.VocabularyImport
import org.plaudbridge.app.storage.RecordingStore

/**
 * Custom vocabulary editor (Settings > Custom vocabulary): the names and terms the server's
 * transcriber should get right, as a searchable list of terms rather than one giant text box.
 *
 * The list is loaded from GET /api/v1/vocabulary and Save PUTs the whole list back, which
 * REPLACES the server's list. That is why Save is only enabled once a load has succeeded AND the
 * list differs from what the server holds: with an empty list after a failed load, Save would
 * silently wipe every term on the server. Every existing term keeps its [VocabEntry.source]
 * ("manual" or "obsidian") across edits so a later vault import can still tell them apart.
 *
 * Leaving with unsaved edits asks first. "Import from your vault" reads the vault's names file
 * from the phone's Obsidian copy ([VocabularyImport.parseGazetteer]) and merges it server-side
 * ([ApiClient.importVocabulary]); it never removes anything, so it needs no confirmation, only a
 * clean (saved) list to start from.
 */
class VocabularyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVocabularyBinding

    /** Entries as last confirmed by the server; null until the first load succeeds. */
    private var loadedEntries: List<VocabEntry>? = null

    /** The working copy the user edits, kept sorted the way the server sorts. */
    private var entries: List<VocabEntry> = emptyList()

    private var filter = ""
    private var busy = false

    private val adapter = VocabularyAdapter(onEdit = { showTermDialog(it) }, onRemove = { removeTerm(it) })

    /**
     * The server calls this screen makes. An interface (with the real client as the default) so
     * a Robolectric test can drive the screen without a network stack.
     */
    interface VocabularySource {
        suspend fun fetch(): ApiClient.VocabularyResult
        suspend fun save(entries: List<VocabEntry>): ApiClient.VocabularyResult
        suspend fun import(entries: List<VocabEntry>): ApiClient.VocabularyImportResult
    }

    private object ApiVocabularySource : VocabularySource {
        override suspend fun fetch() = withContext(Dispatchers.IO) { ApiClient.fetchVocabulary() }
        override suspend fun save(entries: List<VocabEntry>) =
            withContext(Dispatchers.IO) { ApiClient.saveVocabulary(entries) }
        override suspend fun import(entries: List<VocabEntry>) =
            withContext(Dispatchers.IO) { ApiClient.importVocabulary(entries) }
    }

    companion object {
        private const val TAG = "Vocabulary"
        private const val MENU_IMPORT = 1

        /** Swapped by tests; production always uses the ApiClient-backed default. */
        @VisibleForTesting
        var source: VocabularySource = ApiVocabularySource
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }

    private val backGuard = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (isDirty) confirmDiscard() else finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVocabularyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.termsList.layoutManager = LinearLayoutManager(this)
        binding.termsList.adapter = adapter

        binding.backButton.setOnClickListener { backGuard.handleOnBackPressed() }
        onBackPressedDispatcher.addCallback(this, backGuard)
        binding.saveButton.setOnClickListener { save() }
        binding.addButton.setOnClickListener { showTermDialog(null) }
        binding.moreButton.setOnClickListener { showMoreMenu() }
        binding.filterInput.doAfterTextChanged {
            filter = it?.toString()?.trim().orEmpty()
            render()
        }

        if (!RecordingStore.isServerConfigured) {
            binding.statusLabel.text = getString(R.string.not_configured)
            binding.filterInput.isEnabled = false
            binding.moreButton.isEnabled = false
            return
        }
        load()
    }

    // MARK: - State

    /** True when the working list differs from what the server last confirmed. */
    @get:VisibleForTesting
    val isDirty: Boolean
        get() {
            val loaded = loadedEntries ?: return false
            return sorted(entries) != sorted(loaded)
        }

    /** The working list, for tests. */
    @VisibleForTesting
    fun currentEntries(): List<VocabEntry> = entries

    private fun sorted(list: List<VocabEntry>): List<VocabEntry> =
        list.sortedBy { it.term.lowercase() }.map { it.copy(weight = null) }

    private fun setEntries(list: List<VocabEntry>) {
        entries = sorted(list)
        render()
    }

    private fun setBaseline(list: List<VocabEntry>) {
        loadedEntries = list
        setEntries(list)
    }

    /** Push the list, status line, empty state and button states to the screen. */
    private fun render() {
        val loaded = loadedEntries != null
        val shown = if (filter.isEmpty()) entries else entries.filter { e ->
            e.term.contains(filter, ignoreCase = true) || e.aliases.any { it.contains(filter, ignoreCase = true) }
        }
        adapter.submitList(shown)

        binding.addButton.isEnabled = loaded && !busy
        binding.saveButton.isEnabled = loaded && !busy && isDirty

        binding.emptyLabel.visibility = if (shown.isEmpty() && loaded) View.VISIBLE else View.GONE
        if (shown.isEmpty() && loaded) {
            binding.emptyLabel.text = getString(
                if (entries.isEmpty()) R.string.vocabulary_empty else R.string.vocabulary_no_match
            )
        }
        if (!busy && loaded) renderStatus(shown.size)
    }

    /** "50 terms · 12 with corrections", "3 of 50 terms match", plus "Unsaved changes" when dirty. */
    private fun renderStatus(shownCount: Int) {
        val base = when {
            filter.isNotEmpty() -> getString(R.string.vocabulary_filter_status_fmt, shownCount, entries.size)
            entries.isEmpty() -> ""
            else -> getString(
                R.string.vocabulary_status_fmt,
                resources.getQuantityString(R.plurals.vocabulary_terms, entries.size, entries.size),
                entries.count { it.aliases.isNotEmpty() }
            )
        }
        val unsaved = if (isDirty) getString(R.string.vocabulary_unsaved) else ""
        binding.statusLabel.text = listOf(base, unsaved).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun setBusy(status: String?) {
        busy = status != null
        if (status != null) binding.statusLabel.text = status
        render()
    }

    // MARK: - Server round trips

    private fun load() {
        setBusy(getString(R.string.vocabulary_loading))
        lifecycleScope.launch {
            val result = source.fetch()
            busy = false
            when (result) {
                is ApiClient.VocabularyResult.Ok -> setBaseline(result.entries)
                else -> {
                    // Nothing loaded: Save and Add stay disabled (render), and the reason is shown
                    // both in the status line and in place of the list.
                    render()
                    val message = getString(R.string.vocabulary_load_failed_fmt, errorMessage(result))
                    binding.statusLabel.text = message
                    binding.emptyLabel.text = message
                    binding.emptyLabel.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun save() {
        if (loadedEntries == null || !isDirty || busy) return
        val toSave = entries
        setBusy(getString(R.string.vocabulary_saving))
        lifecycleScope.launch {
            val result = source.save(toSave)
            busy = false
            when (result) {
                is ApiClient.VocabularyResult.Ok -> {
                    // Show what the server kept (trimmed, deduped, sorted), like the dashboard's
                    // reload after save, so the list never disagrees with the server.
                    setBaseline(result.entries)
                    Toast.makeText(this@VocabularyActivity, R.string.vocabulary_saved, Toast.LENGTH_SHORT).show()
                }
                else -> {
                    render()
                    binding.statusLabel.text = getString(R.string.vocabulary_save_failed_fmt, errorMessage(result))
                }
            }
        }
    }

    private fun errorMessage(result: ApiClient.VocabularyResult): String = when (result) {
        is ApiClient.VocabularyResult.Ok -> ""
        is ApiClient.VocabularyResult.Unsupported -> getString(R.string.vocabulary_unsupported)
        is ApiClient.VocabularyResult.AuthError -> getString(R.string.transcript_auth_error)
        is ApiClient.VocabularyResult.Error -> ServerErrorText.fromResultMessage(this, result.message)
    }

    // MARK: - Editing

    /**
     * Add a term or replace [existing]. Returns null on success or the reason the change was
     * refused (blank term, duplicate of another term). Aliases equal to the term are dropped;
     * an edited term keeps its source so the vault import can still tell it apart.
     */
    @VisibleForTesting
    fun addOrUpdateTerm(term: String, aliasesText: String, existing: VocabEntry? = null): String? {
        val t = term.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return getString(R.string.vocabulary_term_required)
        val clash = entries.any { e ->
            e.term.equals(t, ignoreCase = true) && (existing == null || !e.term.equals(existing.term, ignoreCase = true))
        }
        if (clash) return getString(R.string.vocabulary_term_exists)
        val aliases = aliasesText.split(',')
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() && !it.equals(t, ignoreCase = true) }
            .distinctBy { it.lowercase() }
        val updated = VocabEntry(t, aliases, existing?.source ?: VocabEntry.SOURCE_MANUAL)
        val rest = if (existing == null) entries
        else entries.filterNot { it.term.equals(existing.term, ignoreCase = true) }
        setEntries(rest + updated)
        return null
    }

    @VisibleForTesting
    fun removeTerm(entry: VocabEntry) {
        if (busy) return
        setEntries(entries.filterNot { it.term.equals(entry.term, ignoreCase = true) })
        Snackbar.make(binding.root, getString(R.string.vocabulary_removed_fmt, entry.term), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                if (entries.none { it.term.equals(entry.term, ignoreCase = true) }) setEntries(entries + entry)
            }
            .show()
    }

    private fun showTermDialog(existing: VocabEntry?) {
        if (loadedEntries == null || busy) return
        val form = DialogVocabTermBinding.inflate(layoutInflater)
        existing?.let {
            form.termInput.setText(it.term)
            form.aliasesInput.setText(it.aliases.joinToString(", "))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.vocabulary_add_term else R.string.vocabulary_edit_term)
            .setView(form.root)
            .setPositiveButton(if (existing == null) R.string.vocabulary_add_term else R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Validate on tap instead of letting the dialog close: the error shows inline.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val error = addOrUpdateTerm(
                    form.termInput.text?.toString().orEmpty(),
                    form.aliasesInput.text?.toString().orEmpty(),
                    existing
                )
                if (error == null) dialog.dismiss() else form.termLayout.error = error
            }
            form.termInput.doAfterTextChanged { form.termLayout.error = null }
            form.termInput.requestFocus()
        }
        dialog.show()
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setTitle(R.string.vocabulary_unsaved_title)
            .setMessage(R.string.vocabulary_unsaved_message)
            .setPositiveButton(R.string.discard) { _, _ -> finish() }
            .setNegativeButton(R.string.keep_editing, null)
            .show()
    }

    // MARK: - Import from the vault

    private fun showMoreMenu() {
        val menu = PopupMenu(this, binding.moreButton)
        menu.menu.add(0, MENU_IMPORT, 0, R.string.vocabulary_import).isEnabled = loadedEntries != null && !busy
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_IMPORT -> { startImport(); true }
                else -> false
            }
        }
        menu.show()
    }

    /** Explain what the import does, then open the system file picker on the vault's names file. */
    @VisibleForTesting
    fun startImport() {
        if (loadedEntries == null || busy) return
        if (isDirty) {
            AlertDialog.Builder(this)
                .setTitle(R.string.vocabulary_import)
                .setMessage(R.string.vocabulary_import_save_first)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.vocabulary_import)
            .setMessage(R.string.vocabulary_import_help)
            .setPositiveButton(R.string.choose_file) { _, _ -> filePicker.launch(arrayOf("*/*")) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importFromUri(uri: Uri) {
        setBusy(getString(R.string.vocabulary_importing))
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytesCompat(VocabularyImport.MAX_FILE_BYTES.toInt() + 1)
                        if (bytes.size > VocabularyImport.MAX_FILE_BYTES) null else String(bytes, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    AppLog.w(TAG, "could not read import file", e)
                    null
                }
            }
            if (text == null) {
                busy = false
                render()
                binding.statusLabel.text = getString(R.string.vocabulary_import_unreadable)
                return@launch
            }
            importFromText(text)
        }
    }

    /**
     * Parse gazetteer text and merge it into the server's list. Entry point for tests, which
     * cannot drive the system file picker.
     */
    @VisibleForTesting
    fun importFromText(text: String) {
        val parsed = VocabularyImport.parseGazetteer(text)
        if (parsed.isEmpty()) {
            busy = false
            render()
            binding.statusLabel.text = getString(R.string.vocabulary_import_nothing)
            return
        }
        setBusy(getString(R.string.vocabulary_importing))
        lifecycleScope.launch {
            val result = source.import(parsed)
            busy = false
            when (result) {
                is ApiClient.VocabularyImportResult.Ok -> {
                    setBaseline(result.entries)
                    val message = if (result.added > 0) {
                        resources.getQuantityString(R.plurals.vocabulary_import_added, result.added, result.added)
                    } else {
                        getString(R.string.vocabulary_import_none_added)
                    }
                    Toast.makeText(this@VocabularyActivity, message, Toast.LENGTH_LONG).show()
                }
                else -> {
                    render()
                    binding.statusLabel.text = getString(R.string.vocabulary_import_failed_fmt, importError(result))
                }
            }
        }
    }

    private fun importError(result: ApiClient.VocabularyImportResult): String = when (result) {
        is ApiClient.VocabularyImportResult.Ok -> ""
        is ApiClient.VocabularyImportResult.Unsupported -> getString(R.string.vocabulary_unsupported)
        is ApiClient.VocabularyImportResult.AuthError -> getString(R.string.transcript_auth_error)
        is ApiClient.VocabularyImportResult.Error -> result.detail ?: ServerErrorText.fromResultMessage(this, result.message)
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var remaining = limit
        while (remaining > 0) {
            val n = read(buf, 0, minOf(buf.size, remaining))
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }
}
