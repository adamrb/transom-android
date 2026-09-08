package org.plaudbridge.app.ui.list

import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.ui.recordings.RecordingItem
import org.plaudbridge.app.ui.recordings.RecordingsAdapter
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor

/**
 * The list adapter diffs by recording key: a poll that changes one row's status produces one
 * payload change for that row and nothing else, never a whole-list reset. Rows are identified
 * by key, so a status change is an update of the same row, not a remove plus insert.
 */
@RunWith(RobolectricTestRunner::class)
class DateGroupedAdapterTest {

    /** Records every notification the adapter sends. */
    private class Recorder : RecyclerView.AdapterDataObserver() {
        val events = mutableListOf<String>()
        override fun onChanged() { events += "reset" }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { events += "changed($positionStart,$itemCount)" }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { events += "changed($positionStart,$itemCount,$payload)" }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { events += "inserted($positionStart,$itemCount)" }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { events += "removed($positionStart,$itemCount)" }
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { events += "moved($fromPosition,$toPosition)" }
    }

    private fun server(id: String, status: String, progress: Double? = null, startedAt: String = "2026-09-07T05:27:31Z") =
        ServerRecording.fromJson(JSONObject(
            """{"id":"$id","device_sn":"SN-A","session_id":1,"filename":"$id.mp3","status":"$status","stage":"transcribing",
            "progress":${progress ?: "null"},"duration_s":61.0,"started_at":"$startedAt"}"""
        ))

    private fun adapter(recorder: Recorder): RecordingsAdapter {
        val adapter = RecordingsAdapter(onTapped = {}, onActions = {}, diffExecutor = Executor { it.run() })
        adapter.registerAdapterDataObserver(recorder)
        return adapter
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun aStatusChangeIsOnePayloadUpdateOfTheSameRow() {
        val recorder = Recorder()
        val adapter = adapter(recorder)
        val done = RecordingItem(null, server("a", "done"))
        val working = RecordingItem(null, server("b", "transcribing", progress = 0.42))
        adapter.submit(listOf(done, working), null)
        idle()
        assertEquals(3, adapter.itemCount) // one header, two rows
        recorder.events.clear()

        adapter.submit(listOf(done, RecordingItem(null, server("b", "transcribing", progress = 0.47))), null)
        idle()
        assertEquals(listOf("changed(2,1,${DateGroupedAdapter.PAYLOAD_LABELS})"), recorder.events)
        assertEquals(3, adapter.itemCount)
    }

    @Test
    fun anUnchangedListNotifiesNothing() {
        val recorder = Recorder()
        val adapter = adapter(recorder)
        val rows = listOf(RecordingItem(null, server("a", "done")), RecordingItem(null, server("b", "pending")))
        adapter.submit(rows, null)
        idle()
        recorder.events.clear()
        adapter.submit(rows.map { it.copy() }, null) // equal content, new objects (a poll's parse)
        idle()
        assertEquals(emptyList<String>(), recorder.events)
    }

    @Test
    fun rowsAreIdentifiedByKeyAcrossPhoneAndServerViews() {
        val recorder = Recorder()
        val adapter = adapter(recorder)
        val phoneCopy = RecordingFile(
            sessionId = 1, deviceSN = "SN-A", name = "Untitled Recording", duration = 61,
            createdAt = ServerRecording.parseIso("2026-09-07T05:27:31Z")!!, localPath = "/tmp/1.mp3", uploaded = true, serverId = "a"
        )
        adapter.submit(listOf(RecordingItem(phoneCopy, null)), null)
        idle()
        recorder.events.clear()
        // The server list arrives: same key ("a"), so the row updates in place.
        adapter.submit(listOf(RecordingItem(phoneCopy, server("a", "done"))), null)
        idle()
        assertEquals(listOf("changed(1,1,${DateGroupedAdapter.PAYLOAD_LABELS})"), recorder.events)
        assertTrue(RecordingItem(phoneCopy, null).key == RecordingItem(phoneCopy, server("a", "done")).key)
    }

    @Test
    fun aNewRecordingIsInsertedAndADeletedOneRemovedWithoutAReset() {
        val recorder = Recorder()
        val adapter = adapter(recorder)
        val a = RecordingItem(null, server("a", "done", startedAt = "2026-09-07T05:00:00Z"))
        val b = RecordingItem(null, server("b", "done", startedAt = "2026-09-06T05:00:00Z"))
        adapter.submit(listOf(a, b), null)
        idle()
        recorder.events.clear()

        val c = RecordingItem(null, server("c", "pending", startedAt = "2026-09-07T06:00:00Z"))
        adapter.submit(listOf(c, a), null) // c appears at the top of today, b (yesterday) is gone
        idle()
        assertTrue(recorder.events.toString(), recorder.events.none { it == "reset" })
        assertTrue(recorder.events.toString(), recorder.events.any { it.startsWith("inserted(") })
        assertTrue(recorder.events.toString(), recorder.events.any { it.startsWith("removed(") })
        assertEquals(3, adapter.itemCount) // Today header + c + a
        assertEquals(c, adapter.itemAt(1))
        assertEquals(a, adapter.itemAt(2))
        assertEquals(null, adapter.itemAt(0))
    }

    @Test
    fun aChangedQueryRebindsTheLabelsOfEveryRow() {
        val recorder = Recorder()
        val adapter = adapter(recorder)
        val rows = listOf(RecordingItem(null, server("a", "done")), RecordingItem(null, server("b", "done")))
        adapter.submit(rows, null)
        idle()
        recorder.events.clear()
        adapter.submit(rows, "budget")
        idle()
        assertEquals("budget", adapter.query)
        assertEquals(listOf("changed(0,3,${DateGroupedAdapter.PAYLOAD_LABELS})"), recorder.events)
    }
}
