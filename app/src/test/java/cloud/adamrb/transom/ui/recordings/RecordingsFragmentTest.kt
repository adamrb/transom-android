package cloud.adamrb.transom.ui.recordings

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputEditText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import cloud.adamrb.transom.R
import cloud.adamrb.transom.models.RecordingFile
import cloud.adamrb.transom.models.SyncProgress
import cloud.adamrb.transom.models.SyncState
import cloud.adamrb.transom.storage.RecordingStore
import cloud.adamrb.transom.ui.common.AppManagers
import cloud.adamrb.transom.ui.common.FakeSyncManager
import cloud.adamrb.transom.ui.common.SnackbarHostActivity
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor

/**
 * The Recordings tab: the search box filters the merged list, shows a snippet under a row whose
 * match is not in its title, names the empty result after the term, and clearing the text
 * restores the list; the transfer banner shows while syncing and clears on failure.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingsFragmentTest {

    private lateinit var context: Context
    private lateinit var sync: FakeSyncManager
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
        RecordingsFragment.diffExecutorForTests = Executor { it.run() }
        sync = FakeSyncManager()
        AppManagers.syncOverride = sync
    }

    @After
    fun tearDown() {
        AppManagers.reset()
        RecordingsFragment.diffExecutorForTests = null
    }

    private fun file(session: Long, name: String, summary: String? = null) = RecordingFile(
        sessionId = session, deviceSN = "SN-A", name = name, duration = 61, createdAt = 1_788_758_851_000L + session,
        localPath = "/tmp/$session.mp3", uploaded = true, serverId = "srv-$session", nameEditedByUser = true,
        summaryText = summary
    )

    private fun attach(): RecordingsFragment {
        activity = Robolectric.buildActivity(SnackbarHostActivity::class.java).setup().get()
        val fragment = RecordingsFragment()
        activity.supportFragmentManager.beginTransaction().add(SnackbarHostActivity.CONTAINER, fragment).commitNow()
        idle()
        return fragment
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T : View> RecordingsFragment.view(id: Int): T = requireView().findViewById(id)

    private fun RecordingsFragment.list(): RecyclerView = view(R.id.recordingsRecyclerView)

    private fun RecordingsFragment.adapter() = list().adapter as RecordingsAdapter

    /** Robolectric does not lay views out on its own; the row views exist only after this. */
    private fun RecordingsFragment.layoutList() {
        val list = list()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, 1080, 2400)
    }

    private fun RecordingsFragment.rowAt(position: Int): View = list().findViewHolderForAdapterPosition(position)!!.itemView

    @Test
    fun searchFiltersShowsSnippetsAndClears() {
        sync.files.value = listOf(
            file(2, "Budget call"),
            file(1, "Walk", summary = "## Notes\n\nA long chat about the *quarterly budget* numbers.")
        )
        val fragment = attach()
        assertEquals(3, fragment.adapter().itemCount) // header + 2 rows

        val field: TextInputEditText = fragment.view(R.id.searchField)
        field.setText("budget")
        idle()
        assertEquals(3, fragment.adapter().itemCount) // both match
        fragment.layoutList()
        // Row 1 is "Budget call" (newer): the title shows the match, no snippet.
        assertEquals(View.GONE, fragment.rowAt(1).findViewById<View>(R.id.fileSnippetLabel).visibility)
        // Row 2 matched on its summary: the snippet says so, markdown gone.
        val snippet = fragment.rowAt(2).findViewById<TextView>(R.id.fileSnippetLabel)
        assertEquals(View.VISIBLE, snippet.visibility)
        // Heading markup and emphasis are stripped; the heading's words stay, as prose.
        assertEquals("Notes A long chat about the quarterly budget numbers.", snippet.text.toString())
        // The ⋮ is there on every row.
        assertEquals(View.VISIBLE, fragment.rowAt(1).findViewById<View>(R.id.moreButton).visibility)

        field.setText("zzz")
        idle()
        assertEquals(0, fragment.adapter().itemCount)
        val empty: TextView = fragment.view(R.id.emptyLabel)
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals(context.getString(R.string.search_no_results_fmt, "zzz"), empty.text.toString())

        field.setText("") // what the clear icon does
        idle()
        assertEquals(3, fragment.adapter().itemCount)
        assertEquals(View.GONE, empty.visibility)
        assertNull(fragment.adapter().query)
    }

    @Test
    fun bannerShowsWhileSyncingAndClearsWhenTheSyncFails() {
        val fragment = attach()
        val banner: View = fragment.view(R.id.syncBanner)
        assertEquals(View.GONE, banner.visibility)

        sync.state.value = SyncState.Syncing(SyncProgress(totalFiles = 0, syncedFiles = 0))
        idle()
        assertEquals(View.VISIBLE, banner.visibility)
        assertEquals(context.getString(R.string.retrieving_file_list), fragment.view<TextView>(R.id.syncSpeedLabel).text.toString())

        sync.state.value = SyncState.Failed("Recorder stopped responding", SyncState.Reason.TIMED_OUT)
        idle()
        assertEquals(View.GONE, banner.visibility)
        // The failure is told by the host's snackbar, not by this tab; nothing is left behind.
        assertNotNull(fragment.view<View>(R.id.searchButton))
    }
}
