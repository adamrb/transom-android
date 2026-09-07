package org.plaudbridge.app.ui.settings

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.R
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Settings "Advanced" section: collapsed on first open, one tap expands it, the state is
 * persisted so the next SettingsFragment opens the way the user left it.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsFragmentTest {

    /** Bare host so the fragment can be attached without the tab bar around it. */
    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            setTheme(R.style.Theme_PlaudBridge)
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

    @Test
    fun advancedSectionStartsCollapsedAndTogglePersists() {
        val first = attach()
        val content = first.requireView().findViewById<View>(R.id.advancedContent)
        assertEquals(View.GONE, content.visibility)
        assertFalse(RecordingStore.advancedSettingsExpanded)

        first.requireView().findViewById<View>(R.id.advancedHeader).performClick()
        assertEquals(View.VISIBLE, content.visibility)
        assertTrue(RecordingStore.advancedSettingsExpanded)
        assertEquals(90f, first.requireView().findViewById<View>(R.id.advancedChevron).rotation)

        // A fresh Settings screen opens the way the user left it.
        val second = attach()
        assertEquals(View.VISIBLE, second.requireView().findViewById<View>(R.id.advancedContent).visibility)

        second.requireView().findViewById<View>(R.id.advancedHeader).performClick()
        assertEquals(View.GONE, second.requireView().findViewById<View>(R.id.advancedContent).visibility)
        assertFalse(RecordingStore.advancedSettingsExpanded)
    }
}
