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

    // MARK: - Delete tombstones and Remove from phone

    @Test
    fun deleteFileTombstonesTheSessionSoTheDeviceListCannotBringItBack() {
        val audio = File(context.filesDir, "del.mp3").apply { writeBytes(byteArrayOf(1)) }
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsSynced("SN-A", 1, audio.absolutePath, 5)

        RecordingStore.deleteFile(RecordingStore.allFiles.single())

        assertTrue(RecordingStore.allFiles.isEmpty())
        assertFalse(audio.exists())
        assertTrue(RecordingStore.isHidden("SN-A", 1))
        assertEquals(setOf("SN-A" to 1L), RecordingStore.hiddenSessions)
        // The recorder still lists the session (delete-after-upload off): addFiles refuses it.
        RecordingStore.addFiles(listOf(rec("SN-A", 1), rec("SN-A", 2)))
        assertEquals(listOf(2L), RecordingStore.allFiles.map { it.sessionId })
        // Exact-match keyed: the same session id on another device is a different recording.
        RecordingStore.addFiles(listOf(rec("SN-B", 1)))
        assertEquals(setOf("SN-A" to 2L, "SN-B" to 1L), RecordingStore.allFiles.map { it.deviceSN to it.sessionId }.toSet())
    }

    @Test
    fun removeFromPhoneDeletesThePathTheIndexHoldsNotOnlyTheCallersCopy() {
        // WiFi re-export moved the entry to a new file after the UI took its snapshot.
        val old = File(context.filesDir, "old.mp3").apply { writeBytes(byteArrayOf(1)) }
        val new = File(context.filesDir, "new.mp3").apply { writeBytes(byteArrayOf(1)) }
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsSynced("SN-A", 1, old.absolutePath, 5)
        val snapshot = RecordingStore.allFiles.single()
        RecordingStore.markAsSynced("SN-A", 1, new.absolutePath, 5)

        RecordingStore.removeFromPhone(snapshot)

        assertFalse("the newer MP3 must not be orphaned", new.exists())
        assertFalse(old.exists())
        assertNull(RecordingStore.allFiles.single().localPath)
    }

    @Test
    fun deleteFileDeletesThePathTheIndexHolds() {
        val old = File(context.filesDir, "d-old.mp3").apply { writeBytes(byteArrayOf(1)) }
        val new = File(context.filesDir, "d-new.mp3").apply { writeBytes(byteArrayOf(1)) }
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsSynced("SN-A", 1, old.absolutePath, 5)
        val snapshot = RecordingStore.allFiles.single()
        RecordingStore.markAsSynced("SN-A", 1, new.absolutePath, 5)

        RecordingStore.deleteFile(snapshot)

        assertFalse(new.exists())
        assertFalse(old.exists())
        assertTrue(RecordingStore.allFiles.isEmpty())
    }

    @Test
    fun hideSessionIsIdempotentAndSurvivesAReload() {
        RecordingStore.hideSession("SN-A", 5)
        RecordingStore.hideSession("SN-A", 5)
        assertEquals(1, RecordingStore.hiddenSessions.size)
        // Prefs-backed: a fresh init (new process) still knows the tombstone.
        RecordingStore.init(context)
        assertTrue(RecordingStore.isHidden("SN-A", 5))
    }

    @Test
    fun removeFromPhoneKeepsTheEntryFlaggedWithoutAudio() {
        val audio = File(context.filesDir, "rm.mp3").apply { writeBytes(byteArrayOf(1)) }
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsSynced("SN-A", 1, audio.absolutePath, 5)
        RecordingStore.markAsUploaded("SN-A", 1, "srv-1")

        RecordingStore.removeFromPhone(RecordingStore.allFiles.single())

        val kept = RecordingStore.allFiles.single()
        assertTrue(kept.removedFromPhone)
        assertFalse(kept.isSynced)
        assertNull(kept.localPath)
        assertNull(kept.syncedAt)
        assertEquals("srv-1", kept.serverId) // the server link survives
        assertFalse(audio.exists())
        assertFalse("not a tombstone: the entry itself carries the marker", RecordingStore.isHidden("SN-A", 1))
        assertTrue("no audio means nothing to upload", RecordingStore.pendingUploads.isEmpty())
        // Reconciliation leaves it alone (nothing to "re-download").
        RecordingStore.clearMissingLocalFiles()
        assertTrue(RecordingStore.allFiles.single().removedFromPhone)
        // addFiles sees an existing (SN, session) and does not duplicate it.
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        assertEquals(1, RecordingStore.allFiles.size)
    }

    @Test
    fun downloadCompletingAfterRemovalIsDiscardedNotAttached() {
        // The BLE queue (or a WiFi export) was built before the user chose Remove from phone.
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.removeFromPhone(RecordingStore.allFiles.single())
        val late = File(context.filesDir, "late.mp3").apply { writeBytes(byteArrayOf(1)) }

        RecordingStore.markAsSynced("SN-A", 1, late.absolutePath, 5)

        val stored = RecordingStore.allFiles.single()
        assertTrue("the user's choice sticks", stored.removedFromPhone)
        assertFalse(stored.isSynced)
        assertNull(stored.localPath)
        assertFalse("stray audio does not leak into storage", late.exists())
        // Other entries are untouched by the discard path.
        RecordingStore.addFiles(listOf(rec("SN-A", 2)))
        val other = File(context.filesDir, "other.mp3").apply { writeBytes(byteArrayOf(1)) }
        RecordingStore.markAsSynced("SN-A", 2, other.absolutePath, 5)
        assertTrue(RecordingStore.allFiles.first { it.sessionId == 2L }.isSynced)
    }

    @Test
    fun unpairClearsOnlyThatDevicesTombstones() {
        RecordingStore.addPairedDevice("SN-A", "Note A")
        RecordingStore.addPairedDevice("SN-B", "Note B")
        RecordingStore.hideSession("SN-A", 1)
        RecordingStore.hideSession("SN-B", 1)

        RecordingStore.hideSession("SN-A", 9, untilServerSwitch = true) // stands in for a removedFromPhone flag

        RecordingStore.removePairedDevice("SN-A")

        assertFalse(RecordingStore.isHidden("SN-A", 1))
        assertTrue(RecordingStore.isHidden("SN-B", 1))
        assertTrue("removal markers survive unpair like the flags they stand in for", RecordingStore.isHidden("SN-A", 9))
        // Re-paired, the deleted session syncs like any other.
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        assertEquals(1, RecordingStore.allFiles.size)
    }

    @Test
    fun serverSwitchLiftsRemoveFromPhoneSoTheNewServerGetsTheRecording() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsUploaded("SN-A", 1, "srv-old")
        RecordingStore.removeFromPhone(RecordingStore.allFiles.single())

        RecordingStore.clearServerState()

        val stored = RecordingStore.allFiles.single()
        assertFalse("the old server kept the copy; the new one has none", stored.removedFromPhone)
        assertNull(stored.serverId)
        assertFalse(stored.uploaded)
        assertNull(stored.localPath) // an ordinary not-yet-downloaded session again
        // Delete tombstones are a stronger choice and survive the switch.
        RecordingStore.hideSession("SN-A", 2)
        RecordingStore.clearServerState()
        assertTrue(RecordingStore.isHidden("SN-A", 2))
    }

    @Test
    fun switchScopedTombstonesLiftOnServerSwitchAndNeverDowngradeAPermanentOne() {
        RecordingStore.hideSession("SN-A", 1, untilServerSwitch = true) // Remove from phone (legacy entry)
        RecordingStore.hideSession("SN-A", 2)                            // Delete
        RecordingStore.hideSession("SN-A", 2, untilServerSwitch = true)  // a later removal must not weaken the delete
        RecordingStore.hideSession("SN-A", 3, untilServerSwitch = true)
        RecordingStore.hideSession("SN-A", 3)                            // a delete upgrades the removal
        assertEquals(3, RecordingStore.hiddenSessions.size)

        RecordingStore.clearServerState()

        assertFalse(RecordingStore.isHidden("SN-A", 1))
        assertTrue(RecordingStore.isHidden("SN-A", 2))
        assertTrue(RecordingStore.isHidden("SN-A", 3))
    }

    @Test
    fun clearAllDropsTheTombstones() {
        RecordingStore.hideSession("SN-A", 1)
        RecordingStore.clearAll()
        assertTrue(RecordingStore.hiddenSessions.isEmpty())
    }

    @Test
    fun legacyIndexWithoutRemovedFlagLoadsAsNotRemoved() {
        File(context.filesDir, "recordings.json").writeText(
            """[{"id":"old","sessionId":1,"deviceSN":"SN-A","name":"n","duration":1,"createdAt":1000,"localPath":"/x"}]"""
        )
        val loaded = RecordingStore.allFiles.single()
        assertFalse(loaded.removedFromPhone)
        assertTrue(loaded.isSynced)
    }

    // MARK: - Device list reconciliation

    private fun listed(vararg sessions: Long) = sessions.map { RecordingStore.ListedSession(it, 0, it * 1000) }

    @Test
    fun reconcileKeepsReusesSuppressesAndCreatesInOneStep() {
        val audio = File(context.filesDir, "1.mp3").apply { writeBytes(byteArrayOf(1)) }
        RecordingStore.addFiles(listOf(rec("SN-A", 1), rec("SN-A", 2), rec("SN-A", 3), rec("SN-A", 4), rec("SN-B", 5)))
        RecordingStore.markAsSynced("SN-A", 1, audio.absolutePath, 5)                      // synced: kept, not new
        RecordingStore.renameFile(RecordingStore.allFiles.first { it.sessionId == 2L }, "Pinned") // audio-less: reused
        RecordingStore.removeFromPhone(RecordingStore.allFiles.first { it.sessionId == 3L })     // kept, suppressed
        RecordingStore.hideSession("SN-A", 4)                                                   // deleted: dropped, suppressed
        val reusedId = RecordingStore.allFiles.first { it.sessionId == 2L }.id

        val r = RecordingStore.reconcileDeviceList("SN-A", listed(1, 2, 3, 4, 6))

        assertEquals(listOf(2L, 6L), r.newSessionIds)
        assertEquals(setOf(3L, 4L), r.suppressedSessionIds)
        val bySession = r.all.associateBy { it.deviceSN to it.sessionId }
        assertEquals(setOf("SN-A" to 1L, "SN-A" to 2L, "SN-A" to 3L, "SN-A" to 6L), bySession.keys)
        assertEquals(reusedId, bySession.getValue("SN-A" to 2L).id)
        assertEquals("Pinned", bySession.getValue("SN-A" to 2L).displayName)
        assertTrue(bySession.getValue("SN-A" to 3L).removedFromPhone)
        assertEquals("Untitled Recording", bySession.getValue("SN-A" to 6L).name)
        // SN-B's audio-less entry was not on this device's list and is dropped, as before.
        assertEquals(r.all.map { it.id }.toSet(), RecordingStore.allFiles.map { it.id }.toSet())
    }

    @Test
    fun reconcileNeverLetsABlankSnLegacyEntryStandInForARealSession() {
        val audio = File(context.filesDir, "legacy.mp3").apply { writeBytes(byteArrayOf(1)) }
        RecordingStore.addFiles(listOf(rec("", 7)))
        RecordingStore.markAsSynced("", 7, audio.absolutePath, 5)

        val r = RecordingStore.reconcileDeviceList("SN-A", listed(7))

        assertEquals(listOf(7L), r.newSessionIds) // one redundant download is the safe side
        assertEquals(2, r.all.size)
    }

    // MARK: - Stale server id

    @Test
    fun replaceServerIdForgetsThatMarksReachedTheOldRecord() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsUploaded("SN-A", 1, "srv-old")
        val file = RecordingStore.allFiles.single()
        RecordingStore.updateMarks(file.id, listOf(4.0))
        RecordingStore.markMarksSynced(file.id, listOf(4.0))
        assertTrue(RecordingStore.awaitingMarksSync.isEmpty())

        RecordingStore.replaceServerId(file.id, "srv-new")

        val stored = RecordingStore.allFiles.single()
        assertEquals("srv-new", stored.serverId)
        assertTrue(stored.uploaded)
        assertEquals(listOf(4.0), stored.marks)
        assertEquals(listOf(file.id), RecordingStore.awaitingMarksSync.map { it.id })
    }

    @Test
    fun clearStaleServerIdClearsOnlyTheMatchingIdAndKeepsUploaded() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsUploaded("SN-A", 1, "srv-old")
        val file = RecordingStore.allFiles.single()

        // A concurrent path already resolved a new id: the clear must not wipe it.
        RecordingStore.clearStaleServerId(file.id, "srv-other")
        assertEquals("srv-old", RecordingStore.allFiles.single().serverId)

        RecordingStore.clearStaleServerId(file.id, "srv-old")
        val cleared = RecordingStore.allFiles.single()
        assertNull(cleared.serverId)
        assertTrue("the upload happened; one 404 does not earn a re-upload", cleared.uploaded)
        assertTrue(RecordingStore.awaitingTranscript.isEmpty())
    }

    @Test
    fun clearStaleServerIdCancelsADeferredDeviceDelete() {
        // Delete-after-upload was deferred (recorder not connected); then the server lost the
        // recording. Running that delete later would destroy the last copy.
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsUploaded("SN-A", 1, "srv-old")
        RecordingStore.setDeletePendingOnDevice("SN-A", 1, true)
        assertEquals(1, RecordingStore.pendingDeviceDeletes("SN-A").size)

        RecordingStore.clearStaleServerId(RecordingStore.allFiles.single().id, "srv-old")

        assertTrue(RecordingStore.pendingDeviceDeletes("SN-A").isEmpty())
        assertFalse(RecordingStore.allFiles.single().deletePendingOnDevice)
    }

    @Test
    fun clearStaleServerIdLiftsRemoveFromPhoneSoTheRowIsNotStranded() {
        RecordingStore.addFiles(listOf(rec("SN-A", 1)))
        RecordingStore.markAsUploaded("SN-A", 1, "srv-old")
        RecordingStore.removeFromPhone(RecordingStore.allFiles.single())

        RecordingStore.clearStaleServerId(RecordingStore.allFiles.single().id, "srv-old")

        val cleared = RecordingStore.allFiles.single()
        assertNull(cleared.serverId)
        assertFalse("no server copy to fall back to: back to an ordinary session", cleared.removedFromPhone)
    }

    // MARK: - Settings preferences

    @Test
    fun advancedSettingsSectionIsCollapsedByDefaultAndRemembered() {
        assertFalse(RecordingStore.advancedSettingsExpanded)
        RecordingStore.advancedSettingsExpanded = true
        assertTrue(RecordingStore.advancedSettingsExpanded)
        // Survives a re-init (the prefs file is the store, not the object).
        RecordingStore.init(context)
        assertTrue(RecordingStore.advancedSettingsExpanded)
        RecordingStore.advancedSettingsExpanded = false
        assertFalse(RecordingStore.advancedSettingsExpanded)
    }
}
