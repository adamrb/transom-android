package org.plaudbridge.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.models.RecordingFile
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * RecordingStore: composite (device_sn, session_id) identity (finding 2), atomic index writes and
 * corruption quarantine (finding 11), and missing-local-file reconciliation (finding 10).
 */
@RunWith(RobolectricTestRunner::class)
class RecordingStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        File(context.filesDir, "recordings.json").delete()
        File(context.filesDir, "recordings.json.bak").delete()
        File(context.filesDir, "recordings.json.tmp").delete()
    }

    private fun rec(sn: String, session: Long): RecordingFile = RecordingFile(
        sessionId = session,
        deviceSN = sn,
        name = "rec-$sn-$session",
        duration = 10,
        createdAt = session * 1000
    )

    // MARK: - Composite-key identity

    @Test
    fun sameSessionIdOnDifferentDevicesAreDistinctRecordings() {
        RecordingStore.addFiles(listOf(rec("SN-A", 100)))
        RecordingStore.addFiles(listOf(rec("SN-B", 100)))
        assertEquals(2, RecordingStore.allFiles.size)
    }

    @Test
    fun duplicateCompositeKeyIsDeduped() {
        RecordingStore.addFiles(listOf(rec("SN-A", 100)))
        RecordingStore.addFiles(listOf(rec("SN-A", 100)))
        assertEquals(1, RecordingStore.allFiles.size)
    }

    @Test
    fun markAsSyncedTargetsOnlyTheMatchingDevice() {
        RecordingStore.addFiles(listOf(rec("SN-A", 100), rec("SN-B", 100)))
        RecordingStore.markAsSynced("SN-A", 100, "/tmp/a.mp3", 42)

        val a = RecordingStore.allFiles.first { it.deviceSN == "SN-A" }
        val b = RecordingStore.allFiles.first { it.deviceSN == "SN-B" }
        assertTrue(a.isSynced)
        assertFalse(b.isSynced)
    }

    @Test
    fun markAsUploadedTargetsOnlyTheMatchingDevice() {
        RecordingStore.addFiles(listOf(rec("SN-A", 100), rec("SN-B", 100)))
        RecordingStore.markAsUploaded("SN-B", 100, "server-id-b")

        val a = RecordingStore.allFiles.first { it.deviceSN == "SN-A" }
        val b = RecordingStore.allFiles.first { it.deviceSN == "SN-B" }
        assertFalse(a.uploaded)
        assertTrue(b.uploaded)
        assertEquals("server-id-b", b.serverId)
    }

    @Test
    fun blankSnRecordsAreIsolatedFromRealDevices() {
        // Blank-SN legacy records are their own namespace: a real device's update must never
        // match (or backfill) them — that wildcard is how cross-device corruption happened.
        RecordingStore.addFiles(listOf(rec("", 100)))
        RecordingStore.markAsSynced("SN-A", 100, "/tmp/a.mp3", 42)

        val stored = RecordingStore.allFiles.single()
        assertEquals("", stored.deviceSN)
        assertFalse(stored.isSynced)

        // A blank-SN update DOES match the blank record (its own namespace).
        RecordingStore.markAsSynced("", 100, "/tmp/legacy.mp3", 42)
        assertTrue(RecordingStore.allFiles.single().isSynced)
    }

    @Test
    fun blankRequestedSnNeverMatchesAKnownDeviceRecord() {
        // A blank-SN update must not write state onto another device's recording.
        RecordingStore.addFiles(listOf(rec("SN-A", 100)))
        RecordingStore.markAsUploaded("", 100, "server-id")
        assertFalse(RecordingStore.allFiles.single().uploaded)
    }

    @Test
    fun blankSnRecordDoesNotSuppressARealDeviceRecordWithSameSession() {
        // Both may coexist: the blank legacy record and the real device's record are distinct.
        RecordingStore.addFiles(listOf(rec("", 100)))
        RecordingStore.addFiles(listOf(rec("SN-A", 100)))
        assertEquals(2, RecordingStore.allFiles.size)
    }

    @Test
    fun pendingDeviceDeletesAreScopedToTheDevice() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1), rec("SN-B", 2)))
        RecordingStore.markAsSynced("SN-A", 1, "/tmp/a.mp3", 1)
        RecordingStore.markAsUploaded("SN-A", 1, "sid-a")
        RecordingStore.setDeletePendingOnDevice("SN-A", 1, true)

        assertEquals(1, RecordingStore.pendingDeviceDeletes("SN-A").size)
        assertTrue(RecordingStore.pendingDeviceDeletes("SN-B").isEmpty())

        RecordingStore.setDeletePendingOnDevice("SN-A", 1, false)
        assertTrue(RecordingStore.pendingDeviceDeletes("SN-A").isEmpty())
    }

    // MARK: - Index persistence

    @Test
    fun saveAndReloadRoundTripsWithoutLeavingTempFile() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1), rec("SN-A", 2)))
        assertEquals(2, RecordingStore.allFiles.size)
        assertFalse(File(context.filesDir, "recordings.json.tmp").exists())
        assertTrue(File(context.filesDir, "recordings.json").exists())
    }

    @Test
    fun corruptIndexIsQuarantinedNotSilentlyEmptied() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        val index = File(context.filesDir, "recordings.json")
        index.writeText("{ this is not valid json ][")

        // Reading the corrupt index yields an empty view...
        assertTrue(RecordingStore.allFiles.isEmpty())
        // ...but the corrupt content is preserved as a .bak for recovery/diagnosis.
        val bak = File(context.filesDir, "recordings.json.bak")
        assertTrue(bak.exists())
        assertEquals("{ this is not valid json ][", bak.readText())
    }

    @Test
    fun renameFailureNeverFallsBackToDirectWrite() {
        // Force renameTo to fail: the target path is occupied by a non-empty directory
        // (rename(2) of a file onto a non-empty directory fails). The old code then fell back
        // to a direct target.writeText — truncation-prone AND it would throw here. The fixed
        // code must complete without throwing and leave the existing path untouched.
        val target = File(context.filesDir, "recordings.json")
        target.delete()
        assertTrue(target.mkdir())
        File(target, "occupant").writeText("keep")

        RecordingStore.addFiles(listOf(rec("SN-A", 1))) // must not throw

        assertTrue(target.isDirectory)
        assertEquals("keep", File(target, "occupant").readText())

        // Clean up so later saves in other tests work.
        File(target, "occupant").delete()
        target.delete()
        File(context.filesDir, "recordings.json.tmp").delete()
    }

    @Test
    fun serverConfigGenerationBumpsOnUrlOrTokenChange() {
        val start = RecordingStore.serverConfigGeneration
        RecordingStore.serverBaseUrl = "https://one.example.com"
        assertEquals(start + 1, RecordingStore.serverConfigGeneration)

        // Same value: no bump.
        RecordingStore.serverBaseUrl = "https://one.example.com"
        assertEquals(start + 1, RecordingStore.serverConfigGeneration)

        RecordingStore.serverAuthToken = "tok-1"
        assertEquals(start + 2, RecordingStore.serverConfigGeneration)
        RecordingStore.serverAuthToken = "tok-1"
        assertEquals(start + 2, RecordingStore.serverConfigGeneration)

        RecordingStore.serverBaseUrl = "https://two.example.com"
        assertEquals(start + 3, RecordingStore.serverConfigGeneration)
    }

    // MARK: - Background sync settings

    @Test
    fun backgroundSyncDefaultsToEnabledAndPersistsChanges() {
        // Fresh install (clearAll in setUp): on by default, because keeping the recorder linked
        // while the phone is pocketed is the reason the bridge exists.
        assertTrue(RecordingStore.isBackgroundSyncEnabled)

        RecordingStore.isBackgroundSyncEnabled = false
        assertFalse(RecordingStore.isBackgroundSyncEnabled)

        RecordingStore.isBackgroundSyncEnabled = true
        assertTrue(RecordingStore.isBackgroundSyncEnabled)
    }

    @Test
    fun notificationPermissionAskedDefaultsToFalse() {
        assertFalse(RecordingStore.notificationPermissionAsked)
        RecordingStore.notificationPermissionAsked = true
        assertTrue(RecordingStore.notificationPermissionAsked)
    }

    @Test
    fun clearAllResetsBackgroundSyncToDefault() {
        RecordingStore.isBackgroundSyncEnabled = false
        RecordingStore.clearAll()
        assertTrue(RecordingStore.isBackgroundSyncEnabled)
    }

    // MARK: - Missing local file reconciliation

    @Test
    fun missingLocalFileMakesRecordingUnsyncedAgain() {
        val audio = File(context.filesDir, "gone.mp3").apply { writeBytes(byteArrayOf(1)) }
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsSynced("SN-A", 1, audio.absolutePath, 5)
        assertTrue(RecordingStore.allFiles.single().isSynced)

        audio.delete()
        RecordingStore.clearMissingLocalFiles()

        val stored = RecordingStore.allFiles.single()
        assertFalse(stored.isSynced)
        assertNull(stored.localPath)
        assertNull(stored.syncedAt)
    }

    @Test
    fun existingLocalFileSurvivesReconciliation() {
        val audio = File(context.filesDir, "kept.mp3").apply { writeBytes(byteArrayOf(1)) }
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsSynced("SN-A", 1, audio.absolutePath, 5)

        RecordingStore.clearMissingLocalFiles()
        assertTrue(RecordingStore.allFiles.single().isSynced)
    }

    @Test
    fun clearServerStateResetsUploadStateForReupload() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsSynced("SN-A", 1, "/tmp/a.mp3", 5)
        RecordingStore.markAsUploaded("SN-A", 1, "old-server-id")
        RecordingStore.updateTranscript(RecordingStore.allFiles.single().id, """{"text":"hi"}""")

        RecordingStore.clearServerState()

        val stored = RecordingStore.allFiles.single()
        assertFalse(stored.uploaded)
        assertNull(stored.serverId)
        assertNull(stored.uploadedAt)
        assertNull(stored.transcriptJSON)
        assertFalse(stored.deletePendingOnDevice)
        // Local sync state survives — only server-scoped state is dropped.
        assertEquals("/tmp/a.mp3", stored.localPath)
    }
}
