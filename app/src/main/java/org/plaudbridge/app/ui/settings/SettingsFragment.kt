package org.plaudbridge.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.R
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.databinding.FragmentSettingsBinding
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.service.DeviceConnectionService
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.onboarding.WelcomeActivity

/**
 * Settings Tab — sync toggles, bridge-server config, Plaud region, user id, firmware, unpair.
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val TAG = "SettingsFragment"
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireActivity().application as PlaudBridgeApp
    private val deviceManager get() = app.deviceManager
    private val recordingManager get() = app.recordingManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Re-check firmware on open (mirrors iOS setupBindings), covering late/failed on-connect checks
        deviceManager.refreshFirmwareCheck()

        // Auto Sync toggle
        binding.autoSyncToggle.isChecked = RecordingStore.isAutoSyncEnabled
        binding.autoSyncToggle.onToggleChanged = { isChecked ->
            RecordingStore.isAutoSyncEnabled = isChecked
            deviceManager.setAutoSync(isChecked)
        }

        // Background sync toggle (default ON): runs the DeviceConnectionService foreground service
        // so the BLE link survives Doze. sync() starts or stops it according to the new value.
        binding.backgroundSyncToggle.isChecked = RecordingStore.isBackgroundSyncEnabled
        binding.backgroundSyncToggle.onToggleChanged = { isChecked ->
            RecordingStore.isBackgroundSyncEnabled = isChecked
            DeviceConnectionService.sync(requireContext())
        }

        // Battery optimization: Doze still throttles a foreground service's timers unless the app
        // is exempt, so surface the state and hand off to the system dialog. API 23+ only.
        if (Build.VERSION.SDK_INT >= 23) {
            binding.batteryOptimizationRow.setOnClickListener { requestBatteryExemption() }
        } else {
            binding.batteryOptimizationRow.visibility = View.GONE
        }

        // Delete-after-upload toggle (default OFF): remove the recording from the device only
        // after the bridge server has confirmed the upload.
        binding.deleteAfterUploadToggle.isChecked = RecordingStore.deleteAfterUpload
        binding.deleteAfterUploadToggle.onToggleChanged = { isChecked ->
            RecordingStore.deleteAfterUpload = isChecked
        }

        // Bridge server (URL + auth token)
        renderServerCard()
        binding.editServerButton.setOnClickListener { showEditServerDialog() }

        // Plaud cloud region (SDK auth handshake only; restart to apply)
        renderRegionCard()
        binding.switchRegionButton.setOnClickListener { showSwitchRegionDialog() }

        // Per-install user id (sent to the bridge server as the Plaud client_user_id)
        binding.userIdLabel.text = RecordingStore.getOrCreateUserId()
        binding.copyUserIdButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("User ID", RecordingStore.getOrCreateUserId()))
        }

        // SDK Logs export: encrypted .plaud package → share sheet → clear logs
        binding.exportLogsButton.setOnClickListener { exportSdkLogs() }

        // App version + manual update check against the self-hosted server
        renderVersionLabel(null)
        binding.checkUpdateButton.setOnClickListener { checkForUpdates() }

        // Unpair
        binding.signOutButton.setOnClickListener { showUnpairConfirmation() }

        observeDevice()
    }

    override fun onResume() {
        super.onResume()
        // The user may have just returned from the system battery dialog.
        renderBatteryOptimizationRow()
    }

    // MARK: - Battery optimization

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    private fun renderBatteryOptimizationRow() {
        if (_binding == null || Build.VERSION.SDK_INT < 23) return
        val exempt = isIgnoringBatteryOptimizations()
        binding.batteryOptimizationLabel.text = getString(
            if (exempt) R.string.battery_optimization_exempt else R.string.battery_optimization_restricted
        )
        binding.batteryOptimizationChevron.visibility = if (exempt) View.INVISIBLE else View.VISIBLE
    }

    /**
     * Ask the system to exempt this package from battery optimizations. The direct request dialog
     * is preferred (one tap); some OEM builds do not resolve it, so fall back to the full list
     * screen where the user finds the app manually.
     */
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        if (isIgnoringBatteryOptimizations()) {
            renderBatteryOptimizationRow()
            return
        }
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${requireContext().packageName}")
        }
        try {
            startActivity(direct)
            return
        } catch (e: Exception) {
            AppLog.w(TAG, "direct battery-exemption request failed, falling back to settings list", e)
        }
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            AppLog.w(TAG, "battery optimization settings screen unavailable", e)
            binding.batteryOptimizationLabel.text = getString(R.string.battery_optimization_unavailable)
        }
    }

    private fun observeDevice() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                deviceManager.connectedDevice.collect { device ->
                    binding.firmwareVersionLabel.text = device?.firmwareVersion?.let { "Version $it" } ?: "--"

                    // Show the firmware update button
                    val hasUpdate = device?.latestFirmwareVersion != null &&
                            device.latestFirmwareVersion != device.firmwareVersion
                    binding.firmwareUpdateButton.visibility = if (hasUpdate) View.VISIBLE else View.GONE
                    binding.firmwareUpdateButton.setOnClickListener {
                        FirmwareUpdateSheet
                            .newInstance(device?.name ?: "Plaud Device")
                            .show(childFragmentManager, "FirmwareUpdateSheet")
                    }
                }
            }
        }
    }

    /**
     * Export the encrypted SDK log package (.plaud) and hand it to the system share sheet, then
     * clear the on-device logs so each export contains only fresh content.
     */
    private fun exportSdkLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val file = withContext(Dispatchers.IO) {
                try { sdk.NiceBuildSdk.exportLog(ctx) } catch (e: Exception) { null }
            }
            if (file == null || !file.exists()) {
                AlertDialog.Builder(requireContext())
                    .setMessage(R.string.export_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.sdk_logs)))
            withContext(Dispatchers.IO) {
                try { sdk.NiceBuildSdk.cleanupLogs(ctx) } catch (_: Exception) { }
            }
        }
    }

    // MARK: - App update (APK hosted on the bridge server)

    /** "0.2.0 (2)" plus the latest check result, e.g. "0.2.0 (2) · Up to date". */
    private fun renderVersionLabel(status: String?) {
        val version =
            "${org.plaudbridge.app.BuildConfig.VERSION_NAME} (${org.plaudbridge.app.BuildConfig.VERSION_CODE})"
        binding.appVersionLabel.text = if (status.isNullOrBlank()) version else "$version · $status"
    }

    private fun checkForUpdates() {
        if (!RecordingStore.isServerConfigured) {
            renderVersionLabel(getString(R.string.not_configured))
            return
        }
        binding.checkUpdateButton.isEnabled = false
        renderVersionLabel(getString(R.string.update_checking))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                org.plaudbridge.app.net.UpdateManager.checkForUpdate(
                    org.plaudbridge.app.BuildConfig.VERSION_CODE
                )
            }
            if (!isAdded) return@launch
            binding.checkUpdateButton.isEnabled = true
            when (result) {
                is org.plaudbridge.app.net.UpdateManager.CheckResult.UpToDate ->
                    renderVersionLabel(getString(R.string.update_up_to_date))
                is org.plaudbridge.app.net.UpdateManager.CheckResult.NotHosted ->
                    renderVersionLabel(getString(R.string.update_not_hosted))
                is org.plaudbridge.app.net.UpdateManager.CheckResult.Error ->
                    renderVersionLabel(getString(R.string.update_check_failed_fmt, result.message))
                is org.plaudbridge.app.net.UpdateManager.CheckResult.UpdateAvailable -> {
                    renderVersionLabel(
                        getString(R.string.update_available_short_fmt, result.manifest.versionName)
                    )
                    org.plaudbridge.app.ui.update.AppUpdateFlow.promptInstall(
                        requireActivity(), result.manifest
                    )
                }
            }
        }
    }

    // MARK: - Bridge server settings

    private fun renderServerCard() {
        val url = RecordingStore.serverBaseUrl
        val token = RecordingStore.serverAuthToken
        binding.serverInfoLabel.text = when {
            url.isNullOrBlank() -> getString(R.string.not_configured)
            else -> "$url · ${maskToken(token)}"
        }
    }

    private fun maskToken(token: String?): String = when {
        token.isNullOrBlank() -> "no token"
        token.length <= 8 -> "••••"
        else -> "${token.take(4)}…${token.takeLast(4)}"
    }

    /** Edit the server URL + auth token; verified against the server before saving. */
    private fun showEditServerDialog() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val urlInput = EditText(ctx).apply {
            hint = getString(R.string.server_url_hint)
            setText(RecordingStore.serverBaseUrl ?: "")
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        val tokenInput = EditText(ctx).apply {
            hint = getString(R.string.server_auth_token_hint)
            setText(RecordingStore.serverAuthToken ?: "")
            // Secret field: password transformation + no autofill/suggestions.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            maxLines = 1
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(urlInput)
            addView(tokenInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.bridge_server)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                verifyAndSaveServer(
                    urlInput.text.toString().trim(),
                    tokenInput.text.toString().trim()
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun verifyAndSaveServer(rawUrl: String, token: String) {
        var url = rawUrl.trimEnd('/')
        if (url.isBlank() || token.isBlank()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        // HTTPS only: the default network security config blocks cleartext anyway, so an http://
        // URL would just fail later with an opaque network error (see README for LAN setups).
        if (!url.startsWith("https://")) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.bridge_server)
                .setMessage(R.string.server_setup_https_required)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val finalUrl = url
        // Pointing the app at a DIFFERENT server invalidates every cached server id: recordings
        // marked uploaded / transcript ids belong to the old server. Clear that state so files
        // re-upload (the server deduplicates) instead of silently querying foreign ids.
        val oldHost = RecordingStore.serverBaseUrl?.let { android.net.Uri.parse(it).host }
        val newHost = android.net.Uri.parse(finalUrl).host
        val hostChanged = oldHost != null && newHost != null && oldHost != newHost
        viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    if (!ApiClient.checkHealth(finalUrl)) return@withContext getString(R.string.server_setup_health_failed)
                    val plaudToken = ApiClient.fetchUserToken(
                        finalUrl, token, RecordingStore.getOrCreateUserId()
                    )
                    RecordingStore.serverBaseUrl = finalUrl
                    RecordingStore.serverAuthToken = token
                    org.plaudbridge.app.net.TokenManager.store(plaudToken)
                    if (hostChanged) RecordingStore.clearServerState()
                    try { sdk.NiceBuildSdk.setPartnerToken(plaudToken.accessToken) } catch (_: Exception) { }
                    null
                } catch (e: Exception) {
                    e.message ?: "connection failed"
                }
            }
            if (!isAdded) return@launch
            renderServerCard()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.bridge_server)
                .setMessage(
                    when {
                        error != null -> getString(R.string.server_setup_error_fmt, error)
                        hostChanged ->
                            getString(R.string.server_saved) +
                                "\n\nServer changed: upload state and transcripts were reset — " +
                                "synced recordings will re-upload to the new server."
                        else -> getString(R.string.server_saved)
                    }
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    // MARK: - Plaud cloud region

    private fun renderRegionCard() {
        val domain = RecordingStore.plaudDomain
        val label = RecordingStore.plaudDomains.firstOrNull { it.second == domain }?.first ?: "Custom"
        binding.plaudRegionLabel.text = "$label · $domain"
    }

    /**
     * Switch the Plaud platform region (SDK customDomain / partner API host — used ONLY for the
     * device auth handshake). The SDK initializes once at app start, so restart to apply.
     */
    private fun showSwitchRegionDialog() {
        val domains = RecordingStore.plaudDomains.map { it.second }.toTypedArray()
        val labels = RecordingStore.plaudDomains
            .map { (label, domain) -> "$label ($domain)" }
            .toTypedArray()
        val current = domains.indexOf(RecordingStore.plaudDomain).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.plaud_region)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val selected = domains[which]
                if (selected == RecordingStore.plaudDomain) return@setSingleChoiceItems
                RecordingStore.plaudDomain = selected
                renderRegionCard()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.plaud_region)
                    .setMessage(getString(R.string.plaud_region_restart_fmt, selected))
                    .setCancelable(false)
                    .setPositiveButton(R.string.exit_now) { _, _ ->
                        requireActivity().finishAffinity()
                        kotlin.system.exitProcess(0)
                    }
                    .setNegativeButton(R.string.later, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showUnpairConfirmation() {
        // Unpairing strands an in-progress recording — refuse while recording.
        if (recordingManager.state.value.isActive) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.device_recording_title)
                .setMessage(R.string.device_recording_blocks_unpair)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.unpair_device)
            .setMessage(R.string.confirm_unpair)
            .setPositiveButton(R.string.confirm) { _, _ ->
                // Unpair the CURRENT device only: other paired devices and synced
                // recordings survive. unpair() falls the active SN back to the next device.
                deviceManager.unpair()
                // No device left means nothing to reconnect: stop the background-sync service.
                DeviceConnectionService.sync(requireContext())
                if (RecordingStore.pairedDeviceSNs.isEmpty()) {
                    navigateToWelcome()
                } else {
                    requireActivity().recreate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun navigateToWelcome() {
        // Unpairing the last device returns to onboarding — drop the "connect later" shortcut too.
        RecordingStore.hasSkippedOnboarding = false
        val intent = Intent(requireContext(), WelcomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        activity?.finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
