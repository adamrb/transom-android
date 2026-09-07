package org.plaudbridge.app.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.plaudbridge.app.R
import org.plaudbridge.app.databinding.ActivityVocabularyBinding
import org.plaudbridge.app.models.VocabEntry
import org.plaudbridge.app.models.VocabularyEditorText
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore

/**
 * Custom vocabulary editor (Settings > Custom vocabulary): the names and terms the server's
 * transcriber should get right, in the same one-line-per-term format the web dashboard uses.
 *
 * The editor is prefilled from GET /api/v1/vocabulary and Save PUTs the parsed list back, which
 * REPLACES the server's list. That is why Save stays disabled until a load has succeeded: with an
 * empty editor after a failed load, Save would silently wipe every term on the server. The
 * fetched entries are also what lets [VocabularyEditorText.parse] hand each existing term's
 * source ("manual" or "obsidian") back unchanged.
 */
class VocabularyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVocabularyBinding

    /** Entries as last confirmed by the server; null until the first load succeeds. */
    private var loadedEntries: List<VocabEntry>? = null

    /**
     * The server calls this screen makes. An interface (with the real client as the default) so
     * a Robolectric test can drive the screen without a network stack.
     */
    interface VocabularySource {
        suspend fun fetch(): ApiClient.VocabularyResult
        suspend fun save(entries: List<VocabEntry>): ApiClient.VocabularyResult
    }

    private object ApiVocabularySource : VocabularySource {
        override suspend fun fetch() = withContext(Dispatchers.IO) { ApiClient.fetchVocabulary() }
        override suspend fun save(entries: List<VocabEntry>) =
            withContext(Dispatchers.IO) { ApiClient.saveVocabulary(entries) }
    }

    companion object {
        /** Swapped by tests; production always uses the ApiClient-backed default. */
        @VisibleForTesting
        var source: VocabularySource = ApiVocabularySource
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVocabularyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener { save() }

        if (!RecordingStore.isServerConfigured) {
            binding.statusLabel.text = getString(R.string.not_configured)
            binding.editor.isEnabled = false
            return
        }
        load()
    }

    private fun load() {
        binding.statusLabel.text = getString(R.string.vocabulary_loading)
        lifecycleScope.launch {
            when (val result = source.fetch()) {
                is ApiClient.VocabularyResult.Ok -> {
                    loadedEntries = result.entries
                    binding.editor.setText(result.editorText)
                    binding.saveButton.isEnabled = true
                    renderStatus(result.entries)
                }
                else -> binding.statusLabel.text = getString(R.string.vocabulary_load_failed_fmt, errorMessage(result))
            }
        }
    }

    private fun save() {
        val existing = loadedEntries ?: return
        val entries = VocabularyEditorText.parse(binding.editor.text.toString(), existing)
        binding.saveButton.isEnabled = false
        binding.statusLabel.text = getString(R.string.vocabulary_saving)
        lifecycleScope.launch {
            val result = source.save(entries)
            binding.saveButton.isEnabled = true
            when (result) {
                is ApiClient.VocabularyResult.Ok -> {
                    // Show what the server kept (trimmed, deduped, sorted), like the dashboard's
                    // reload after save, so the editor never disagrees with the server.
                    loadedEntries = result.entries
                    binding.editor.setText(result.editorText)
                    renderStatus(result.entries)
                    Toast.makeText(this@VocabularyActivity, R.string.vocabulary_saved, Toast.LENGTH_SHORT).show()
                }
                else -> binding.statusLabel.text = getString(R.string.vocabulary_save_failed_fmt, errorMessage(result))
            }
        }
    }

    /** "50 terms · 12 with corrections", or the empty hint. Same wording as the dashboard. */
    private fun renderStatus(entries: List<VocabEntry>) {
        binding.statusLabel.text = if (entries.isEmpty()) getString(R.string.vocabulary_empty)
        else getString(
            R.string.vocabulary_status_fmt,
            resources.getQuantityString(R.plurals.vocabulary_terms, entries.size, entries.size),
            entries.count { it.aliases.isNotEmpty() }
        )
    }

    private fun errorMessage(result: ApiClient.VocabularyResult): String = when (result) {
        is ApiClient.VocabularyResult.Ok -> ""
        is ApiClient.VocabularyResult.Unsupported -> getString(R.string.vocabulary_unsupported)
        is ApiClient.VocabularyResult.AuthError -> getString(R.string.transcript_auth_error)
        is ApiClient.VocabularyResult.Error -> result.message
    }
}
