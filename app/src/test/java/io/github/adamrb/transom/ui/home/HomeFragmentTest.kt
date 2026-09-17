package io.github.adamrb.transom.ui.home

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.github.adamrb.transom.R
import io.github.adamrb.transom.storage.RecordingStore
import io.github.adamrb.transom.ui.common.AppManagers
import io.github.adamrb.transom.ui.common.FakeDeviceManager
import io.github.adamrb.transom.ui.common.FakeSyncManager
import io.github.adamrb.transom.ui.common.SnackbarHostActivity
import io.github.adamrb.transom.ui.onboarding.ScanningActivity
import io.github.adamrb.transom.ui.recording.RecordingActivity
import io.github.adamrb.transom.ui.recordings.RecordingsRepository
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Home with no recorder connected: the recorder card is one "Connect your recorder" call to
 * action (no empty battery or storage bars, no Manage button), and Record and Sync now are
 * dimmed and explain themselves instead of starting something that cannot work. Once a
 * recorder connects, the card and both entries behave as before.
 */
@RunWith(RobolectricTestRunner::class)
class HomeFragmentTest {

    private lateinit var context: Context
    private lateinit var sync: FakeSyncManager
    private lateinit var device: FakeDeviceManager
    private lateinit var activity: SnackbarHostActivity

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        android.provider.Settings.Global.putFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f
        )
        RecordingStore.init(context)
        RecordingStore.clearAll()
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

    private fun attach(): HomeFragment {
        activity = Robolectric.buildActivity(SnackbarHostActivity::class.java).setup().get()
        val fragment = HomeFragment()
        activity.supportFragmentManager.beginTransaction().add(SnackbarHostActivity.CONTAINER, fragment).commitNow()
        idle()
        return fragment
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T : View> HomeFragment.view(id: Int): T = requireView().findViewById(id)

    @Test
    fun withoutARecorderTheCardIsASingleConnectCallToAction() {
        val fragment = attach()
        assertEquals(View.VISIBLE, fragment.view<View>(R.id.noRecorderContent).visibility)
        assertEquals(View.GONE, fragment.view<View>(R.id.deviceCardHeader).visibility)
        assertEquals(View.GONE, fragment.view<View>(R.id.deviceExpandedContent).visibility)
        assertEquals(context.getString(R.string.connect_recorder), fragment.view<TextView>(R.id.connectRecorderButton).text.toString())

        fragment.view<View>(R.id.connectRecorderButton).performClick()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ScanningActivity::class.java.name, started.component?.className)
        assertTrue(started.getBooleanExtra(ScanningActivity.EXTRA_ADDING_DEVICE, false))
    }

    @Test
    fun recordAndSyncNowAreDimmedAndExplainUntilARecorderConnects() {
        val fragment = attach()
        val syncNow = fragment.view<View>(R.id.syncNowButton)
        val recordCard = fragment.view<View>(R.id.recordCard)
        assertEquals(HomeFragment.DISABLED_ALPHA, syncNow.alpha)
        assertEquals(HomeFragment.DISABLED_ALPHA, recordCard.alpha)
        assertEquals(context.getString(R.string.sync_connect_first), fragment.view<TextView>(R.id.recordSubtitleLabel).text.toString())

        syncNow.performClick()
        assertEquals(0, sync.startSyncCalls)
        assertEquals(listOf(context.getString(R.string.sync_connect_first)), activity.messages)
        assertEquals(context.getString(R.string.connect), activity.actionLabels.last())

        recordCard.performClick()
        assertNull(shadowOf(activity).nextStartedActivity) // no Start Recording screen that cannot start
        assertEquals(2, activity.messages.size)

        // The snackbar's Connect action opens the connect flow.
        activity.lastAction!!.invoke()
        assertEquals(ScanningActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
    }

    @Test
    fun aConnectedRecorderRestoresEverything() {
        val fragment = attach()
        device.connect(name = "NotePin")
        idle()

        assertEquals(View.GONE, fragment.view<View>(R.id.noRecorderContent).visibility)
        assertEquals(View.VISIBLE, fragment.view<View>(R.id.deviceCardHeader).visibility)
        assertEquals("NotePin", fragment.view<TextView>(R.id.deviceNameLabel).text.toString())
        assertEquals(1f, fragment.view<View>(R.id.syncNowButton).alpha)
        assertEquals(1f, fragment.view<View>(R.id.recordCard).alpha)
        assertEquals(context.getString(R.string.record_via_device), fragment.view<TextView>(R.id.recordSubtitleLabel).text.toString())

        fragment.view<View>(R.id.syncNowButton).performClick()
        assertEquals(1, sync.startSyncCalls)
        assertTrue(activity.messages.isEmpty())

        fragment.view<View>(R.id.recordCard).performClick()
        assertEquals(RecordingActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)

        // Expanding the card now shows battery and storage.
        fragment.view<View>(R.id.deviceCardHeader).performClick()
        idle()
        assertEquals(View.VISIBLE, fragment.view<View>(R.id.deviceExpandedContent).visibility)
        assertEquals("80%", fragment.view<TextView>(R.id.batteryValueLabel).text.toString())

        // And losing the recorder collapses it back into the call to action.
        device.disconnectRecorder()
        idle()
        assertEquals(View.VISIBLE, fragment.view<View>(R.id.noRecorderContent).visibility)
        assertEquals(View.GONE, fragment.view<View>(R.id.deviceExpandedContent).visibility)
        assertEquals(HomeFragment.DISABLED_ALPHA, fragment.view<View>(R.id.syncNowButton).alpha)
    }
}
