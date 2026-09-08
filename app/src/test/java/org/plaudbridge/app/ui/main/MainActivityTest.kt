package org.plaudbridge.app.ui.main

import android.content.Context
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.R
import org.plaudbridge.app.models.SyncState
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.common.AppManagers
import org.plaudbridge.app.ui.common.FakeDeviceManager
import org.plaudbridge.app.ui.common.FakeSyncManager
import org.plaudbridge.app.ui.recordings.RecordingsRepository
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * MainActivity: back from Recordings or Settings returns to Home and only Home's back leaves
 * the app; tab labels come from string resources; a fresh sync failure is one snackbar worded
 * for the user (with Retry, or Connect when no recorder was connected), and a stale one
 * replayed by the StateFlow is not repeated.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private lateinit var context: Context
    private lateinit var sync: FakeSyncManager
    private lateinit var device: FakeDeviceManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        android.provider.Settings.Global.putFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f
        )
        RecordingStore.init(context)
        RecordingStore.clearAll()
        RecordingStore.hasSkippedOnboarding = true // Main opens without a paired recorder
        RecordingsRepository.reset()
        sync = FakeSyncManager()
        device = FakeDeviceManager()
        AppManagers.syncOverride = sync
        AppManagers.deviceOverride = device
    }

    @After
    fun tearDown() {
        AppManagers.reset()
    }

    private fun launch(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        return activity
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun backReturnsToHomeFromOtherTabsAndLeavesFromHome() {
        val activity = launch()
        assertEquals(MainActivity.TAB_HOME, activity.currentTab)

        activity.selectTab(MainActivity.TAB_RECORDINGS)
        idle()
        assertEquals(MainActivity.TAB_RECORDINGS, activity.currentTab)
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(MainActivity.TAB_HOME, activity.currentTab)
        assertFalse(activity.isFinishing)

        activity.selectTab(MainActivity.TAB_SETTINGS)
        idle()
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(MainActivity.TAB_HOME, activity.currentTab)
        assertFalse(activity.isFinishing)

        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun tabLabelsComeFromStringResources() {
        val activity = launch()
        assertEquals(context.getString(R.string.home), activity.findViewById<TextView>(R.id.tabHomeLabel).text.toString())
        assertEquals(context.getString(R.string.recordings), activity.findViewById<TextView>(R.id.tabRecordingsLabel).text.toString())
        assertEquals(context.getString(R.string.settings), activity.findViewById<TextView>(R.id.tabSettingsLabel).text.toString())
    }

    @Test
    fun aFreshSyncFailureIsOneSnackbarInUserWords() {
        val activity = launch()
        assertNull(activity.lastSnackbarMessage)

        sync.state.value = SyncState.Failed("Recorder stopped responding", SyncState.Reason.TIMED_OUT)
        idle()
        assertEquals(context.getString(R.string.sync_failed_timeout), activity.lastSnackbarMessage)

        // The same failure object staying in the flow is not announced twice.
        activity.clearLastSnackbarForTests()
        sync.state.value = SyncState.Idle
        idle()
        assertNull(activity.lastSnackbarMessage)

        // A second, distinct failure with the same wording is.
        sync.state.value = SyncState.Failed("Recorder stopped responding", SyncState.Reason.TIMED_OUT)
        idle()
        assertEquals(context.getString(R.string.sync_failed_timeout), activity.lastSnackbarMessage)
    }

    @Test
    fun noRecorderFailureSaysToConnectFirst() {
        val activity = launch()
        sync.state.value = SyncState.Failed("No recorder connected", SyncState.Reason.NOT_CONNECTED)
        idle()
        assertEquals(context.getString(R.string.sync_connect_first), activity.lastSnackbarMessage)
    }

    @Test
    fun aStaleFailureReplayedOnLaunchIsNotRepeated() {
        sync.state.value = SyncState.Failed("old", SyncState.Reason.OTHER, at = System.currentTimeMillis() - 60_000)
        val activity = launch()
        assertNull(activity.lastSnackbarMessage)
    }

    @Test
    fun theSdkMessageNeverReachesTheSnackbarAndRetryRepeatsTheSameKindOfTransfer() {
        val activity = launch()
        sync.state.value = SyncState.Failed("openWiFi status 4 (WifiTransferAgent)", SyncState.Reason.WIFI)
        idle()
        assertEquals(context.getString(R.string.sync_failed_wifi), activity.lastSnackbarMessage)
        // Retry after a Fast Transfer failure is Fast Transfer again, not an ordinary sync.
        activity.lastSnackbarAction!!.invoke()
        assertEquals(1, sync.wifiCalls)
        assertEquals(0, sync.startSyncCalls)

        sync.state.value = SyncState.Failed("Recorder stopped responding", SyncState.Reason.TIMED_OUT)
        idle()
        activity.lastSnackbarAction!!.invoke()
        assertEquals(1, sync.startSyncCalls)
        assertEquals(1, sync.wifiCalls)
    }
}
