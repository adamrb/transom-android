package org.plaudbridge.app.ui.recordings

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.RobolectricTestRunner

/**
 * RecordingsRepository's snapshot belongs to one server configuration. After the URL or token
 * changes the old rows go at once (not when the new server finally answers), and an answer from
 * a request that straddled the switch is dropped instead of being shown as the new server's list.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingsRepositoryTest {

    private fun rec(id: String) = ServerRecording.fromJson(
        JSONObject("""{"id":"$id","device_sn":"SN-A","session_id":1,"filename":"$id.mp3","status":"done","title":"$id"}""")
    )

    private fun ok(vararg ids: String) = ApiClient.ListResult.Ok(ids.map { rec(it) })

    @Before
    fun setUp() {
        RecordingStore.init(ApplicationProvider.getApplicationContext())
        RecordingStore.clearAll()
        RecordingStore.serverBaseUrl = "https://one.example.com"
        RecordingStore.serverAuthToken = "tok-one"
        RecordingsRepository.reset()
    }

    @After
    fun tearDown() = RecordingsRepository.reset()

    @Test
    fun successfulRefreshPublishesTheList() = runTest {
        RecordingsRepository.listSource = RecordingsRepository.ListSource { ok("a", "b") }
        val result = RecordingsRepository.refresh()
        assertTrue(result is ApiClient.ListResult.Ok)
        assertEquals(listOf("a", "b"), RecordingsRepository.server.value.map { it.id })
    }

    @Test
    fun failedRefreshOnTheSameServerKeepsTheRowsOnScreen() = runTest {
        RecordingsRepository.listSource = RecordingsRepository.ListSource { ok("a") }
        RecordingsRepository.refresh()
        RecordingsRepository.listSource = RecordingsRepository.ListSource { ApiClient.ListResult.Error("offline") }
        RecordingsRepository.refresh()
        assertEquals(listOf("a"), RecordingsRepository.server.value.map { it.id })
    }

    @Test
    fun switchingServersBlanksTheSnapshotBeforeTheNewServerAnswers() = runTest {
        RecordingsRepository.listSource = RecordingsRepository.ListSource { ok("old-1", "old-2") }
        RecordingsRepository.refresh()
        assertEquals(2, RecordingsRepository.server.value.size)

        RecordingStore.serverBaseUrl = "https://two.example.com"
        val seenDuringRequest = ArrayList<List<String>>()
        RecordingsRepository.listSource = RecordingsRepository.ListSource {
            seenDuringRequest += RecordingsRepository.server.value.map { it.id }
            ok("new-1")
        }
        RecordingsRepository.refresh()

        assertEquals("old rows must be gone while the new server is asked", listOf(emptyList<String>()), seenDuringRequest)
        assertEquals(listOf("new-1"), RecordingsRepository.server.value.map { it.id })
    }

    @Test
    fun responseThatCrossedAServerSwitchIsDiscarded() = runTest {
        RecordingsRepository.listSource = RecordingsRepository.ListSource {
            // The user saves a new token while this request is in flight; the body that comes
            // back was produced by the OLD server.
            RecordingStore.serverAuthToken = "tok-two"
            ok("from-old-server")
        }
        val result = RecordingsRepository.refresh()

        assertTrue(result is ApiClient.ListResult.Error)
        assertTrue(RecordingsRepository.server.value.isEmpty())

        // The next refresh, on the new configuration, publishes normally.
        RecordingsRepository.listSource = RecordingsRepository.ListSource { ok("from-new-server") }
        RecordingsRepository.refresh()
        assertEquals(listOf("from-new-server"), RecordingsRepository.server.value.map { it.id })
    }

    @Test
    fun existingRowsAreDroppedWhenARefreshCrossesAServerSwitch() = runTest {
        RecordingsRepository.listSource = RecordingsRepository.ListSource { ok("old") }
        RecordingsRepository.refresh()
        assertEquals(1, RecordingsRepository.server.value.size)

        RecordingsRepository.listSource = RecordingsRepository.ListSource {
            RecordingStore.serverAuthToken = "tok-two" // switch lands while the request is out
            ok("old-again")
        }
        val result = RecordingsRepository.refresh()

        assertTrue(result is ApiClient.ListResult.Error)
        assertTrue("old rows must not wait for the next refresh to disappear", RecordingsRepository.server.value.isEmpty())
    }

    @Test
    fun rowsFromAnEarlierServerDoNotSurviveAFailedRefreshOnTheNewOne() = runTest {
        RecordingsRepository.listSource = RecordingsRepository.ListSource { ok("old") }
        RecordingsRepository.refresh()

        RecordingStore.serverAuthToken = "tok-two"
        RecordingsRepository.listSource = RecordingsRepository.ListSource { ApiClient.ListResult.Error("offline") }
        RecordingsRepository.refresh()

        assertTrue("a stale snapshot is worse than an empty one", RecordingsRepository.server.value.isEmpty())
    }

    @Test
    fun unconfiguredServerClearsTheSnapshot() = runTest {
        RecordingsRepository.listSource = RecordingsRepository.ListSource { ok("a") }
        RecordingsRepository.refresh()
        RecordingStore.serverAuthToken = null
        val result = RecordingsRepository.refresh()
        assertTrue(result is ApiClient.ListResult.Error)
        assertTrue(RecordingsRepository.server.value.isEmpty())
    }
}
