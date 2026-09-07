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

    // MARK: - Server titles and manual renames

    @Test
    fun legacyIndexWithoutTitleFieldsLoadsWithDefaults() {
        // recordings.json written by a build that predates serverTitle/nameEditedByUser.
        File(context.filesDir, "recordings.json").writeText(
            """[{"id":"legacy-1","sessionId":7,"deviceSN":"SN-A","name":"Untitled Recording",
                "duration":12,"createdAt":7000,"uploaded":true,"serverId":"srv-7"}]"""
        )

        val stored = RecordingStore.allFiles.single()
        assertEquals("legacy-1", stored.id)
        assertNull(stored.serverTitle)
        assertFalse(stored.nameEditedByUser)
        assertEquals("Untitled Recording", stored.displayName)
        // It is exactly the kind of record the title fetch should pick up.
        assertEquals(listOf("legacy-1"), RecordingStore.awaitingTranscript.map { it.id })
    }

    @Test
    fun renameFilePinsTheNameAgainstServerTitles() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        val rec = RecordingStore.allFiles.single()

        RecordingStore.renameFile(rec, "Call with Sam")
        RecordingStore.updateServerTitle(rec.id, "Budget planning call")

        val stored = RecordingStore.allFiles.single()
        assertEquals("Call with Sam", stored.name)
        assertTrue(stored.nameEditedByUser)
        assertEquals("Budget planning call", stored.serverTitle)
        assertEquals("Call with Sam", stored.displayName)
    }

    @Test
    fun updateServerTitleChangesOnlyTheTitle() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        val rec = RecordingStore.allFiles.single()

        RecordingStore.updateServerTitle(rec.id, "AI title")

        val stored = RecordingStore.allFiles.single()
        assertEquals("AI title", stored.serverTitle)
        assertEquals("AI title", stored.displayName)
        assertEquals(rec.name, stored.name)
        assertFalse(stored.nameEditedByUser)
        assertNull(stored.transcriptJSON)
    }

    @Test
    fun updateTranscriptCapturesTheTitle() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        val rec = RecordingStore.allFiles.single()

        RecordingStore.updateTranscript(
            rec.id,
            """{"text":"hello","segments":[],"summary":"short","title":"  Budget planning call "}"""
        )

        val stored = RecordingStore.allFiles.single()
        assertEquals("Budget planning call", stored.serverTitle)
        assertEquals("Budget planning call", stored.displayName)
        assertTrue(stored.transcriptJSON!!.contains("\"text\":\"hello\""))
    }

    @Test
    fun updateTranscriptToleratesDocumentsWithoutAUsableTitle() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        val rec = RecordingStore.allFiles.single()
        RecordingStore.updateServerTitle(rec.id, "Earlier title")

        // Absent, JSON null, non-string, blank, and a bare segment array: transcript stored,
        // the previously known title is left alone (never cleared by a title-less document).
        for (doc in listOf(
            """{"text":"a","segments":[]}""",
            """{"text":"b","title":null}""",
            """{"text":"c","title":42}""",
            """{"text":"d","title":"   "}""",
            """[{"text":"e","start":0}]"""
        )) {
            RecordingStore.updateTranscript(rec.id, doc)
            val stored = RecordingStore.allFiles.single()
            assertEquals(doc, stored.transcriptJSON)
            assertEquals("Earlier title", stored.serverTitle)
        }
    }

    @Test
    fun parseTranscriptTitleNeverThrows() {
        assertNull(RecordingStore.parseTranscriptTitle("<html>login</html>"))
        assertNull(RecordingStore.parseTranscriptTitle(""))
        assertEquals("T", RecordingStore.parseTranscriptTitle("""{"title":"T"}"""))
    }

    @Test
    fun clearServerStateDropsServerTitleButKeepsManualRename() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1), rec("SN-A", 2)))
        val titled = RecordingStore.allFiles.first { it.sessionId == 1L }
        val renamed = RecordingStore.allFiles.first { it.sessionId == 2L }
        RecordingStore.updateServerTitle(titled.id, "Old server title")
        RecordingStore.renameFile(renamed, "Mine")

        RecordingStore.clearServerState()

        assertNull(RecordingStore.allFiles.first { it.sessionId == 1L }.serverTitle)
        val kept = RecordingStore.allFiles.first { it.sessionId == 2L }
        assertEquals("Mine", kept.name)
        assertTrue(kept.nameEditedByUser)
    }

    @Test
    fun awaitingTranscriptListsUploadedFilesWithoutACachedTranscript() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1), rec("SN-A", 2), rec("SN-A", 3)))
        RecordingStore.markAsUploaded("SN-A", 2, "srv-2")
        RecordingStore.markAsUploaded("SN-A", 3, "srv-3")
        val done = RecordingStore.allFiles.first { it.sessionId == 3L }
        RecordingStore.updateTranscript(done.id, """{"text":"t","title":"Done"}""")

        assertEquals(listOf(2L), RecordingStore.awaitingTranscript.map { it.sessionId })
    }
}
