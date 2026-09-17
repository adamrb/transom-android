package io.github.adamrb.transom.ui.settings

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.adamrb.transom.TransomApp
import io.github.adamrb.transom.R
import io.github.adamrb.transom.common.AppLog
import io.github.adamrb.transom.common.Appearance
import io.github.adamrb.transom.common.ServerErrorText
import io.github.adamrb.transom.databinding.FragmentSettingsBinding
import io.github.adamrb.transom.net.ApiClient
import io.github.adamrb.transom.service.DeviceConnectionService
import io.github.adamrb.transom.storage.RecordingStore
import io.github.adamrb.transom.ui.common.ContentWidth
import io.github.adamrb.transom.ui.library.WebDashboardActivity
import io.github.adamrb.transom.ui.onboarding.WelcomeActivity

/**
 * Settings Tab: sync toggles, server config, Automations, firmware, version, unpair, and a
 * collapsed Advanced section for the plumbing (full web dashboard, Plaud region, user id,
 * diagnostic logs, build number). The recorder rows (firmware, unpair) only appear once a
 * recorder is paired; before that they would be inert.
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val TAG = "SettingsFragment"

        /** Version row text: the version name alone, plus the latest update-check result. */
        fun versionLabel(versionName: String, status: String?): String =
            if (status.isNullOrBlank()) versionName else "$versionName · $status"

        /**
         * "Your server" row text: the address plus whether an access token is stored. Never a
         * fragment of the token itself.
         */
        fun serverLabel(context: Context, url: String?, token: String?): String = when {
            url.isNullOrBlank() -> context.getString(R.string.not_configured)
            token.isNullOrBlank() -> "$url · ${context.getString(R.string.access_token_missing)}"
            else -> "$url · ${context.getString(R.string.access_token_set)}"
        }

        /** The Appearance row's value and the dialog's choices, in the user's words. */
        fun appearanceLabel(appearance: Appearance): Int = when (appearance) {
            Appearance.SYSTEM -> R.string.appearance_system
            Appearance.LIGHT -> R.string.appearance_light
            Appearance.DARK -> R.string.appearance_dark
        }
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireActivity().application as TransomApp
    private val deviceManager get() = app.deviceManager
    private val recordingManager get() = app.recordingManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ContentWidth.limit(binding.settingsContent)

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

        // Appearance: System (default), Light or Dark. Applying a new value recreates the
        // activity; the selected tab survives through MainActivity's saved state.
        renderAppearanceRow()
        binding.appearanceRow.setOnClickListener { showAppearanceDialog() }

        // Transcript and automation notifications each have a channel; the system screen is
        // where they are switched, so the row goes straight there.
        binding.notificationsRow.setOnClickListener {
            try {
                startActivity(io.github.adamrb.transom.common.AppNotifications.settingsIntent(requireContext()))
            } catch (e: android.content.ActivityNotFoundException) {
                com.google.android.material.snackbar.Snackbar
                    .make(binding.root, R.string.notifications_unavailable, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                    .show()
            }
        }

        // Bridge server (URL + auth token)
        renderServerCard()
        binding.editServerButton.setOnClickListener { showEditServerDialog() }

        // Names and terms the server's transcriber keeps mishearing; edited natively because
        // a phone keyboard in the dashboard's textarea inside a WebView is a poor experience.
        binding.vocabularyRow.setOnClickListener {
            startActivity(Intent(requireContext(), VocabularyActivity::class.java))
        }

        // Automations live in the server's web UI; open it straight on that tab so the row reads
        // as a feature, not as "a second recordings list". The full dashboard is under Advanced.
        binding.webDashboardRow.setOnClickListener {
            startActivity(
                Intent(requireContext(), WebDashboardActivity::class.java)
                    .putExtra(WebDashboardActivity.EXTRA_TAB, WebDashboardActivity.TAB_AUTOMATIONS)
            )
        }
        binding.webDashboardAdvancedRow.setOnClickListener {
            startActivity(Intent(requireContext(), WebDashboardActivity::class.java))
        }

        // Approve the dashboard's login QR with the phone so a computer's browser gets its own
        // session without the access token ever being typed or pasted there.
        binding.qrLoginRow.setOnClickListener {
            startActivity(Intent(requireContext(), QrLoginActivity::class.java))
        }

        // Advanced section: collapsed unless the user opened it before.
        renderAdvancedSection(RecordingStore.advancedSettingsExpanded)
        binding.advancedHeader.setOnClickListener {
            val expanded = !RecordingStore.advancedSettingsExpanded
            RecordingStore.advancedSettingsExpanded = expanded
            renderAdvancedSection(expanded)
        }

        // Plaud cloud region (SDK auth handshake only; restart to apply)
        renderRegionCard()
        binding.switchRegionButton.setOnClickListener { showSwitchRegionDialog() }

        // Per-install user id (sent to the bridge server as the Plaud client_user_id)
        binding.userIdLabel.text = RecordingStore.getOrCreateUserId()
        binding.userIdLabel.contentDescription = getString(R.string.user_id_tap_hint)
        binding.userIdLabel.setOnClickListener { showEditUserIdDialog() }
        binding.copyUserIdButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.user_id), RecordingStore.getOrCreateUserId())
            )
        }

        // Diagnostic logs: encrypted .plaud package → share sheet → clear logs
        binding.exportLogsButton.setOnClickListener { exportSdkLogs() }

        // App version + manual update check against the self-hosted server; the build number
        // is plumbing, so it sits under Advanced.
        renderVersionLabel(null)
        binding.buildNumberLabel.text = io.github.adamrb.transom.BuildConfig.VERSION_CODE.toString()
        binding.checkUpdateButton.setOnClickListener { checkForUpdates() }

        // Recorder rows: inert without a paired recorder, so hidden until there is one.
        renderRecorderRows()
        binding.signOutButton.setOnClickListener { showUnpairConfirmation() }

        observeDevice()
    }

    /** Firmware and Unpair only make sense once a recorder is paired. */
    private fun renderRecorderRows() {
        val paired = RecordingStore.pairedDeviceSNs.isNotEmpty()
        binding.firmwareCard.visibility = if (paired) View.VISIBLE else View.GONE
        binding.signOutButton.visibility = if (paired) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        // The user may have just returned from the system battery dialog.
        renderBatteryOptimizationRow()
    }

    /** Show or hide the Advanced rows; the chevron points down while they are open. */
    private fun renderAdvancedSection(expanded: Boolean) {
        binding.advancedContent.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.advancedChevron.rotation = if (expanded) 90f else 0f
    }

    // MARK: - Appearance

    private fun renderAppearanceRow() {
        binding.appearanceLabel.text = getString(appearanceLabel(RecordingStore.appearance))
    }

    private fun showAppearanceDialog() {
        val options = Appearance.values()
        val labels = options.map { getString(appearanceLabel(it)) }.toTypedArray()
        val current = options.indexOf(RecordingStore.appearance)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.appearance)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val chosen = options[which]
                if (chosen == RecordingStore.appearance) return@setSingleChoiceItems
                RecordingStore.appearance = chosen
                renderAppearanceRow()
                chosen.apply() // recreates the activity when the palette changes
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                    // Pairing can change underneath us (connect sheet, unpair elsewhere).
                    renderRecorderRows()
                    binding.firmwareVersionLabel.text =
                        device?.firmwareVersion?.takeIf { it.isNotBlank() } ?: getString(R.string.not_connected)

                    // Show the firmware update button
                    val hasUpdate = device?.latestFirmwareVersion != null &&
                            device.latestFirmwareVersion != device.firmwareVersion
                    binding.firmwareUpdateButton.visibility = if (hasUpdate) View.VISIBLE else View.GONE
                    binding.firmwareUpdateButton.setOnClickListener {
                        FirmwareUpdateSheet
                            .newInstance(device?.name?.ifBlank { null } ?: getString(R.string.default_recorder_name))
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

    /** "0.4.6" plus the latest check result, e.g. "0.4.6 · Up to date". */
    private fun renderVersionLabel(status: String?) {
        binding.appVersionLabel.text = versionLabel(io.github.adamrb.transom.BuildConfig.VERSION_NAME, status)
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
                io.github.adamrb.transom.net.UpdateManager.checkForUpdate(
                    io.github.adamrb.transom.BuildConfig.VERSION_CODE
                )
            }
            if (!isAdded) return@launch
            binding.checkUpdateButton.isEnabled = true
            when (result) {
                is io.github.adamrb.transom.net.UpdateManager.CheckResult.UpToDate ->
                    renderVersionLabel(getString(R.string.update_up_to_date))
                is io.github.adamrb.transom.net.UpdateManager.CheckResult.NotHosted ->
                    renderVersionLabel(getString(R.string.update_not_hosted))
                is io.github.adamrb.transom.net.UpdateManager.CheckResult.Error -> {
                    AppLog.w(TAG, "update check failed: ${result.message}")
                    renderVersionLabel(getString(R.string.update_check_failed))
                }
                is io.github.adamrb.transom.net.UpdateManager.CheckResult.UpdateAvailable -> {
                    renderVersionLabel(
                        getString(R.string.update_available_short_fmt, result.manifest.versionName)
                    )
                    io.github.adamrb.transom.ui.update.AppUpdateFlow.promptInstall(
                        requireActivity(), result.manifest
                    )
                }
            }
        }
    }

    // MARK: - Bridge server settings

    private fun renderServerCard() {
        binding.serverInfoLabel.text =
            serverLabel(requireContext(), RecordingStore.serverBaseUrl, RecordingStore.serverAuthToken)
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
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // The error is already a user sentence (unreachable, token refused, unexpected
            // answer); codes and exception text stay in the log.
            val error = withContext(Dispatchers.IO) {
                try {
                    if (!ApiClient.checkHealth(finalUrl)) return@withContext ctx.getString(R.string.server_setup_health_failed)
                    val userId = RecordingStore.getOrCreateUserId()
                    val plaudToken = ApiClient.fetchUserToken(finalUrl, token, userId)
                    RecordingStore.serverBaseUrl = finalUrl
                    RecordingStore.serverAuthToken = token
                    // store() refuses the token if the user adopted another id meanwhile; then the
                    // SDK must not be handed it either (the next refresh mints one for the new id).
                    val stored = io.github.adamrb.transom.net.TokenManager.store(plaudToken, forUserId = userId)
                    if (hostChanged) RecordingStore.clearServerState()
                    if (stored) try { sdk.NiceBuildSdk.setPartnerToken(plaudToken.accessToken) } catch (_: Exception) { }
                    null
                } catch (e: Exception) {
                    AppLog.w(TAG, "server verification failed", e)
                    ServerErrorText.forServerSetup(ctx, e)
                }
            }
            if (!isAdded) return@launch
            renderServerCard()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.bridge_server)
                .setMessage(
                    when {
                        error != null -> error
                        hostChanged -> getString(R.string.server_saved) + "\n\n" + getString(R.string.server_changed_note)
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
    /**
     * Let the user adopt a previous install's user id (see RecordingStore.adoptUserId): the
     * recorder's binding follows that id, so a phone move or the package rename needs no unpair.
     */
    private fun showEditUserIdDialog() {
        val input = EditText(requireContext()).apply {
            setText(RecordingStore.getOrCreateUserId())
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.user_id_edit_title)
            .setMessage(R.string.user_id_edit_help)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val value = input.text.toString().trim()
                if (value == RecordingStore.getOrCreateUserId()) return@setPositiveButton
                if (!RecordingStore.isValidUserId(value)) {
                    android.widget.Toast.makeText(requireContext(), R.string.user_id_invalid, android.widget.Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                // The SDK was initialised with the old id and the recorder link may be live under
                // it, so the process ends right here (the dialog text says so); a second dialog
                // could be lost to a rotation and leave the app running on mixed identities.
                // Exiting also covers a failed commit: disk still holds the old id, the in-memory
                // half-state dies with the process, and the user sees the unchanged id on relaunch.
                RecordingStore.adoptUserId(value)
                requireActivity().finishAffinity()
                kotlin.system.exitProcess(0)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

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
