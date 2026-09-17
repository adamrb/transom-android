package cloud.adamrb.transom.ui.recordings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import cloud.adamrb.transom.managers.SyncManagerProtocol
import cloud.adamrb.transom.models.RecordingFile
import cloud.adamrb.transom.models.ServerRecording
import cloud.adamrb.transom.models.SyncState
import cloud.adamrb.transom.net.ApiClient
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Delete / Remove from phone / Rename semantics for a merged row, through fakes: the server is
 * asked only when the row has a server id, the phone copy goes only when the server agreed (or
 * already had nothing), and the recorder is never involved (there is no seam to it at all).
 * Delete also leaves a tombstone for the recorder's (device SN, session id) so the next sync does
 * not bring the recording back; Remove from phone keeps the flagged entry instead.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingActionsTest {

    private class FakeServer(
        var deleteResult: ApiClient.ActionResult = ApiClient.ActionResult.Ok,
        var renameResult: (String, String) -> ApiClient.RecordingResult = { id, title ->
            ApiClient.RecordingResult.Ok(rec(id, title))
        }
    ) : ServerRecordingActions {
        val deleted = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val retranscribed = mutableListOf<String>()
        override suspend fun rename(id: String, title: String): ApiClient.RecordingResult {
            renamed += id to title
            return renameResult(id, title)
        }
        override suspend fun retranscribe(id: String): ApiClient.ActionResult {
            retranscribed += id
            return ApiClient.ActionResult.Ok
        }
        override suspend fun delete(id: String): ApiClient.ActionResult {
            deleted += id
            return deleteResult
        }
    }

    /** Only the file operations matter here; everything else is inert. */
    private class FakeLocal : SyncManagerProtocol {
        val deleted = mutableListOf<RecordingFile>()
        val removed = mutableListOf<RecordingFile>()
        val renamed = mutableListOf<Pair<RecordingFile, String>>()
        override val state: StateFlow<SyncState> = MutableStateFlow(SyncState.Idle)
        override val files: StateFlow<List<RecordingFile>> = MutableStateFlow(emptyList())
        override fun fetchFileList() {}
        override fun startSync() {}
        override fun startWiFiTransfer() {}
        override fun stopSync() {}
        override fun deleteFile(file: RecordingFile) { deleted += file }
        override fun removeFromPhone(file: RecordingFile) { removed += file }
        override fun renameFile(file: RecordingFile, newName: String) { renamed += file to newName }
        override fun exportAudio(file: RecordingFile, callback: (Result<File>) -> Unit) {}
    }

    companion object {
        fun rec(id: String, title: String? = "Title") = ServerRecording.fromJson(
            JSONObject("""{"id":"$id","device_sn":"SN-A","session_id":1,"filename":"$id.mp3","status":"done",
                "title":${if (title == null) "null" else "\"$title\""},"started_at":"2026-09-07T05:27:31Z"}""")
        )
    }

    private fun local(serverId: String? = null) = RecordingFile(
        sessionId = 1, deviceSN = "SN-A", name = "Untitled Recording", duration = 61, createdAt = 1L,
        localPath = "/tmp/1.opus", uploaded = serverId != null, serverId = serverId
    )

    @Before
    fun setUp() {
        RecordingsRepository.reset()
        cloud.adamrb.transom.storage.RecordingStore.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        cloud.adamrb.transom.storage.RecordingStore.clearAll()
    }

    @After
    fun tearDown() = RecordingsRepository.reset()

    // MARK: - Delete

    @Test
    fun deleteOfMatchedRowHitsServerThenPhone() = runTest {
        val server = FakeServer()
        val phone = FakeLocal()
        val file = local(serverId = "srv-1")
        val result = RecordingActions.delete(RecordingItem(file, rec("srv-1")), server, phone)
        assertEquals(ApiClient.ActionResult.Ok, result)
        assertEquals(listOf("srv-1"), server.deleted)
        assertEquals(listOf(file), phone.deleted)
    }

    @Test
    fun deleteOfPhoneOnlyRowNeverAsksTheServer() = runTest {
        val server = FakeServer()
        val phone = FakeLocal()
        val file = local()
        assertEquals(ApiClient.ActionResult.Ok, RecordingActions.delete(RecordingItem(file, null), server, phone))
        assertTrue(server.deleted.isEmpty())
        assertEquals(listOf(file), phone.deleted)
    }

    @Test
    fun deleteOfServerOnlyRowTouchesNothingLocal() = runTest {
        val server = FakeServer()
        val phone = FakeLocal()
        assertEquals(ApiClient.ActionResult.Ok, RecordingActions.delete(RecordingItem(null, rec("srv-9")), server, phone))
        assertEquals(listOf("srv-9"), server.deleted)
        assertTrue(phone.deleted.isEmpty())
    }

    @Test
    fun deleteOfServerOnlyRowTombstonesTheRecorderSession() = runTest {
        // The server row carries device_sn + session_id (the upload metadata). If this phone
        // syncs that recorder later, the deleted recording must not be downloaded and uploaded.
        RecordingActions.delete(RecordingItem(null, rec("srv-9")), FakeServer(), FakeLocal())
        assertTrue(cloud.adamrb.transom.storage.RecordingStore.isHidden("SN-A", 1))
    }

    @Test
    fun deleteOfMatchedRowTombstonesTheServerIdentityAsWell() = runTest {
        // The phone's delete goes through the (fake) sync manager; the server row's identity is
        // tombstoned here, so even a legacy blank-SN phone entry cannot leave the real one open.
        val legacy = RecordingFile(sessionId = 1, deviceSN = "", name = "n", duration = 1, createdAt = 1L, localPath = "/tmp/1.opus")
        RecordingActions.delete(RecordingItem(legacy, rec("srv-1")), FakeServer(), FakeLocal())
        assertTrue(cloud.adamrb.transom.storage.RecordingStore.isHidden("SN-A", 1))
    }

    @Test
    fun deleteOfServerOnlyRowWithoutARecorderIdentityLeavesNoTombstone() = runTest {
        val noSession = ServerRecording.fromJson(
            JSONObject("""{"id":"srv-x","device_sn":"","session_id":null,"filename":"x.mp3","status":"done","title":"x"}""")
        )
        RecordingActions.delete(RecordingItem(null, noSession), FakeServer(), FakeLocal())
        assertTrue(cloud.adamrb.transom.storage.RecordingStore.hiddenSessions.isEmpty())
    }

    @Test
    fun deleteRefusedByTheServerLeavesNoTombstone() = runTest {
        val server = FakeServer(deleteResult = ApiClient.ActionResult.Error("boom"))
        RecordingActions.delete(RecordingItem(local("srv-1"), rec("srv-1")), server, FakeLocal())
        assertTrue(cloud.adamrb.transom.storage.RecordingStore.hiddenSessions.isEmpty())
    }

    @Test
    fun deleteKeepsThePhoneCopyWhenTheServerRefuses() = runTest {
        val server = FakeServer(deleteResult = ApiClient.ActionResult.AuthError(401))
        val phone = FakeLocal()
        val result = RecordingActions.delete(RecordingItem(local("srv-1"), rec("srv-1")), server, phone)
        assertEquals(ApiClient.ActionResult.AuthError(401), result)
        assertTrue(phone.deleted.isEmpty())
    }

    @Test
    fun deleteProceedsLocallyWhenTheServerCopyIsAlreadyGone() = runTest {
        val server = FakeServer(deleteResult = ApiClient.ActionResult.NotFound)
        val phone = FakeLocal()
        val file = local("srv-1")
        // The phone remembers a server id the server no longer knows (server-only row is gone).
        assertEquals(ApiClient.ActionResult.Ok, RecordingActions.delete(RecordingItem(file, null), server, phone))
        assertEquals(listOf("srv-1"), server.deleted)
        assertEquals(listOf(file), phone.deleted)
    }

    @Test
    fun deleteDropsTheRowFromTheServerSnapshotImmediately() = runTest {
        RecordingsRepository.listSource = RecordingsRepository.ListSource {
            ApiClient.ListResult.Ok(listOf(rec("srv-1"), rec("srv-2")))
        }
        cloud.adamrb.transom.storage.RecordingStore.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        cloud.adamrb.transom.storage.RecordingStore.serverBaseUrl = "https://bridge.example.com"
        cloud.adamrb.transom.storage.RecordingStore.serverAuthToken = "tok"
        RecordingsRepository.refresh()
        assertEquals(2, RecordingsRepository.server.value.size)
        RecordingActions.delete(RecordingItem(null, rec("srv-1")), FakeServer(), FakeLocal())
        assertEquals(listOf("srv-2"), RecordingsRepository.server.value.map { it.id })
    }

    // MARK: - Remove from phone

    @Test
    fun removeFromPhoneKeepsTheEntryWhenAServerCopyExists() {
        val phone = FakeLocal()
        val file = local("srv-1")
        assertTrue(RecordingActions.removeFromPhone(RecordingItem(file, rec("srv-1")), phone))
        assertEquals(listOf(file), phone.removed)
        assertTrue("a flagged entry, not a delete: the row stays server-backed", phone.deleted.isEmpty())
    }

    @Test
    fun removeFromPhoneUsesTheRememberedServerIdWhenTheServerRowIsMissing() {
        // Stale snapshot: the phone knows the upload id but the server list has not caught up.
        val phone = FakeLocal()
        val file = local("srv-1")
        assertTrue(RecordingActions.removeFromPhone(RecordingItem(file, null), phone))
        assertEquals(listOf(file), phone.removed)
        assertTrue(phone.deleted.isEmpty())
    }

    @Test
    fun removeFromPhoneOfALegacyBlankSnEntryTombstonesTheServerIdentity() {
        // The flag sits on the blank-SN entry, which the real recorder's sync never consults.
        val phone = FakeLocal()
        val legacy = RecordingFile(sessionId = 1, deviceSN = "", name = "n", duration = 1, createdAt = 1L, localPath = "/tmp/1.opus")
        assertTrue(RecordingActions.removeFromPhone(RecordingItem(legacy, rec("srv-1")), phone))
        assertEquals(listOf(legacy), phone.removed)
        assertTrue(cloud.adamrb.transom.storage.RecordingStore.isHidden("SN-A", 1))
    }

    @Test
    fun removeFromPhoneOfALegacyEntryWithoutAKnownIdentityIsRefused() {
        // Server row not in the snapshot: nothing could stop the recorder from syncing the
        // session straight back next to the legacy row, so nothing is removed.
        val phone = FakeLocal()
        val legacy = RecordingFile(
            sessionId = 1, deviceSN = "", name = "n", duration = 1, createdAt = 1L, localPath = "/tmp/1.opus",
            uploaded = true, serverId = "srv-1"
        )
        assertFalse(RecordingActions.removeFromPhone(RecordingItem(legacy, null), phone))
        assertTrue(phone.removed.isEmpty())
        assertTrue(phone.deleted.isEmpty())
        assertTrue(cloud.adamrb.transom.storage.RecordingStore.hiddenSessions.isEmpty())
    }

    @Test
    fun removeFromPhonePersistsAServerIdKnownOnlyFromTheServerList() {
        // Upload receipt not stored yet (or a legacy index): the row is matched by (SN, session).
        val phone = FakeLocal()
        val file = local() // no serverId
        cloud.adamrb.transom.storage.RecordingStore.addFiles(listOf(file))
        assertTrue(RecordingActions.removeFromPhone(RecordingItem(file, rec("srv-1")), phone))
        assertEquals(listOf(file), phone.removed)
        val stored = cloud.adamrb.transom.storage.RecordingStore.allFiles.single()
        assertEquals("srv-1", stored.serverId)
        assertTrue(stored.uploaded)
    }

    @Test
    fun removeFromPhoneOfAMatchingEntryLeavesNoTombstone() {
        // Same identity on both sides: the flagged entry alone suppresses the session.
        val phone = FakeLocal()
        assertTrue(RecordingActions.removeFromPhone(RecordingItem(local("srv-1"), rec("srv-1")), phone))
        assertTrue(cloud.adamrb.transom.storage.RecordingStore.hiddenSessions.isEmpty())
    }

    @Test
    fun removeFromPhoneWithoutAServerCopyIsAFullLocalDelete() {
        // Nothing for the row to fall back to, so keeping a flagged entry would leave a ghost
        // row. The UI does not offer the action here (canRemoveFromPhone), this is the backstop.
        val phone = FakeLocal()
        val file = local()
        assertTrue(RecordingActions.removeFromPhone(RecordingItem(file, null), phone))
        assertEquals(listOf(file), phone.deleted)
        assertTrue(phone.removed.isEmpty())
    }

    @Test
    fun removeFromPhoneIsANoOpWithoutALocalCopy() {
        val phone = FakeLocal()
        assertFalse(RecordingActions.removeFromPhone(RecordingItem(null, rec("srv-1")), phone))
        assertTrue(phone.deleted.isEmpty())
    }

    // MARK: - Re-transcribe

    @Test
    fun retranscribeGoesToTheServerAndReturnsItsAnswer() = runTest {
        val server = FakeServer()
        assertEquals(ApiClient.ActionResult.Ok, RecordingActions.retranscribe("srv-1", server))
        assertEquals(listOf("srv-1"), server.retranscribed)
    }

    // MARK: - Rename

    @Test
    fun renameGoesToTheServerAndMirrorsIntoThePhoneCopy() = runTest {
        val server = FakeServer()
        val phone = FakeLocal()
        val file = local("srv-1")
        val result = RecordingActions.rename(RecordingItem(file, rec("srv-1")), "New name", server, phone)
        assertEquals(ApiClient.ActionResult.Ok, result)
        assertEquals(listOf("srv-1" to "New name"), server.renamed)
        assertEquals(listOf(file to "New name"), phone.renamed)
    }

    @Test
    fun renameOfPhoneOnlyRowIsLocal() = runTest {
        val server = FakeServer()
        val phone = FakeLocal()
        val file = local()
        assertEquals(ApiClient.ActionResult.Ok, RecordingActions.rename(RecordingItem(file, null), "Walk", server, phone))
        assertTrue(server.renamed.isEmpty())
        assertEquals(listOf(file to "Walk"), phone.renamed)
    }

    @Test
    fun renameLeavesThePhoneAloneWhenTheServerRefuses() = runTest {
        val server = FakeServer(renameResult = { _, _ -> ApiClient.RecordingResult.Error("boom") })
        val phone = FakeLocal()
        val result = RecordingActions.rename(RecordingItem(local("srv-1"), rec("srv-1")), "x", server, phone)
        assertEquals(ApiClient.ActionResult.Error("boom"), result)
        assertTrue(phone.renamed.isEmpty())
    }
}
