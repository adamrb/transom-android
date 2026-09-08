package org.plaudbridge.app.managers

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.SyncState
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * SyncManager.handleFileList against the store, with no BLE involved: the device file list is
 * fed in directly. What matters is what the list does NOT do: a session the user deleted
 * (tombstoned) or removed from the phone must neither reappear in the index nor be queued for
 * download, because with delete-after-upload off the recorder still lists it every time.
 *
 * Only suppressed sessions appear in the fed lists, so no download is ever started (a download
 * would reach for the BLE SDK); "nothing new" surfaces as SyncState.Completed.
 */
@RunWith(RobolectricTestRunner::class)
class SyncManagerFileListTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        File(context.filesDir, "recordings.json").delete()
        SyncManager.shared.reset()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun rec(sn: String, session: Long) = RecordingFile(
        sessionId = session, deviceSN = sn, name = "Untitled Recording", duration = 10, createdAt = session * 1000
    )

    /** Feed a device list for [sn] and run the main-looper continuation that sets the state. */
    private fun feedDeviceList(sn: String, vararg sessions: Long) {
        SyncManager.shared.handleFileList(
            sessionIds = sessions.toList(),
            durations = sessions.map { 0L },
            sizes = sessions.map { 0 },
            sns = sessions.map { sn }
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun syncNowWithoutARecorderFailsAtOnceInsteadOfWaitingForever() {
        val previous = SyncManager.shared.recorderConnected
        SyncManager.shared.recorderConnected = { false }
        try {
            SyncManager.shared.startSync()
            shadowOf(Looper.getMainLooper()).idle()
            val state = SyncManager.shared.state.value
            assertTrue(state.toString(), state is SyncState.Failed)
            assertEquals(SyncState.Reason.NOT_CONNECTED, (state as SyncState.Failed).reason)
            // Nothing is active, so the banner has nothing to show and Sync now works again later.
            assertTrue(!state.isActive)
        } finally {
            SyncManager.shared.recorderConnected = previous
            SyncManager.shared.reset()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun deletedSessionListedByTheDeviceIsNotReAddedOrDownloaded() {
        val audio = File(context.filesDir, "1.mp3").apply { writeBytes(byteArrayOf(1)) }
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsSynced("SN-A", 1, audio.absolutePath, 5)
        SyncManager.shared.deleteFile(RecordingStore.allFiles.single())
        assertTrue(RecordingStore.allFiles.isEmpty())

        // Next sync: the recorder still has session 1.
        feedDeviceList("SN-A", 1)

        assertTrue("tombstoned session must not come back", RecordingStore.allFiles.isEmpty())
        assertTrue(SyncManager.shared.files.value.isEmpty())
        assertEquals(SyncState.Completed, SyncManager.shared.state.value)
    }

    @Test
    fun removedFromPhoneSessionKeepsItsEntryAndIsNotDownloadedAgain() {
        val audio = File(context.filesDir, "2.mp3").apply { writeBytes(byteArrayOf(1)) }
        RecordingStore.addFiles(listOf(rec("SN-A", 2)))
        RecordingStore.markAsSynced("SN-A", 2, audio.absolutePath, 5)
        RecordingStore.markAsUploaded("SN-A", 2, "srv-2")
        SyncManager.shared.removeFromPhone(RecordingStore.allFiles.single())

        feedDeviceList("SN-A", 2)

        val kept = RecordingStore.allFiles.single()
        assertTrue(kept.removedFromPhone)
        assertEquals("srv-2", kept.serverId) // the entry (and its server link) was kept, not re-created
        assertEquals(null, kept.localPath)
        assertEquals(SyncState.Completed, SyncManager.shared.state.value)
    }

    @Test
    fun tombstoneIsScopedToTheDeviceThatHadTheRecording() {
        RecordingStore.hideSession("SN-A", 3)

        // A different recorder happens to use the same session id: that is a distinct recording.
        // Its download would need the SDK, so it is added and synced through the store directly;
        // the point is that SN-A's tombstone neither blocks it nor is disturbed by it.
        val audio = File(context.filesDir, "b3.mp3").apply { writeBytes(byteArrayOf(1)) }
        RecordingStore.addFiles(listOf(rec("SN-B", 3)))
        RecordingStore.markAsSynced("SN-B", 3, audio.absolutePath, 5)
        assertEquals(listOf("SN-B"), RecordingStore.allFiles.map { it.deviceSN })

        feedDeviceList("SN-A", 3)
        assertEquals(listOf("SN-B"), RecordingStore.allFiles.map { it.deviceSN })
        assertEquals(SyncState.Completed, SyncManager.shared.state.value)
        assertTrue(RecordingStore.isHidden("SN-A", 3))
    }

    @Test
    fun sessionStillOnTheRecorderKeepsItsEntryAcrossAListRefresh() {
        // An entry without audio (evicted file, or "removed from phone" lifted by a server
        // switch) is carried forward, not re-created: the pinned name, marks and upload state
        // survive the re-download that follows.
        RecordingStore.addFiles(listOf(rec("SN-A", 5)))
        val entry = RecordingStore.allFiles.single()
        RecordingStore.renameFile(entry, "Pinned name")
        RecordingStore.updateMarks(entry.id, listOf(3.0))
        RecordingStore.markAsUploaded("SN-A", 5, "srv-5")

        // No looper idle here: the store side is synchronous, and the queued download (which
        // would reach for the BLE SDK) is never run.
        SyncManager.shared.handleFileList(listOf(5L, 6L), listOf(0L, 0L), listOf(0, 0), listOf("SN-A", "SN-A"))

        val after = RecordingStore.allFiles.associateBy { it.sessionId }
        assertEquals(setOf(5L, 6L), after.keys)
        val carried = after.getValue(5L)
        assertEquals(entry.id, carried.id)
        assertEquals("Pinned name", carried.displayName)
        assertTrue(carried.nameEditedByUser)
        assertEquals(listOf(3.0), carried.marks)
        assertEquals("srv-5", carried.serverId)
        assertTrue(carried.uploaded)
        assertEquals(null, carried.localPath) // still to be downloaded
    }

    @Test
    fun sessionNoLongerOnTheRecorderIsDroppedAsBefore() {
        RecordingStore.addFiles(listOf(rec("SN-A", 7)))
        RecordingStore.hideSession("SN-A", 8) // so the fed list adds nothing and no download starts

        feedDeviceList("SN-A", 8)

        assertTrue(RecordingStore.allFiles.isEmpty())
    }

    @Test
    fun unpairingForgetsTheTombstonesSoARePairSyncsTheRecorderAsIs() {
        RecordingStore.addPairedDevice("SN-A", "Note")
        RecordingStore.hideSession("SN-A", 4)

        RecordingStore.removePairedDevice("SN-A")

        assertTrue(RecordingStore.hiddenSessions.isEmpty())
        RecordingStore.addFiles(listOf(rec("SN-A", 4)))
        assertEquals(1, RecordingStore.allFiles.size)
    }
}
