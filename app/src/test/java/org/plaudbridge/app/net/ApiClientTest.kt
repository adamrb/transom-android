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
    fun uploadNumericIdIsRejected() {
        // org.json optString would coerce 123 -> "123"; the contract requires a JSON string.
        server.enqueue(
            MockResponse().setResponseCode(201).setBody("""{"id":123,"duplicate":false}""")
        )
        assertThrows(ApiClient.ApiException::class.java) { upload() }
    }

    @Test
    fun uploadStringDuplicateIsRejected() {
        // org.json getBoolean would coerce "true" -> true; the contract requires a JSON boolean.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"id":"abc","duplicate":"true"}""")
        )
        assertThrows(ApiClient.ApiException::class.java) { upload() }
    }

    @Test
    fun uploadNullIdIsRejected() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody("""{"id":null,"duplicate":false}""")
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

    // MARK: - Upload metadata marks

    private fun metadataOf(body: String): org.json.JSONObject {
        // The multipart "metadata" part is a JSON object on its own line after its headers.
        val line = body.lines().first { it.trimStart().startsWith("{") && it.contains("session_id") }
        return org.json.JSONObject(line)
    }

    @Test
    fun uploadMetadataOmitsMarksWhenUnknown() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"abc","duplicate":false}"""))
        upload()
        val meta = metadataOf(server.takeRequest().body.readUtf8())
        assertEquals(42L, meta.getLong("session_id"))
        assertTrue("absent key means not read yet", !meta.has("marks"))
    }

    @Test
    fun uploadMetadataCarriesMarksWhenKnown() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"abc","duplicate":false}"""))
        ApiClient.uploadRecording(audioFile, 42L, "SN1", null, 60.0, marks = listOf(6.0, 125.5))
        val meta = metadataOf(server.takeRequest().body.readUtf8())
        assertEquals("[6,125.5]", meta.getJSONArray("marks").toString())
    }

    @Test
    fun uploadMetadataSendsEmptyMarksAsEmptyArray() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"abc","duplicate":false}"""))
        ApiClient.uploadRecording(audioFile, 42L, "SN1", null, null, marks = emptyList())
        val meta = metadataOf(server.takeRequest().body.readUtf8())
        assertEquals(0, meta.getJSONArray("marks").length())
    }

    // MARK: - PATCH marks

    @Test
    fun patchMarks200IsOkWithJsonBodyAndAuth() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"rid","marks":[6],"highlights":[]}"""))
        val result = ApiClient.patchMarks("rid", listOf(6.0, 125.5))
        assertEquals(ApiClient.PatchMarksResult.Ok, result)
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/v1/recordings/rid/marks", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        assertEquals("""{"marks":[6,125.5]}""", recorded.body.readUtf8())
    }

    @Test
    fun patchMarks404IsNotFound() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ApiClient.PatchMarksResult.NotFound, ApiClient.patchMarks("rid", listOf(1.0)))
    }

    @Test
    fun patchMarks403IsAuthError() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ApiClient.PatchMarksResult.AuthError(403), ApiClient.patchMarks("rid", listOf(1.0)))
    }

    @Test
    fun patchMarks500IsError() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(ApiClient.patchMarks("rid", listOf(1.0)) is ApiClient.PatchMarksResult.Error)
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

    @Test
    fun lookup200WithNumericIdIsError() {
        // Same strict typing as the upload contract: id must be a JSON string.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":123}"""))
        assertTrue(ApiClient.lookupRecordingId("SN1", 42L) is ApiClient.LookupResult.Error)
    }
}
