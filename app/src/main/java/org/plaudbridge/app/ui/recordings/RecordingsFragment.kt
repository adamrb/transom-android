package org.plaudbridge.app.ui.recordings

import android.content.Context
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.R
import org.plaudbridge.app.databinding.FragmentRecordingsBinding
import org.plaudbridge.app.managers.TitleSyncManager
import org.plaudbridge.app.models.SyncProgress
import org.plaudbridge.app.models.SyncState
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.filedetail.FileDetailActivity
import org.plaudbridge.app.ui.home.FastTransferSheet

/**
 * Recordings tab: every recording the user has, whether it currently sits on the phone, on the
 * bridge server, or both, as one date-grouped list.
 *
 * This replaced the separate Files (phone) and Library (server) tabs. The split mirrored how
 * the app stores things, not how the user thinks about them: one recording showed up twice
 * with different names and different states, and nobody could say which tab to open. The rows
 * come from [RecordingsMerger] over the phone's sync index ([SyncManagerProtocol.files]) and the
 * server snapshot in [RecordingsRepository]; both are observed, so a finished download, upload
 * or transcription updates the row in place. The server list is refreshed on every visit and on
 * pull; a failed refresh keeps the last snapshot on screen behind a slim error line.
 */
class RecordingsFragment : Fragment() {

    private var _binding: FragmentRecordingsBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireActivity().application as PlaudBridgeApp
    private val syncManager get() = app.syncManager

    private var refreshJob: Job? = null
    private var merged: List<RecordingItem> = emptyList()
    private var query: String? = null

    private val adapter = RecordingsAdapter(
        onTapped = { item -> startActivity(FileDetailActivity.intentFor(requireContext(), item)) },
        onLongPressed = { item -> showRowActions(item) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRecordingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recordingsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recordingsRecyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { refresh() }
        // The refresh layout's direct child is a FrameLayout, which never scrolls; ask the list
        // instead so a pull mid-list scrolls up rather than triggering a refresh.
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            _binding != null && binding.recordingsRecyclerView.canScrollVertically(-1)
        }
        binding.retryButton.setOnClickListener { refresh() }

        binding.searchButton.setOnClickListener { toggleSearch() }
        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                render()
            }
        })

        binding.syncBanner.fastTransferButton.setOnClickListener {
            if (RecordingStore.fastTransferNeverShowAgain) {
                syncManager.startWiFiTransfer()
            } else {
                FastTransferSheet().show(childFragmentManager, "FastTransferSheet")
            }
        }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(syncManager.files, RecordingsRepository.server) { local, server ->
                        RecordingsMerger.merge(local, server)
                    }.collect { items ->
                        merged = items
                        render()
                    }
                }
                launch {
                    syncManager.state.collect { state -> updateSyncBanner(state) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Pick up AI titles for uploaded recordings that have none yet. Cheap: with nothing
        // awaiting a transcript this touches neither the network nor WorkManager.
        TitleSyncManager.kick()
    }

    /** Tabs are switched with show/hide, which does not touch the lifecycle. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) refresh()
    }

    // MARK: - Data

    /** Apply the search to the merged list and toggle the empty label. */
    private fun render() {
        if (_binding == null) return
        val shown = RecordingsMerger.filter(merged, query)
        adapter.submit(shown)
        binding.emptyLabel.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.recordingsRecyclerView.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Refresh the server snapshot. The rows stay on screen while the spinner runs, and on
     * failure they stay too, behind a slim error line: a flaky connection should not blank a
     * list the user was just reading.
     */
    private fun refresh() {
        if (_binding == null) return
        if (!RecordingStore.isServerConfigured) {
            showError(getString(R.string.library_not_configured))
            return
        }
        refreshJob?.cancel()
        binding.swipeRefresh.isRefreshing = true
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = RecordingsRepository.refresh()
            if (_binding == null) return@launch
            binding.swipeRefresh.isRefreshing = false
            when (result) {
                is ApiClient.ListResult.Ok -> hideError()
                is ApiClient.ListResult.AuthError -> showError(getString(R.string.library_auth_failed))
                is ApiClient.ListResult.Error -> showError(getString(R.string.library_load_failed))
            }
        }
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

    // MARK: - Sync banner (recorder to phone transfer progress)

    private fun updateSyncBanner(state: SyncState) {
        val bannerRoot = binding.syncBanner.root
        when (state) {
            is SyncState.Syncing -> {
                fadeInBanner(bannerRoot)
                updateBannerContent(state.progress, false)
            }
            is SyncState.WiFiTransferring -> {
                fadeInBanner(bannerRoot)
                updateBannerContent(state.progress, true)
            }
            // Keep the banner untouched during the WiFi connect window (mirrors iOS)
            is SyncState.WiFiConnecting -> {}
            is SyncState.Completed -> {
                bannerRoot.postDelayed({ fadeOutBanner(bannerRoot) }, 2000)
            }
            else -> fadeOutBanner(bannerRoot)
        }
    }

    /** 0.25s fade in/out (mirrors iOS banner animations). */
    private fun fadeInBanner(v: View) {
        if (v.visibility == View.VISIBLE && v.alpha == 1f) return
        v.alpha = if (v.visibility == View.VISIBLE) v.alpha else 0f
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(250).start()
    }

    private fun fadeOutBanner(v: View) {
        if (v.visibility != View.VISIBLE) return
        v.animate().alpha(0f).setDuration(250)
            .withEndAction { v.visibility = View.GONE; v.alpha = 1f }
            .start()
    }

    private fun updateBannerContent(progress: SyncProgress, isWiFi: Boolean) {
        val banner = binding.syncBanner
        banner.syncTitleLabel.text =
            if (isWiFi) getString(R.string.fast_transfer) else getString(R.string.syncing_recordings)
        banner.syncCountLabel.text =
            if (progress.totalFiles > 0) "${progress.syncedFiles}/${progress.totalFiles}" else ""

        val fill = banner.syncProgressFill
        fill.post {
            val parent = fill.parent as? View ?: return@post
            val fraction = progress.progressFraction.coerceIn(0f, 1f)
            fill.layoutParams = fill.layoutParams.apply {
                width = (parent.width * fraction).toInt().coerceAtLeast(1)
            }
        }

        // Always show progress as a percentage so the readout never flickers between a speed and
        // a (usually "Untitled") file name; append the real transfer speed when one is reported.
        val percent = (progress.progressFraction.coerceIn(0f, 1f) * 100).toInt()
        banner.syncSpeedLabel.text = when {
            progress.totalFiles == 0 -> getString(R.string.retrieving_file_list)
            progress.bytesPerSecond > 0 -> "$percent% · ${progress.speedText}"
            else -> "$percent%"
        }

        banner.fastTransferButton.visibility = if (isWiFi) View.GONE else View.VISIBLE
    }

    // MARK: - Row actions (long press)

    /**
     * The sheet offers only what applies to this row: Re-transcribe needs a server copy, Remove
     * from phone needs a phone copy. Rename and Delete always apply and route themselves.
     */
    private fun showRowActions(item: RecordingItem) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.rename) to { showRenameDialog(item) }
        if (item.serverId != null) actions += getString(R.string.retranscribe) to { retranscribe(item) }
        if (item.local != null) actions += getString(R.string.remove_from_phone) to { confirmRemoveFromPhone(item) }
        actions += getString(R.string.delete) to { confirmDelete(item) }
        AlertDialog.Builder(requireContext())
            .setTitle(item.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun showRenameDialog(item: RecordingItem) {
        val editText = EditText(requireContext()).apply {
            setText(item.title)
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
                    runAction { RecordingActions.rename(item, newTitle, serverActions, syncManager) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun retranscribe(item: RecordingItem) {
        val serverId = item.serverId ?: return
        runAction(successMessage = getString(R.string.retranscribe_queued)) { serverActions.retranscribe(serverId) }
    }

    private fun confirmRemoveFromPhone(item: RecordingItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remove_from_phone)
            .setMessage(getString(R.string.remove_from_phone_confirm_fmt, item.title))
            .setPositiveButton(R.string.remove_from_phone) { _, _ ->
                RecordingActions.removeFromPhone(item, syncManager)
                toast(getString(R.string.removed_from_phone))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(item: RecordingItem) {
        val message = if (item.serverId != null) {
            getString(R.string.delete_everywhere_confirm_fmt, item.title)
        } else {
            getString(R.string.delete_phone_only_confirm_fmt, item.title)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ ->
                runAction(successMessage = getString(R.string.recording_deleted)) {
                    RecordingActions.delete(item, serverActions, syncManager)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Run a write, report the outcome, and refresh the server snapshot. */
    private fun runAction(successMessage: String? = null, action: suspend () -> ApiClient.ActionResult) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = action()
            if (_binding == null) return@launch
            when (result) {
                is ApiClient.ActionResult.Ok -> successMessage?.let { toast(it) }
                is ApiClient.ActionResult.NotFound -> toast(getString(R.string.recording_not_on_server))
                is ApiClient.ActionResult.AuthError -> toast(getString(R.string.library_auth_failed))
                is ApiClient.ActionResult.Error -> toast(getString(R.string.server_request_failed_fmt, result.message))
            }
            refresh()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
        _binding = null
    }

    companion object {
        /** Swapped by tests; production always uses the ApiClient-backed default. */
        @androidx.annotation.VisibleForTesting
        var serverActions: ServerRecordingActions = ApiServerRecordingActions
    }
}
