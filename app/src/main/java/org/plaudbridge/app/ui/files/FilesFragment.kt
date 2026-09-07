package org.plaudbridge.app.ui.files

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.R
import org.plaudbridge.app.databinding.FragmentFilesBinding
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.SyncProgress
import org.plaudbridge.app.models.SyncState
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.filedetail.FileDetailActivity
import org.plaudbridge.app.ui.list.DateGroupedAdapter
import org.plaudbridge.app.ui.list.DateGrouping
import kotlinx.coroutines.launch

/**
 * Files Tab — File list grouped by date + Sync Banner
 */
class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireActivity().application as PlaudBridgeApp
    private val syncManager get() = app.syncManager

    private val adapter = FilesAdapter { file ->
        val intent = Intent(requireContext(), FileDetailActivity::class.java)
        intent.putExtra("file_id", file.id)
        startActivity(intent)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.filesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.filesRecyclerView.adapter = adapter

        binding.searchButton.setOnClickListener { /* Search placeholder */ }

        binding.syncBanner.fastTransferButton.setOnClickListener {
            if (RecordingStore.fastTransferNeverShowAgain) {
                syncManager.startWiFiTransfer()
            } else {
                org.plaudbridge.app.ui.home.FastTransferSheet()
                    .show(childFragmentManager, "FastTransferSheet")
            }
        }

        observeManagers()
    }

    private fun observeManagers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    syncManager.files.collect { files ->
                        adapter.submitFiles(files)
                        binding.emptyLabel.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                        binding.filesRecyclerView.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
                    }
                }

                launch {
                    syncManager.state.collect { state ->
                        updateSyncBanner(state)
                    }
                }
            }
        }
    }

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

    /// 0.25s fade in/out (mirrors iOS banner animations).
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

        banner.fastTransferButton.visibility =
            if (isWiFi) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // Pick up AI titles for uploaded recordings that have none yet. Cheap: with nothing
        // awaiting a transcript this touches neither the network nor WorkManager.
        org.plaudbridge.app.managers.TitleSyncManager.kick()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// MARK: - RecyclerView Adapter

/**
 * File list adapter: the Files tab's rows on top of the shared [DateGroupedAdapter], so the
 * Library tab (server recordings) draws the identical list.
 */
class FilesAdapter(
    private val onFileTapped: (RecordingFile) -> Unit
) : DateGroupedAdapter<RecordingFile>(timestamp = { it.createdAt }) {

    fun submitFiles(files: List<RecordingFile>) = submit(files)

    override fun rowName(item: RecordingFile): String = item.displayName

    override fun onRowTapped(item: RecordingFile) = onFileTapped(item)

    override fun rowMeta(context: android.content.Context, item: RecordingFile): String {
        val upload = when {
            item.uploaded -> context.getString(R.string.uploaded)
            item.isSynced -> context.getString(R.string.upload_pending)
            else -> context.getString(R.string.on_device)
        }
        return DateGrouping.formatDateTime(item.createdAt) + DateGrouping.SEPARATOR +
            DateGrouping.formatDuration(item.duration) + DateGrouping.SEPARATOR + upload
    }
}
