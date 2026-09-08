package org.plaudbridge.app.ui.recordings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.R
import org.plaudbridge.app.databinding.FragmentRecordingsBinding
import org.plaudbridge.app.managers.TitleSyncManager
import org.plaudbridge.app.managers.UploadManager
import org.plaudbridge.app.models.SyncProgress
import org.plaudbridge.app.models.SyncState
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.common.AppManagers
import org.plaudbridge.app.ui.common.ContentWidth
import org.plaudbridge.app.ui.common.FriendlyErrors
import org.plaudbridge.app.ui.common.SyncFeedback
import org.plaudbridge.app.ui.common.showSnackbar
import org.plaudbridge.app.ui.filedetail.FileDetailActivity
import org.plaudbridge.app.ui.home.FastTransferSheet

/**
 * Recordings tab: every recording the user has, whether it currently sits on the phone, on the
 * bridge server, or both, as one date-grouped list.
 *
 * This replaced the separate Files (phone) and Library (server) tabs. The split mirrored how
 * the app stores things, not how the user thinks about them: one recording showed up twice
 * with different names and different states, and nobody could say which tab to open. The rows
 * come from [RecordingsMerger] over the phone's sync index ([SyncManagerProtocol.files]), the
 * server snapshot in [RecordingsRepository] and the upload failures UploadManager remembers;
 * all three are observed, so a finished download, upload or transcription updates the row in
 * place. The server list is refreshed on every visit and on pull; a failed refresh keeps the
 * last snapshot on screen behind a slim error line.
 */
class RecordingsFragment : Fragment() {

    private var _binding: FragmentRecordingsBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireActivity().application as PlaudBridgeApp
    private val syncManager get() = AppManagers.sync(app)

    private var refreshJob: Job? = null
    private var merged: List<RecordingItem> = emptyList()
    private var query: String? = null

    /**
     * The one pending quiet re-read of the server list while a row on screen is still being
     * transcribed, see [schedulePollIfNeeded]. Cancelled whenever the list leaves the screen.
     */
    private var pollJob: Job? = null

    /** The delayed hide after a completed sync, cancelled if a new sync starts meanwhile. */
    private var bannerHide: Runnable? = null

    private val adapter = RecordingsAdapter(
        onTapped = { item -> startActivity(FileDetailActivity.intentFor(requireContext(), item)) },
        onActions = { item -> showRowActions(item) },
        diffExecutor = diffExecutorForTests
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRecordingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ContentWidth.limit(binding.recordingsRoot)
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
        // The keyboard's search key: the list already filters as you type, so it just puts the
        // keyboard away and leaves the results.
        binding.searchField.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(v)
                true
            } else false
        }

        binding.syncBanner.fastTransferButton.setOnClickListener {
            if (RecordingStore.fastTransferNeverShowAgain) {
                syncManager.startWiFiTransfer()
            } else {
                FastTransferSheet().show(childFragmentManager, "FastTransferSheet")
            }
        }

        childFragmentManager.setFragmentResultListener(RecordingActionsSheet.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            val (action, key) = RecordingActionsSheet.parseResult(result) ?: return@setFragmentResultListener
            val item = merged.firstOrNull { it.key == key } ?: return@setFragmentResultListener
            onRowAction(action, item)
        }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(syncManager.files, RecordingsRepository.server, UploadManager.failedUploads) { local, server, failed ->
                        RecordingsMerger.merge(local, server, failed)
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

    override fun onPause() {
        super.onPause()
        cancelPoll()
    }

    /** Tabs are switched with show/hide, which does not touch the lifecycle. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) cancelPoll() else if (_binding != null) refresh()
    }

    // MARK: - Data

    /** Apply the search to the merged list and toggle the empty label. */
    private fun render() {
        if (_binding == null) return
        val shown = RecordingsMerger.filter(merged, query)
        adapter.submit(shown, query)
        val q = query
        binding.emptyLabel.text =
            if (q != null) getString(R.string.search_no_results_fmt, q) else getString(R.string.no_recordings_yet)
        binding.emptyLabel.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.recordingsRecyclerView.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        schedulePollIfNeeded()
    }

    /**
     * Refresh the server snapshot. The rows stay on screen while the spinner runs, and on
     * failure they stay too, behind a slim error line: a flaky connection should not blank a
     * list the user was just reading. A [quiet] refresh (the transcription poll) skips the
     * spinner: a list that flashes every few seconds would draw the eye to nothing.
     */
    private fun refresh(quiet: Boolean = false) {
        if (_binding == null) return
        if (!RecordingStore.isServerConfigured) {
            showError(getString(R.string.library_not_configured))
            return
        }
        refreshJob?.cancel()
        if (!quiet) binding.swipeRefresh.isRefreshing = true
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = RecordingsRepository.refresh()
            if (_binding == null) return@launch
            binding.swipeRefresh.isRefreshing = false
            when (result) {
                is ApiClient.ListResult.Ok -> hideError()
                is ApiClient.ListResult.AuthError -> showError(getString(R.string.library_auth_failed))
                is ApiClient.ListResult.Error -> showError(getString(R.string.library_load_failed))
            }
            // An unchanged list emits nothing to the observer (and so no render): decide here too.
            schedulePollIfNeeded()
        }
    }

    /**
     * While a row on screen is still being transcribed, re-read the server list every few
     * seconds so its stage and percentage move without a pull. One pending read at a time, only
     * while the tab is the one showing and the app is in the foreground, and none once every
     * shown row has settled: the list then goes quiet until the next visit or pull.
     */
    private fun schedulePollIfNeeded() {
        cancelPoll()
        if (_binding == null || isHidden || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!shouldPoll(RecordingsMerger.filter(merged, query))) return
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(TRANSCRIPTION_POLL_INTERVAL_MS)
            refresh(quiet = true)
        }
    }

    private fun cancelPoll() {
        pollJob?.cancel()
        pollJob = null
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
        val container: TextInputLayout = binding.searchContainer
        val field: TextInputEditText = binding.searchField
        val showing = container.visibility == View.VISIBLE
        if (showing) {
            container.visibility = View.GONE
            field.setText("")
            hideKeyboard(field)
        } else {
            container.visibility = View.VISIBLE
            field.requestFocus()
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // MARK: - Sync banner (recorder to phone transfer progress)

    private fun updateSyncBanner(state: SyncState) {
        val bannerRoot = binding.syncBanner.root
        bannerHide?.let { bannerRoot.removeCallbacks(it) }
        bannerHide = null
        when (SyncFeedback.banner(state)) {
            SyncFeedback.Banner.SHOW -> {
                fadeInBanner(bannerRoot)
                updateBannerContent(state.currentProgress ?: return, state is SyncState.WiFiTransferring)
            }
            SyncFeedback.Banner.KEEP -> {}
            SyncFeedback.Banner.HIDE_SOON -> {
                val hide = Runnable { if (_binding != null) fadeOutBanner(bannerRoot) }
                bannerHide = hide
                bannerRoot.postDelayed(hide, 2000)
            }
            // Idle and Failed: the failure itself is told through MainActivity's snackbar.
            SyncFeedback.Banner.HIDE -> fadeOutBanner(bannerRoot)
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

    // MARK: - Row actions (⋮ or long press)

    /**
     * The sheet offers only what applies to this row: Retry upload needs a failed upload,
     * Re-transcribe needs a server copy, Remove from phone needs audio on the phone plus a
     * server copy to fall back to. Rename and Delete always apply and route themselves.
     */
    private fun showRowActions(item: RecordingItem) {
        if (childFragmentManager.findFragmentByTag(RecordingActionsSheet.TAG) != null) return
        val args = RecordingActionsSheet.argsFor(item, RecordingsAdapter.rowTitle(requireContext(), item))
        RecordingActionsSheet.newInstance(args).show(childFragmentManager, RecordingActionsSheet.TAG)
    }

    private fun onRowAction(action: RecordingActionsSheet.Action, item: RecordingItem) {
        when (action) {
            RecordingActionsSheet.Action.RENAME -> showRenameDialog(item)
            RecordingActionsSheet.Action.RETRY_UPLOAD -> retryUpload(item)
            RecordingActionsSheet.Action.RETRANSCRIBE -> retranscribe(item)
            RecordingActionsSheet.Action.REMOVE_FROM_PHONE -> confirmRemoveFromPhone(item)
            RecordingActionsSheet.Action.DELETE -> confirmDelete(item)
        }
    }

    private fun showRenameDialog(item: RecordingItem) {
        val context = requireContext()
        val layout = TextInputLayout(context).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val editText = TextInputEditText(context).apply {
            setText(item.title)
            selectAll()
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        layout.addView(editText)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.rename)
            .setView(layout)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val newTitle = editText.text.toString().trim()
                if (newTitle.isEmpty()) {
                    showSnackbar(getString(R.string.title_required))
                } else {
                    runAction { RecordingActions.rename(item, newTitle, serverActions, syncManager) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Forget the failure and run an upload pass; the row reads "Uploading" again at once. */
    private fun retryUpload(item: RecordingItem) {
        val localId = item.localId ?: return
        UploadManager.retryUpload(localId)
        showSnackbar(getString(R.string.upload_retrying))
    }

    private fun retranscribe(item: RecordingItem) {
        val serverId = item.serverId ?: return
        runAction(successMessage = getString(R.string.retranscribe_queued)) { RecordingActions.retranscribe(serverId, serverActions) }
    }

    private fun confirmRemoveFromPhone(item: RecordingItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_from_phone)
            .setMessage(getString(R.string.remove_from_phone_confirm_fmt, RecordingsAdapter.rowTitle(requireContext(), item)))
            .setPositiveButton(R.string.remove_from_phone) { _, _ ->
                if (RecordingActions.removeFromPhone(item, syncManager)) showSnackbar(getString(R.string.removed_from_phone))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(item: RecordingItem) {
        // The prompt names the row as the list shows it (see RecordingsAdapter.rowTitle).
        val shownTitle = RecordingsAdapter.rowTitle(requireContext(), item)
        val message = if (item.serverId != null) {
            getString(R.string.delete_everywhere_confirm_fmt, shownTitle)
        } else {
            getString(R.string.delete_phone_only_confirm_fmt, shownTitle)
        }
        MaterialAlertDialogBuilder(requireContext())
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
                is ApiClient.ActionResult.Ok -> successMessage?.let { showSnackbar(it) }
                is ApiClient.ActionResult.NotFound -> showSnackbar(getString(R.string.recording_not_on_server))
                is ApiClient.ActionResult.AuthError -> showSnackbar(getString(R.string.library_auth_failed))
                // The server's own sentence (its `detail`) when it sent one; never a code.
                is ApiClient.ActionResult.Error ->
                    showSnackbar(FriendlyErrors.forDisplay(result.detail ?: result.message, getString(R.string.server_action_failed)))
            }
            refresh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
        cancelPoll()
        bannerHide?.let { _binding?.syncBanner?.root?.removeCallbacks(it) }
        bannerHide = null
        _binding = null
    }

    companion object {
        /** Swapped by tests; production always uses the ApiClient-backed default. */
        @VisibleForTesting
        var serverActions: ServerRecordingActions = ApiServerRecordingActions

        /** Tests make the list diff synchronous; production diffs on a background thread. */
        @VisibleForTesting
        var diffExecutorForTests: java.util.concurrent.Executor? = null

        /** How often the list is re-read while a shown row is still being transcribed. */
        const val TRANSCRIPTION_POLL_INTERVAL_MS = 5_000L

        /** The list keeps polling only while a row the user can see is still being transcribed. */
        fun shouldPoll(shown: List<RecordingItem>): Boolean = shown.any { it.isTranscribing }
    }
}
