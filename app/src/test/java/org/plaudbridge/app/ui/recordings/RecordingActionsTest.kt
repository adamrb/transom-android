package org.plaudbridge.app.ui.recordings

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
import org.plaudbridge.app.managers.SyncManagerProtocol
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.models.SyncState
import org.plaudbridge.app.net.ApiClient
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Delete / Remove from phone / Rename semantics for a merged row, through fakes: the server is
 * asked only when the row has a server id, the phone copy goes only when the server agreed (or
 * already had nothing), and the recorder is never involved (there is no seam to it at all).
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

    /** Only the two file operations matter here; everything else is inert. */
    private class FakeLocal : SyncManagerProtocol {
        val deleted = mutableListOf<RecordingFile>()
        val renamed = mutableListOf<Pair<RecordingFile, String>>()
        override val state: StateFlow<SyncState> = MutableStateFlow(SyncState.Idle)
        override val files: StateFlow<List<RecordingFile>> = MutableStateFlow(emptyList())
        override fun fetchFileList() {}
        override fun startSync() {}
        override fun startWiFiTransfer() {}
        override fun stopSync() {}
        override fun deleteFile(file: RecordingFile) { deleted += file }
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
    fun setUp() = RecordingsRepository.reset()

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
        org.plaudbridge.app.storage.RecordingStore.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        org.plaudbridge.app.storage.RecordingStore.serverBaseUrl = "https://bridge.example.com"
        org.plaudbridge.app.storage.RecordingStore.serverAuthToken = "tok"
        RecordingsRepository.refresh()
        assertEquals(2, RecordingsRepository.server.value.size)
        RecordingActions.delete(RecordingItem(null, rec("srv-1")), FakeServer(), FakeLocal())
        assertEquals(listOf("srv-2"), RecordingsRepository.server.value.map { it.id })
    }

    // MARK: - Remove from phone

    @Test
    fun removeFromPhoneDeletesOnlyTheLocalCopy() {
        val phone = FakeLocal()
        val file = local("srv-1")
        assertTrue(RecordingActions.removeFromPhone(RecordingItem(file, rec("srv-1")), phone))
        assertEquals(listOf(file), phone.deleted)
    }

    @Test
    fun removeFromPhoneIsANoOpWithoutALocalCopy() {
        val phone = FakeLocal()
        assertFalse(RecordingActions.removeFromPhone(RecordingItem(null, rec("srv-1")), phone))
        assertTrue(phone.deleted.isEmpty())
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
