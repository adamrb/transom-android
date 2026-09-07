package org.plaudbridge.app.ui.recordings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.ServerRecording
import org.robolectric.RobolectricTestRunner

/**
 * RecordingsMerger pairing rules and the RecordingItem fields the rows print: every server row
 * once, unmatched phone files once, status words only while something is still happening.
 * Robolectric only for org.json (ServerRecording.fromJson); no Android UI is touched.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingsMergerTest {

    private fun local(
        session: Long,
        sn: String = "SN-A",
        serverId: String? = null,
        localPath: String? = "/tmp/$session.opus",
        uploaded: Boolean = false,
        createdAt: Long = 1_000_000L + session,
        name: String = "Untitled Recording",
        serverTitle: String? = null,
        marks: List<Double>? = null,
        duration: Long = 61
    ) = RecordingFile(
        sessionId = session, deviceSN = sn, name = name, duration = duration, createdAt = createdAt,
        localPath = localPath, uploaded = uploaded, serverId = serverId, serverTitle = serverTitle, marks = marks
    )

    private fun server(
        id: String,
        session: Long? = null,
        sn: String = "SN-A",
        status: String = "done",
        title: String? = "Server title $id",
        startedAt: String? = "2026-09-07T05:27:31Z",
        marks: String = "[]",
        durationS: Double = 61.0
    ) = ServerRecording.fromJson(
        JSONObject(
            """{"id":"$id","device_sn":"$sn","session_id":${session ?: "null"},"filename":"$id.mp3",
            "status":"$status","title":${if (title == null) "null" else "\"$title\""},
            "started_at":${if (startedAt == null) "null" else "\"$startedAt\""},
            "duration_s":$durationS,"marks":$marks}"""
        )
    )

    // MARK: - Matching

    @Test
    fun matchesByServerIdFirst() {
        val l = local(session = 1, serverId = "srv-1", uploaded = true)
        val s = server("srv-1", session = 999) // session differs on purpose: the id wins
        val items = RecordingsMerger.merge(listOf(l), listOf(s))
        assertEquals(1, items.size)
        assertSame(l, items[0].local)
        assertSame(s, items[0].server)
    }

    @Test
    fun matchesByDeviceAndSessionWhenTheIdIsNotKnownYet() {
        val l = local(session = 7) // upload queued: no serverId yet
        val s = server("srv-7", session = 7)
        val items = RecordingsMerger.merge(listOf(l), listOf(s))
        assertEquals(1, items.size)
        assertSame(l, items[0].local)
        assertEquals("srv-7", items[0].serverId)
    }

    @Test
    fun sameSessionOnAnotherDeviceDoesNotMatch() {
        val l = local(session = 7, sn = "SN-B")
        val s = server("srv-7", session = 7, sn = "SN-A")
        val items = RecordingsMerger.merge(listOf(l), listOf(s))
        assertEquals(2, items.size)
        assertEquals(setOf(null, "srv-7"), items.map { it.serverId }.toSet())
    }

    @Test
    fun blankDeviceSnNeverMatchesBySession() {
        val l = local(session = 7, sn = "")
        val s = server("srv-7", session = 7, sn = "")
        assertEquals(2, RecordingsMerger.merge(listOf(l), listOf(s)).size)
    }

    @Test
    fun everyServerRowAppearsOnceAndPhoneOnlyFilesAreAppended() {
        val matched = local(session = 1, serverId = "srv-1", uploaded = true)
        val phoneOnly = local(session = 2)
        val items = RecordingsMerger.merge(listOf(matched, phoneOnly), listOf(server("srv-1"), server("srv-3")))
        assertEquals(3, items.size)
        assertEquals(1, items.count { it.local === phoneOnly && it.server == null })
        assertEquals(1, items.count { it.local === matched && it.server?.id == "srv-1" })
        assertEquals(1, items.count { it.local == null && it.server?.id == "srv-3" })
    }

    @Test
    fun aLocalFileIsConsumedByOneServerRowOnly() {
        val l = local(session = 1, serverId = "srv-1", uploaded = true)
        // Two server rows claim the same phone file (duplicate upload); the second stays server-only.
        val items = RecordingsMerger.merge(listOf(l), listOf(server("srv-1"), server("srv-dup", session = 1)))
        assertEquals(2, items.size)
        assertEquals(1, items.count { it.local === l })
    }

    // MARK: - Fields

    @Test
    fun titleFallsBackFromServerToLocalToFilename() {
        assertEquals("Server title srv-1", RecordingItem(local(1, serverTitle = "Cached"), server("srv-1")).title)
        assertEquals("Cached", RecordingItem(local(1, serverTitle = "Cached"), server("srv-1", title = null)).title)
        assertEquals("Untitled Recording", RecordingItem(local(1), null).title)
        assertEquals("srv-2.mp3", RecordingItem(null, server("srv-2", title = "  ")).title)
    }

    @Test
    fun timeAndDurationFallBackToWhicheverSideKnows() {
        val l = local(1, createdAt = 42L, duration = 0)
        val s = server("srv-1", startedAt = null, durationS = 90.0)
        val item = RecordingItem(l, s)
        assertEquals(42L, item.recordedAt)
        assertEquals(90L, item.durationSeconds)
        val s2 = server("srv-2", durationS = 0.0)
        assertEquals(ServerRecording.parseIso("2026-09-07T05:27:31Z"), RecordingItem(local(2, duration = 61), s2).recordedAt)
        assertEquals(61L, RecordingItem(local(2, duration = 61), s2).durationSeconds)
    }

    @Test
    fun marksComeFromTheServerElseThePhone() {
        assertEquals(2, RecordingItem(local(1, marks = listOf(1.0)), server("srv-1", marks = "[6.0, 9.0]")).marksCount)
        assertEquals(1, RecordingItem(local(1, marks = listOf(1.0)), server("srv-1")).marksCount)
        assertEquals(3, RecordingItem(local(1, marks = listOf(1.0, 2.0, 3.0)), null).marksCount)
        assertEquals(0, RecordingItem(local(1), null).marksCount)
    }

    // MARK: - Status word

    @Test
    fun phoneOnlyStatusFollowsTheSyncPipeline() {
        assertEquals(RecordingItem.Status.DOWNLOADING, RecordingItem(local(1, localPath = null), null).status)
        assertEquals(RecordingItem.Status.UPLOADING, RecordingItem(local(1), null).status)
        // Uploaded but not in the server list we hold: nothing certain to say.
        assertEquals(RecordingItem.Status.NONE, RecordingItem(local(1, uploaded = true, serverId = "x"), null).status)
    }

    @Test
    fun serverStatusMapsToTranscribingFailedOrNothing() {
        assertEquals(RecordingItem.Status.TRANSCRIBING, RecordingItem(null, server("a", status = "pending")).status)
        assertEquals(RecordingItem.Status.TRANSCRIBING, RecordingItem(null, server("a", status = "transcribing")).status)
        assertEquals(RecordingItem.Status.FAILED, RecordingItem(null, server("a", status = "failed")).status)
        assertEquals(RecordingItem.Status.NONE, RecordingItem(null, server("a", status = "stored")).status)
        assertEquals(RecordingItem.Status.NONE, RecordingItem(null, server("a", status = "done")).status)
        assertEquals(RecordingItem.Status.NONE, RecordingItem(null, server("a", status = "something-new")).status)
    }

    @Test
    fun serverStatusWinsOverLocalStateWhenMatched() {
        // Phone still says "not uploaded" (the upload receipt has not landed) but the server has it.
        val item = RecordingItem(local(1), server("srv-1", session = 1, status = "done"))
        assertEquals(RecordingItem.Status.NONE, item.status)
        assertNull(RecordingsAdapter.statusLabelRes(item.status))
    }

    // MARK: - Order and search

    @Test
    fun sortedNewestFirstAcrossBothSources() {
        val older = local(session = 1, createdAt = ServerRecording.parseIso("2026-09-06T10:00:00Z")!!)
        val newest = server("srv-n", startedAt = "2026-09-07T09:00:00Z")
        val middle = server("srv-m", startedAt = "2026-09-07T05:00:00Z")
        val items = RecordingsMerger.merge(listOf(older), listOf(middle, newest))
        assertEquals(listOf("srv-n", "srv-m", null), items.map { it.serverId })
    }

    @Test
    fun filterMatchesTitleAndPreviewCaseInsensitively() {
        val budget = RecordingItem(null, server("a", title = "Budget call"))
        val standup = RecordingItem(
            null,
            ServerRecording.fromJson(JSONObject("""{"id":"b","filename":"b.mp3","title":"Standup","text_preview":"we discussed the BUDGET"}"""))
        )
        val other = RecordingItem(local(3, name = "Walk"), null)
        val all = listOf(budget, standup, other)
        assertEquals(listOf(budget, standup), RecordingsMerger.filter(all, "budget"))
        assertEquals(listOf(other), RecordingsMerger.filter(all, "walk"))
        assertEquals(all, RecordingsMerger.filter(all, "  "))
        assertEquals(all, RecordingsMerger.filter(all, null))
    }
}
