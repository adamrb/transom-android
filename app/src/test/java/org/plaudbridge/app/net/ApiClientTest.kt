package org.plaudbridge.app.net

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Contract tests for ApiClient against a MockWebServer.
 *
 * The upload contract is the critical one: only a strictly validated response may ever lead to
 * markAsUploaded / device deletion, so every malformed "success" here must throw.
 */
@RunWith(RobolectricTestRunner::class)
class ApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var audioFile: File

    @Before
    fun setUp() {
        RecordingStore.init(ApplicationProvider.getApplicationContext())
        RecordingStore.clearAll()
        server = MockWebServer()
        server.start()
        RecordingStore.serverBaseUrl = server.url("/").toString().trimEnd('/')
        RecordingStore.serverAuthToken = "test-token"
        audioFile = File.createTempFile("rec", ".mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        audioFile.delete()
    }

    private fun upload(): ApiClient.UploadResult =
        ApiClient.uploadRecording(audioFile, 42L, "SN1", null, null)

    // MARK: - Upload contract

    @Test
    fun upload201FreshStoreIsAccepted() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody("""{"id":"abc","duplicate":false}""")
        )
        val result = upload()
        assertEquals("abc", result.id)
        assertEquals(false, result.duplicate)
        val recorded = server.takeRequest()
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun upload200DuplicateIsAccepted() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"id":"abc","duplicate":true}""")
        )
        val result = upload()
        assertEquals("abc", result.id)
        assertEquals(true, result.duplicate)
    }

    @Test
    fun upload200HtmlPageIsRejected() {
        // e.g. a captive portal / proxy login page answering 200 — must NOT count as uploaded.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>Please log in</body></html>")
        )
        assertThrows(ApiClient.ApiException::class.java) { upload() }
    }

    @Test
    fun uploadMissingIdIsRejected() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody("""{"duplicate":false}""")
        )
        assertThrows(ApiClient.ApiException::class.java) { upload() }
    }

    @Test
    fun uploadMissingDuplicateFlagIsRejected() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody("""{"id":"abc"}""")
        )
        assertThrows(ApiClient.ApiException::class.java) { upload() }
    }

    @Test
    fun upload201WithDuplicateTrueViolatesContract() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody("""{"id":"abc","duplicate":true}""")
        )
        assertThrows(ApiClient.ApiException::class.java) { upload() }
    }

    @Test
    fun upload200WithDuplicateFalseViolatesContract() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"id":"abc","duplicate":false}""")
        )
        assertThrows(ApiClient.ApiException::class.java) { upload() }
    }

    @Test
    fun uploadRedirectIsNotFollowed() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/elsewhere").toString())
        )
        // If redirects were followed, this second response would be consumed and validated.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"id":"evil","duplicate":true}""")
        )
        assertThrows(ApiClient.ApiException::class.java) { upload() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun uploadNon2xxIsRejected() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertThrows(ApiClient.ApiException::class.java) { upload() }
    }

    // MARK: - Token fetch

    @Test
    fun fetchUserTokenParsesAccessTokenAndExpiresIn() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"tok123","expires_in":86400}""")
        )
        val token = ApiClient.fetchUserToken("pb_user")
        assertEquals("tok123", token.accessToken)
        assertEquals(86400L, token.expiresInSec)
    }

    @Test
    fun fetchUserTokenWithoutExpiresInDefaultsToZero() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"access_token":"tok123"}""")
        )
        assertEquals(0L, ApiClient.fetchUserToken("pb_user").expiresInSec)
    }

    @Test
    fun fetchUserTokenMissingAccessTokenThrows() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"other":"x"}"""))
        assertThrows(ApiClient.ApiException::class.java) { ApiClient.fetchUserToken("pb_user") }
    }

    @Test
    fun fetchUserTokenNonJsonThrows() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>hi</html>"))
        assertThrows(ApiClient.ApiException::class.java) { ApiClient.fetchUserToken("pb_user") }
    }

    // MARK: - Transcript status mapping

    @Test
    fun transcript200IsReady() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"text":"hello","segments":[]}""")
        )
        val result = ApiClient.fetchTranscript("rid")
        assertTrue(result is ApiClient.TranscriptResult.Ready)
        assertEquals("""{"text":"hello","segments":[]}""",
            (result as ApiClient.TranscriptResult.Ready).rawJson)
    }

    @Test
    fun transcript409IsPending() {
        server.enqueue(MockResponse().setResponseCode(409))
        assertTrue(ApiClient.fetchTranscript("rid") is ApiClient.TranscriptResult.Pending)
    }

    @Test
    fun transcript404IsNotFoundNotPending() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(ApiClient.fetchTranscript("rid") is ApiClient.TranscriptResult.NotFound)
    }

    @Test
    fun transcript401IsAuthError() {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = ApiClient.fetchTranscript("rid")
        assertTrue(result is ApiClient.TranscriptResult.AuthError)
        assertEquals(401, (result as ApiClient.TranscriptResult.AuthError).code)
    }

    @Test
    fun transcript500IsError() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(ApiClient.fetchTranscript("rid") is ApiClient.TranscriptResult.Error)
    }

    // MARK: - Lookup status mapping

    @Test
    fun lookup200WithIdIsFound() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"srv-1"}"""))
        val result = ApiClient.lookupRecordingId("SN1", 42L)
        assertTrue(result is ApiClient.LookupResult.Found)
        assertEquals("srv-1", (result as ApiClient.LookupResult.Found).id)
    }

    @Test
    fun lookup404IsNotFound() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(ApiClient.lookupRecordingId("SN1", 42L) is ApiClient.LookupResult.NotFound)
    }

    @Test
    fun lookup403IsAuthError() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(ApiClient.lookupRecordingId("SN1", 42L) is ApiClient.LookupResult.AuthError)
    }

    @Test
    fun lookup500IsError() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(ApiClient.lookupRecordingId("SN1", 42L) is ApiClient.LookupResult.Error)
    }

    @Test
    fun lookup200WithoutIdIsError() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))
        assertTrue(ApiClient.lookupRecordingId("SN1", 42L) is ApiClient.LookupResult.Error)
    }
}
