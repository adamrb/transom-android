package org.plaudbridge.app.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.plaudbridge.app.BuildConfig
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.R
import org.plaudbridge.app.databinding.ActivityMainBinding
import org.plaudbridge.app.models.DeviceConnectionState
import org.plaudbridge.app.net.UpdateManager
import org.plaudbridge.app.service.DeviceConnectionService
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.files.FilesFragment
import org.plaudbridge.app.ui.home.HomeFragment
import org.plaudbridge.app.ui.library.LibraryFragment
import org.plaudbridge.app.ui.onboarding.WelcomeActivity
import org.plaudbridge.app.ui.settings.SettingsFragment
import org.plaudbridge.app.ui.update.AppUpdateFlow

/**
 * Main screen — bottom floating Tab Bar + 4 Fragments (Home, Files, Library, Settings)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Created in setupFragments: fresh instances on first launch, re-attached by tag after
    // process/config recreation (the FragmentManager restores its own instances — adding new
    // ones on top would duplicate every tab).
    private lateinit var homeFragment: Fragment
    private lateinit var filesFragment: Fragment
    private lateinit var libraryFragment: Fragment
    private lateinit var settingsFragment: Fragment
    private var activeFragment: Fragment? = null

    private var selectedTab = 0

    private val deviceManager get() = (application as PlaudBridgeApp).deviceManager

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
        selectTab(savedInstanceState?.getInt(KEY_SELECTED_TAB, 0) ?: 0, force = true)

        // Cloud binding alerts (e.g. device bound to another account)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                deviceManager.cloudAlerts.collect { message ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Device Binding")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
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
        if (!DeviceConnectionService.isEligible()) {
            DeviceConnectionService.sync(this) // stops a stale instance, no-op otherwise
            return
        }
        val needsPrompt = Build.VERSION.SDK_INT >= 33 &&
            !RecordingStore.notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
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
            filesFragment = FilesFragment()
            libraryFragment = LibraryFragment()
            settingsFragment = SettingsFragment()
            fm.beginTransaction()
                .add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
                .add(R.id.fragmentContainer, libraryFragment, "library").hide(libraryFragment)
                .add(R.id.fragmentContainer, filesFragment, "files").hide(filesFragment)
                .add(R.id.fragmentContainer, homeFragment, "home")
                .commit()
        } else {
            // Recreation: reuse the FragmentManager's restored instances.
            homeFragment = fm.findFragmentByTag("home") ?: HomeFragment()
            filesFragment = fm.findFragmentByTag("files") ?: FilesFragment()
            libraryFragment = fm.findFragmentByTag("library") ?: LibraryFragment()
            settingsFragment = fm.findFragmentByTag("settings") ?: SettingsFragment()
        }
        activeFragment = null // selectTab(force = true) sets visibility + activeFragment
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, selectedTab)
    }

    private fun setupTabBar() {
        // Pin the floating bar to safe-area bottom + 8dp (mirrors iOS)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.tabBar) { v, insets ->
            val bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            (v.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin =
                bottom + (8 * v.resources.displayMetrics.density).toInt()
            v.requestLayout()
            insets
        }

        binding.tabHome.setOnClickListener { selectTab(0) }
        binding.tabFiles.setOnClickListener { selectTab(1) }
        binding.tabLibrary.setOnClickListener { selectTab(2) }
        binding.tabSettings.setOnClickListener { selectTab(3) }
    }

    private fun selectTab(index: Int, force: Boolean = false) {
        if (!force && selectedTab == index && activeFragment != null) return
        selectedTab = index

        val target = when (index) {
            0 -> homeFragment
            1 -> filesFragment
            2 -> libraryFragment
            3 -> settingsFragment
            else -> homeFragment
        }

        val tx = supportFragmentManager.beginTransaction()
        listOf(homeFragment, filesFragment, libraryFragment, settingsFragment).forEach { f ->
            if (f !== target && f.isAdded) tx.hide(f)
        }
        if (target.isAdded) tx.show(target)
        tx.commit()
        activeFragment = target

        updateTabAppearance()
    }

    private fun updateTabAppearance() {
        val tabs = listOf(binding.tabHome, binding.tabFiles, binding.tabLibrary, binding.tabSettings)
        val icons = listOf(binding.tabHomeIcon, binding.tabFilesIcon, binding.tabLibraryIcon, binding.tabSettingsIcon)
        val labels = listOf(binding.tabHomeLabel, binding.tabFilesLabel, binding.tabLibraryLabel, binding.tabSettingsLabel)

        for (i in tabs.indices) {
            val isSelected = i == selectedTab
            tabs[i].background = if (isSelected) {
                ContextCompat.getDrawable(this, R.drawable.bg_tab_selected)
            } else {
                null
            }
            // Selected = black icon+label, unselected = #7A7A7A (mirrors iOS)
            val tint = ContextCompat.getColor(this, if (isSelected) R.color.black else R.color.tab_unselected)
            icons[i].setColorFilter(tint)
            labels[i].setTextColor(tint)
        }
    }

    companion object {
        private const val KEY_SELECTED_TAB = "selected_tab"
    }
}
