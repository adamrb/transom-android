package org.plaudbridge.app.ui.library

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.plaudbridge.app.R
import org.plaudbridge.app.databinding.FragmentLibraryBinding
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.filedetail.FileDetailActivity

/**
 * Library tab: the recordings on the self-hosted bridge server, as a native list.
 *
 * This replaced an embedded WebView of the server dashboard. The web page never felt like part
 * of the app (different rows, different typography, its own navigation) and its JavaScript
 * confirm() dialogs did not work inside a WebView, so Delete silently did nothing. The list here
 * reuses the Files tab's row and header layouts through [LibraryAdapter] and opens recordings in
 * the same [FileDetailActivity] the Files tab uses (server mode). The dashboard itself is still
 * reachable from Settings ([WebDashboardActivity]) for the automations editor.
 *
 * Data is fetched live on every visit (resume, tab shown, pull) and never persisted: the server
 * is the source of truth for its own recordings, and writing them into RecordingStore would mix
 * them up with the phone's own sync index.
 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private var loadJob: Job? = null
    private var searchDebounce: Job? = null
    private var currentQuery: String? = null

    private val adapter = LibraryAdapter(
        onTapped = { rec ->
            val intent = Intent(requireContext(), FileDetailActivity::class.java)
            intent.putExtra(FileDetailActivity.EXTRA_SERVER_RECORDING_ID, rec.id)
            startActivity(intent)
        },
        onLongPressed = { rec -> showRowActions(rec) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recordingsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recordingsRecyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { load() }
        // The refresh layout's direct child is a FrameLayout, which never scrolls; ask the list
        // instead so a pull mid-list scrolls up rather than triggering a refresh.
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            _binding != null && binding.recordingsRecyclerView.canScrollVertically(-1)
        }

        binding.retryButton.setOnClickListener { load() }

        binding.searchButton.setOnClickListener { toggleSearch() }
        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Debounce so each keystroke does not become a server round trip.
                searchDebounce?.cancel()
                searchDebounce = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    val q = s?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    if (q != currentQuery) {
                        currentQuery = q
                        load()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /** Tabs are switched with show/hide, which does not touch the lifecycle. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) load()
    }

    // MARK: - Loading

    /**
     * Fetch the list. The previous rows stay on screen while the spinner runs, and on failure
     * they stay too, with a slim error line above them: a flaky connection should not blank a
     * list the user was just reading.
     */
    private fun load() {
        if (_binding == null) return
        if (!RecordingStore.isServerConfigured) {
            showError(getString(R.string.library_not_configured))
            adapter.submit(emptyList())
            updateEmptyState(isEmpty = true)
            return
        }
        loadJob?.cancel()
        binding.swipeRefresh.isRefreshing = true
        val query = currentQuery
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ApiClient.listRecordings(query) }
            if (_binding == null) return@launch
            binding.swipeRefresh.isRefreshing = false
            when (result) {
                is ApiClient.ListResult.Ok -> {
                    hideError()
                    adapter.submit(result.recordings)
                    updateEmptyState(result.recordings.isEmpty())
                }
                is ApiClient.ListResult.AuthError -> showError(getString(R.string.library_auth_failed))
                is ApiClient.ListResult.Error -> showError(getString(R.string.library_load_failed))
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyLabel.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recordingsRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showError(message: String) {
        if (_binding == null) return
        binding.errorLabel.text = message
        binding.errorView.visibility = View.VISIBLE
        binding.swipeRefresh.isRefreshing = false
    }

    private fun hideError() {
        binding.errorView.visibility = View.GONE
    }

    // MARK: - Search

    private fun toggleSearch() {
        val showing = binding.searchContainer.visibility == View.VISIBLE
        if (showing) {
            binding.searchContainer.visibility = View.GONE
            binding.searchField.setText("")
            hideKeyboard(binding.searchField)
            if (currentQuery != null) {
                currentQuery = null
                load()
            }
        } else {
            binding.searchContainer.visibility = View.VISIBLE
            binding.searchField.requestFocus()
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(binding.searchField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // MARK: - Row actions (long press)

    private fun showRowActions(rec: ServerRecording) {
        val actions = arrayOf(
            getString(R.string.rename),
            getString(R.string.retranscribe),
            getString(R.string.delete_from_server)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(rec.displayTitle)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showRenameDialog(rec)
                    1 -> retranscribe(rec)
                    2 -> confirmDelete(rec)
                }
            }
            .show()
    }

    private fun showRenameDialog(rec: ServerRecording) {
        val editText = EditText(requireContext()).apply {
            setText(rec.displayTitle)
            selectAll()
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename)
            .setView(editText)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val newTitle = editText.text.toString().trim()
                if (newTitle.isEmpty()) {
                    toast(getString(R.string.title_required))
                } else {
                    runServerAction { ApiClient.renameRecording(rec.id, newTitle).toActionResult() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun retranscribe(rec: ServerRecording) {
        runServerAction(successMessage = getString(R.string.retranscribe_queued)) {
            ApiClient.retranscribe(rec.id)
        }
    }

    private fun confirmDelete(rec: ServerRecording) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_from_server)
            .setMessage(getString(R.string.delete_from_server_confirm_fmt, rec.displayTitle))
            .setPositiveButton(R.string.delete) { _, _ ->
                runServerAction(successMessage = getString(R.string.recording_deleted)) {
                    ApiClient.deleteRecording(rec.id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Run a server write off the main thread, report the outcome, and refresh the list. */
    private fun runServerAction(successMessage: String? = null, action: () -> ApiClient.ActionResult) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            if (_binding == null) return@launch
            when (result) {
                is ApiClient.ActionResult.Ok -> successMessage?.let { toast(it) }
                is ApiClient.ActionResult.NotFound -> toast(getString(R.string.recording_not_on_server))
                is ApiClient.ActionResult.AuthError -> toast(getString(R.string.library_auth_failed))
                is ApiClient.ActionResult.Error -> toast(getString(R.string.server_request_failed_fmt, result.message))
            }
            load()
        }
    }

    private fun ApiClient.RecordingResult.toActionResult(): ApiClient.ActionResult = when (this) {
        is ApiClient.RecordingResult.Ok -> ApiClient.ActionResult.Ok
        is ApiClient.RecordingResult.NotFound -> ApiClient.ActionResult.NotFound
        is ApiClient.RecordingResult.AuthError -> ApiClient.ActionResult.AuthError(code)
        is ApiClient.RecordingResult.Error -> ApiClient.ActionResult.Error(message)
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadJob?.cancel()
        searchDebounce?.cancel()
        _binding = null
    }
}
