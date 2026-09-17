package io.github.adamrb.transom.ui.settings

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.github.adamrb.transom.BuildConfig
import io.github.adamrb.transom.R
import io.github.adamrb.transom.storage.RecordingStore
import io.github.adamrb.transom.ui.library.WebDashboardActivity
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Settings screen: the Advanced section is collapsed on first open and its state persists; the
 * recorder rows only appear once a recorder is paired; the version row shows the version name
 * alone (build number under Advanced); the server row never shows token fragments; the
 * Automations row opens the dashboard straight on its Automations tab.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsFragmentTest {

    /** Bare host so the fragment can be attached without the tab bar around it. */
    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            setTheme(R.style.Theme_Transom)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = CONTAINER })
        }
        companion object { const val CONTAINER = 4242 }
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        RecordingStore.clearAll()
    }

    private fun attach(): SettingsFragment {
        val activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
        val fragment = SettingsFragment()
        activity.supportFragmentManager.beginTransaction().add(HostActivity.CONTAINER, fragment).commitNow()
        return fragment
    }

    private fun SettingsFragment.view(id: Int): View = requireView().findViewById(id)
    private fun SettingsFragment.text(id: Int): String = (view(id) as TextView).text.toString()

    @Test
    fun advancedSectionStartsCollapsedAndTogglePersists() {
        val first = attach()
        val content = first.view(R.id.advancedContent)
        assertEquals(View.GONE, content.visibility)
        assertFalse(RecordingStore.advancedSettingsExpanded)

        first.view(R.id.advancedHeader).performClick()
        assertEquals(View.VISIBLE, content.visibility)
        assertTrue(RecordingStore.advancedSettingsExpanded)
        assertEquals(90f, first.view(R.id.advancedChevron).rotation)

        // A fresh Settings screen opens the way the user left it.
        val second = attach()
        assertEquals(View.VISIBLE, second.view(R.id.advancedContent).visibility)

        second.view(R.id.advancedHeader).performClick()
        assertEquals(View.GONE, second.view(R.id.advancedContent).visibility)
        assertFalse(RecordingStore.advancedSettingsExpanded)
    }

    @Test
    fun recorderRowsAreHiddenUntilARecorderIsPaired() {
        val unpaired = attach()
        assertEquals(View.GONE, unpaired.view(R.id.firmwareCard).visibility)
        assertEquals(View.GONE, unpaired.view(R.id.signOutButton).visibility)

        RecordingStore.addPairedDevice("SN123", "Plaud Note")
        val paired = attach()
        assertEquals(View.VISIBLE, paired.view(R.id.firmwareCard).visibility)
        assertEquals(View.VISIBLE, paired.view(R.id.signOutButton).visibility)
        // Paired but not connected right now: a word, not "--".
        assertEquals("Not connected", paired.text(R.id.firmwareVersionLabel))
    }

    @Test
    fun versionRowShowsTheVersionNameAloneWithTheBuildNumberUnderAdvanced() {
        val fragment = attach()
        assertEquals(BuildConfig.VERSION_NAME, fragment.text(R.id.appVersionLabel))
        assertEquals(BuildConfig.VERSION_CODE.toString(), fragment.text(R.id.buildNumberLabel))
        assertEquals("0.4.6 · Up to date", SettingsFragment.versionLabel("0.4.6", "Up to date"))
        assertEquals("0.4.6", SettingsFragment.versionLabel("0.4.6", null))
    }

    @Test
    fun serverRowSaysWhetherATokenIsSetAndNeverShowsFragmentsOfIt() {
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "9e06aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa0084"
        val fragment = attach()
        val label = fragment.text(R.id.serverInfoLabel)
        assertEquals("https://bridge.example.com · Access token set", label)
        assertFalse(label.contains("9e06"))
        assertFalse(label.contains("0084"))

        assertEquals("Not configured", SettingsFragment.serverLabel(context, null, "tok"))
        assertEquals("https://x.example · No access token", SettingsFragment.serverLabel(context, "https://x.example", ""))
    }

    @Test
    fun automationsRowOpensTheDashboardOnItsAutomationsTabAndAdvancedOpensTheFullView() {
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "tok"
        val fragment = attach()
        val activity = fragment.requireActivity()

        fragment.view(R.id.webDashboardRow).performClick()
        val automations = shadowOf(activity).nextStartedActivity
        assertEquals(WebDashboardActivity::class.java.name, automations.component!!.className)
        assertEquals(WebDashboardActivity.TAB_AUTOMATIONS, automations.getStringExtra(WebDashboardActivity.EXTRA_TAB))

        fragment.view(R.id.advancedHeader).performClick()
        fragment.view(R.id.webDashboardAdvancedRow).performClick()
        val full = shadowOf(activity).nextStartedActivity
        assertEquals(WebDashboardActivity::class.java.name, full.component!!.className)
        assertNull(full.getStringExtra(WebDashboardActivity.EXTRA_TAB))
    }
}
