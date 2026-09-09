package org.plaudbridge.app.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.R as MaterialR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.plaudbridge.app.BuildConfig
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.R
import org.plaudbridge.app.databinding.ActivityMainBinding
import org.plaudbridge.app.managers.UploadManager
import org.plaudbridge.app.models.DeviceConnectionState
import org.plaudbridge.app.models.SyncState
import org.plaudbridge.app.net.UpdateManager
import org.plaudbridge.app.service.DeviceConnectionService
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.common.AppManagers
import org.plaudbridge.app.ui.common.ContentWidth
import org.plaudbridge.app.ui.common.SnackbarHost
import org.plaudbridge.app.ui.common.SyncFeedback
import org.plaudbridge.app.ui.common.themeColor
import org.plaudbridge.app.ui.home.HomeFragment
import org.plaudbridge.app.ui.onboarding.ScanningActivity
import org.plaudbridge.app.ui.onboarding.WelcomeActivity
import org.plaudbridge.app.ui.recordings.RecordingsFragment
import org.plaudbridge.app.ui.settings.SettingsFragment
import org.plaudbridge.app.ui.update.AppUpdateFlow

/**
 * Main screen: three tabs (Home, Recordings, Settings) over an opaque bottom tab bar.
 *
 * Back from Recordings or Settings returns to Home; back from Home leaves the app, the way
 * every other tabbed Android app behaves. The activity is also the one place snackbars come
 * from ([SnackbarHost]), anchored above the tab bar, and the one observer that turns a failed
 * recorder sync into a message with Retry, whichever tab is showing.
 */
class MainActivity : AppCompatActivity(), SnackbarHost {

    private lateinit var binding: ActivityMainBinding

    // Created in setupFragments: fresh instances on first launch, re-attached by tag after
    // process/config recreation (the FragmentManager restores its own instances — adding new
    // ones on top would duplicate every tab).
    private lateinit var homeFragment: Fragment
    private lateinit var recordingsFragment: Fragment
    private lateinit var settingsFragment: Fragment
    private var activeFragment: Fragment? = null

    private var selectedTab = TAB_HOME

    private val app get() = application as PlaudBridgeApp
    private val deviceManager get() = AppManagers.device(app)
    private val syncManager get() = AppManagers.sync(app)

    /** Enabled only away from Home, so Home's back still leaves the app. */
    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = selectTab(TAB_HOME)
    }

    /** The last failure a snackbar was shown for; a replayed StateFlow value must not repeat it. */
    private var notifiedFailure: SyncState.Failed? = null

    /** What the last snackbar said and offered (tests read these; production ignores them). */
    @VisibleForTesting
    var lastSnackbarMessage: String? = null
        private set

    @VisibleForTesting
    var lastSnackbarAction: (() -> Unit)? = null
        private set

    @VisibleForTesting
    fun clearLastSnackbarForTests() {
        lastSnackbarMessage = null
        lastSnackbarAction = null
    }

    /**
     * API 33+ POST_NOTIFICATIONS. Granted or denied, the connection service starts either way: a
     * foreground service without the permission is allowed, the user simply sees no notification.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            DeviceConnectionService.sync(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If no device has been connected before, go back to Welcome — unless the user chose
        // "Connect device later" on Welcome (device can be added afterwards).
        if (RecordingStore.lastConnectedDeviceSN == null && !RecordingStore.hasSkippedOnboarding) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFragments(savedInstanceState)
        setupTabBar()
        onBackPressedDispatcher.addCallback(this, backToHome)
        selectTab((savedInstanceState?.getInt(KEY_SELECTED_TAB, TAB_HOME) ?: TAB_HOME).coerceIn(TAB_HOME, TAB_SETTINGS), force = true)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Cloud binding alerts (e.g. device bound to another account)
                launch {
                    deviceManager.cloudAlerts.collect { message ->
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.recorder_notice_title)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
                // A recorder sync that did not finish: say so once, with a way forward.
                launch {
                    syncManager.state.collect { state ->
                        if (state is SyncState.Failed) onSyncFailed(state)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Entering the foreground -> if not connected and there is a previously bound device, trigger background auto-reconnect after a delay
        // (the delay waits for the Bluetooth stack to power on, matching the 2s delay in iOS sceneDidBecomeActive)
        if (deviceManager.connectionState.value !is DeviceConnectionState.Connected &&
            RecordingStore.lastConnectedDeviceSN != null
        ) {
            binding.root.postDelayed({ deviceManager.attemptReconnect() }, 2_000)
        }

        // We are in the foreground, so a foreground-service start is allowed here even on API 31+
        // (the process-start attempt in PlaudBridgeApp may have been refused). Ask for the
        // notification permission first, once, so the very first start can show its notification.
        ensureBackgroundSyncService()

        // Continue an app update that was waiting on the unknown-sources permission, then
        // (at most once per 24h) check the server for a newer hosted APK.
        AppUpdateFlow.resumePendingInstall(this)
        maybeAutoCheckForUpdate()
    }

    private fun ensureBackgroundSyncService() {
        // The permission covers the connection notification AND the transcript/automation
        // notifications, so it is asked for as soon as either has a reason to exist: a paired
        // recorder or a configured server.
        val needsPrompt = Build.VERSION.SDK_INT >= 33 &&
            !RecordingStore.notificationPermissionAsked &&
            (DeviceConnectionService.isEligible() || RecordingStore.isServerConfigured) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (!DeviceConnectionService.isEligible()) {
            DeviceConnectionService.sync(this) // stops a stale instance, no-op otherwise
            if (needsPrompt) {
                RecordingStore.notificationPermissionAsked = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        if (needsPrompt) {
            RecordingStore.notificationPermissionAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            DeviceConnectionService.sync(this)
        }
    }

    /**
     * Foreground auto-check against the server's hosted APK. Throttle: 24h after a successful
     * response, 1h after a transient failure; reset when the server host or the config
     * generation changes; timestamps are recorded AFTER the response (never before), and an
     * in-flight guard prevents concurrent checks across rapid resume cycles.
     */
    private fun maybeAutoCheckForUpdate() {
        if (!RecordingStore.isServerConfigured) return
        val now = System.currentTimeMillis()
        val host = RecordingStore.serverBaseUrl?.let { android.net.Uri.parse(it).host }
        val gen = RecordingStore.serverConfigGeneration
        val configChanged = gen != UpdateManager.lastCheckedConfigGeneration
        val due = configChanged || UpdateManager.isAutoCheckDue(
            RecordingStore.lastUpdateCheckAt,
            RecordingStore.lastUpdateCheckFailureAt,
            now,
            RecordingStore.lastUpdateCheckHost,
            host
        )
        if (!due || !UpdateManager.tryBeginAutoCheck()) return
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    UpdateManager.checkForUpdate(BuildConfig.VERSION_CODE)
                }
                UpdateManager.lastCheckedConfigGeneration = gen
                RecordingStore.lastUpdateCheckHost = host
                if (result is UpdateManager.CheckResult.Error) {
                    RecordingStore.lastUpdateCheckFailureAt = System.currentTimeMillis()
                } else {
                    RecordingStore.lastUpdateCheckAt = System.currentTimeMillis()
                    RecordingStore.lastUpdateCheckFailureAt = 0L
                }
                if (result is UpdateManager.CheckResult.UpdateAvailable && !isFinishing && !isDestroyed) {
                    AppUpdateFlow.promptInstall(this@MainActivity, result.manifest)
                }
            } finally {
                UpdateManager.endAutoCheck()
            }
        }
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        if (savedInstanceState == null) {
            homeFragment = HomeFragment()
            recordingsFragment = RecordingsFragment()
            settingsFragment = SettingsFragment()
            fm.beginTransaction()
                .add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
                .add(R.id.fragmentContainer, recordingsFragment, "recordings").hide(recordingsFragment)
                .add(R.id.fragmentContainer, homeFragment, "home")
                .commit()
        } else {
            // Recreation: reuse the FragmentManager's restored instances.
            homeFragment = fm.findFragmentByTag("home") ?: HomeFragment()
            recordingsFragment = fm.findFragmentByTag("recordings") ?: RecordingsFragment()
            settingsFragment = fm.findFragmentByTag("settings") ?: SettingsFragment()
        }
        activeFragment = null // selectTab(force = true) sets visibility + activeFragment
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, selectedTab)
    }

    private fun setupTabBar() {
        // The bar sits flush with the bottom edge; the system bar inset becomes bottom padding so
        // the labels stay above the gesture area.
        val basePadding = binding.tabBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.tabBar) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }
        // Wide screens: the three tabs stay a hand's width apart instead of spanning the display
        ContentWidth.limit(binding.tabBar)

        binding.tabHome.setOnClickListener { selectTab(TAB_HOME) }
        binding.tabRecordings.setOnClickListener { selectTab(TAB_RECORDINGS) }
        binding.tabSettings.setOnClickListener { selectTab(TAB_SETTINGS) }
    }

    /** The tab showing right now: [TAB_HOME], [TAB_RECORDINGS] or [TAB_SETTINGS]. */
    val currentTab: Int get() = selectedTab

    fun selectTab(index: Int, force: Boolean = false) {
        if (!force && selectedTab == index && activeFragment != null) return
        selectedTab = index

        val target = when (index) {
            TAB_HOME -> homeFragment
            TAB_RECORDINGS -> recordingsFragment
            TAB_SETTINGS -> settingsFragment
            else -> homeFragment
        }

        val tx = supportFragmentManager.beginTransaction()
        listOf(homeFragment, recordingsFragment, settingsFragment).forEach { f ->
            if (f !== target && f.isAdded) tx.hide(f)
        }
        if (target.isAdded) tx.show(target)
        tx.commit()
        activeFragment = target

        backToHome.isEnabled = index != TAB_HOME
        updateTabAppearance()
    }

    private fun updateTabAppearance() {
        val tabs = listOf(binding.tabHome, binding.tabRecordings, binding.tabSettings)
        val icons = listOf(binding.tabHomeIcon, binding.tabRecordingsIcon, binding.tabSettingsIcon)
        val labels = listOf(binding.tabHomeLabel, binding.tabRecordingsLabel, binding.tabSettingsLabel)

        for (i in tabs.indices) {
            val isSelected = i == selectedTab
            tabs[i].background = if (isSelected) {
                ContextCompat.getDrawable(this, R.drawable.bg_tab_selected)
            } else {
                null
            }
            tabs[i].isSelected = isSelected
            // Selected = the surface's text colour, unselected = the secondary text colour
            val tint = themeColor(if (isSelected) MaterialR.attr.colorOnSurface else MaterialR.attr.colorOnSurfaceVariant)
            icons[i].setColorFilter(tint)
            labels[i].setTextColor(tint)
        }
    }

    // MARK: - Feedback

    override fun showSnackbar(message: String, actionLabel: String?, action: (() -> Unit)?) {
        lastSnackbarMessage = message
        lastSnackbarAction = action
        val duration = if (action != null) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        Snackbar.make(binding.root, message, duration).apply {
            anchorView = binding.tabBar
            if (actionLabel != null && action != null) setAction(actionLabel) { action() }
        }.show()
    }

    /**
     * A sync that did not finish: one snackbar per failure. Stale failures (replayed by the
     * StateFlow when this screen subscribes again after a recreation) are skipped; a fresh one
     * gets Retry (of the same kind of transfer that failed: Fast Transfer again for a WiFi
     * failure, an ordinary sync otherwise), or Connect when no recorder was connected.
     */
    private fun onSyncFailed(failed: SyncState.Failed) {
        if (failed === notifiedFailure || !SyncFeedback.isFresh(failed)) return
        notifiedFailure = failed
        val message = getString(SyncFeedback.messageRes(failed))
        if (SyncFeedback.offersRetry(failed)) {
            showSnackbar(message, getString(R.string.retry)) {
                if (failed.reason == SyncState.Reason.WIFI) {
                    syncManager.startWiFiTransfer()
                } else {
                    syncManager.startSync()
                    UploadManager.kick()
                }
            }
        } else {
            showSnackbar(message, getString(R.string.connect)) {
                startActivity(Intent(this, ScanningActivity::class.java).putExtra(ScanningActivity.EXTRA_ADDING_DEVICE, true))
            }
        }
    }

    companion object {
        private const val KEY_SELECTED_TAB = "selected_tab"

        const val TAB_HOME = 0
        const val TAB_RECORDINGS = 1
        const val TAB_SETTINGS = 2
    }
}
