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

    // MARK: - Library: list / fetch / rename / delete / retranscribe

    private val recordingJson = """{"id":"rec-1","device_sn":"SN1","session_id":42,"filename":"42.mp3",
        "size_bytes":10,"duration_s":61.0,"started_at":"2026-09-07T05:27:31Z","uploaded_at":"2026-09-07T05:30:00Z",
        "source":"plaud-bridge-android","status":"done","title":"Budget call","summary":null,"marks":[6.0],
        "has_transcript":true,"text_preview":"Hello","error":null}"""

    @Test
    fun listRecordingsHitsPathWithPagingAndAuth() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"recordings":[$recordingJson]}"""))
        val result = ApiClient.listRecordings()
        assertTrue(result is ApiClient.ListResult.Ok)
        val recordings = (result as ApiClient.ListResult.Ok).recordings
        assertEquals(1, recordings.size)
        assertEquals("Budget call", recordings[0].displayTitle)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/recordings?limit=200&offset=0", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun listRecordingsEncodesSearchQuery() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"recordings":[]}"""))
        ApiClient.listRecordings("budget q3")
        assertEquals("/api/v1/recordings?limit=200&offset=0&q=budget%20q3", server.takeRequest().path)
    }

    @Test
    fun listRecordingsBlankQueryIsNoFilter() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"recordings":[]}"""))
        ApiClient.listRecordings("   ")
        assertEquals("/api/v1/recordings?limit=200&offset=0", server.takeRequest().path)
    }

    @Test
    fun listRecordings401IsAuthError() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ApiClient.ListResult.AuthError(401), ApiClient.listRecordings())
    }

    @Test
    fun listRecordings500IsError() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(ApiClient.listRecordings() is ApiClient.ListResult.Error)
    }

    @Test
    fun listRecordingsHtmlBodyIsError() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))
        assertTrue(ApiClient.listRecordings() is ApiClient.ListResult.Error)
    }

    @Test
    fun listRecordingsUnreachableServerIsError() {
        RecordingStore.serverBaseUrl = "http://127.0.0.1:1" // nothing listens on port 1
        assertTrue(ApiClient.listRecordings() is ApiClient.ListResult.Error)
    }

    @Test
    fun fetchRecordingParsesObject() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(recordingJson))
        val result = ApiClient.fetchRecording("rec-1")
        assertTrue(result is ApiClient.RecordingResult.Ok)
        assertEquals("rec-1", (result as ApiClient.RecordingResult.Ok).recording.id)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/recordings/rec-1", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun fetchRecording404IsNotFound() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ApiClient.RecordingResult.NotFound, ApiClient.fetchRecording("gone"))
    }

    @Test
    fun renameRecordingPatchesTitle() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(recordingJson.replace("Budget call", "New name")))
        val result = ApiClient.renameRecording("rec-1", "New name")
        assertTrue(result is ApiClient.RecordingResult.Ok)
        assertEquals("New name", (result as ApiClient.RecordingResult.Ok).recording.title)
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/v1/recordings/rec-1", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        assertEquals("""{"title":"New name"}""", recorded.body.readUtf8())
    }

    @Test
    fun renameRecording422IsError() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"title required"}"""))
        assertTrue(ApiClient.renameRecording("rec-1", "") is ApiClient.RecordingResult.Error)
    }

    @Test
    fun renameRecording403IsAuthError() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ApiClient.RecordingResult.AuthError(403), ApiClient.renameRecording("rec-1", "x"))
    }

    @Test
    fun deleteRecording204IsOk() {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(ApiClient.ActionResult.Ok, ApiClient.deleteRecording("rec-1"))
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/v1/recordings/rec-1", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun deleteRecording404IsNotFound() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ApiClient.ActionResult.NotFound, ApiClient.deleteRecording("rec-1"))
    }

    @Test
    fun deleteRecording401IsAuthError() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ApiClient.ActionResult.AuthError(401), ApiClient.deleteRecording("rec-1"))
    }

    @Test
    fun deleteRecording500IsError() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(ApiClient.deleteRecording("rec-1") is ApiClient.ActionResult.Error)
    }

    @Test
    fun retranscribePostsToSubresource() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"rec-1","status":"pending"}"""))
        assertEquals(ApiClient.ActionResult.Ok, ApiClient.retranscribe("rec-1"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/recordings/rec-1/retranscribe", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun retranscribe403IsAuthError() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ApiClient.ActionResult.AuthError(403), ApiClient.retranscribe("rec-1"))
    }

    @Test
    fun recordingAudioUrlIsUnderTheApi() {
        assertEquals(RecordingStore.serverBaseUrl + "/api/v1/recordings/rec-1/audio", ApiClient.recordingAudioUrl("rec-1"))
    }

    // MARK: - Custom vocabulary

    private val vocabularyJson = """{"entries":[
        {"term":"Plaud Bridge","aliases":["Plogged Bridge","Plod Bridge"],"source":"manual"},
        {"term":"Morgan","aliases":[],"source":"obsidian"}],
        "editor_text":"Morgan\nPlaud Bridge = Plogged Bridge, Plod Bridge","hotwords":"Plaud Bridge, Morgan"}"""

    @Test
    fun fetchVocabularyGetsEntriesAndEditorText() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(vocabularyJson))
        val result = ApiClient.fetchVocabulary()
        assertTrue(result.toString(), result is ApiClient.VocabularyResult.Ok)
        val ok = result as ApiClient.VocabularyResult.Ok
        assertEquals(
            listOf(
                org.plaudbridge.app.models.VocabEntry("Plaud Bridge", listOf("Plogged Bridge", "Plod Bridge"), "manual"),
                org.plaudbridge.app.models.VocabEntry("Morgan", emptyList(), "obsidian")
            ),
            ok.entries
        )
        assertEquals("Morgan\nPlaud Bridge = Plogged Bridge, Plod Bridge", ok.editorText)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/vocabulary", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun fetchVocabulary404IsUnsupported() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}"""))
        assertEquals(ApiClient.VocabularyResult.Unsupported, ApiClient.fetchVocabulary())
    }

    @Test
    fun fetchVocabulary401IsAuthError() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ApiClient.VocabularyResult.AuthError(401), ApiClient.fetchVocabulary())
    }

    @Test
    fun fetchVocabularyGarbageIsError() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))
        assertTrue(ApiClient.fetchVocabulary() is ApiClient.VocabularyResult.Error)
    }

    @Test
    fun saveVocabularyPutsEntriesAndRendersEditorTextLocally() {
        // The PUT response has no editor_text; the client renders the kept entries itself.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"entries":[{"term":"Plaud Bridge","aliases":["Plogged Bridge"],"source":"manual"},
                {"term":"Morgan","aliases":[],"source":"obsidian"}]}"""
            )
        )
        val result = ApiClient.saveVocabulary(
            listOf(
                org.plaudbridge.app.models.VocabEntry("Plaud Bridge", listOf("Plogged Bridge"), "manual"),
                org.plaudbridge.app.models.VocabEntry("Morgan", emptyList(), "obsidian")
            )
        )
        assertTrue(result.toString(), result is ApiClient.VocabularyResult.Ok)
        val ok = result as ApiClient.VocabularyResult.Ok
        assertEquals(2, ok.entries.size)
        assertEquals("Morgan\nPlaud Bridge = Plogged Bridge", ok.editorText)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/api/v1/vocabulary", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        assertEquals(
            """{"entries":[{"term":"Plaud Bridge","aliases":["Plogged Bridge"],"source":"manual"},""" +
                """{"term":"Morgan","aliases":[],"source":"obsidian"}]}""",
            recorded.body.readUtf8()
        )
    }

    @Test
    fun saveVocabulary422IsError() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":[{"msg":"too long"}]}"""))
        val result = ApiClient.saveVocabulary(listOf(org.plaudbridge.app.models.VocabEntry("x".repeat(80))))
        assertTrue(result is ApiClient.VocabularyResult.Error)
        assertTrue((result as ApiClient.VocabularyResult.Error).message.contains("422"))
    }

    @Test
    fun saveVocabulary403IsAuthError() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ApiClient.VocabularyResult.AuthError(403), ApiClient.saveVocabulary(emptyList()))
    }

    // MARK: - Web sign-in approval

    private val loginId = "abcDEF123456_-abcDEF123456_-0123"

    @Test
    fun approveLoginPostsLabelToTheApprovePathWithAuth() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"approved","label":"Web · Pixel","session_id":"s1"}"""))
        assertEquals(ApiClient.ApproveLoginResult.Ok, ApiClient.approveLogin(loginId, "Web · Pixel"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/login-requests/$loginId/approve", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"label":"Web · Pixel"}""", recorded.body.readUtf8())
    }

    @Test
    fun approveLoginOmitsABlankLabelAndCutsALongOne() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"approved"}"""))
        ApiClient.approveLogin(loginId, "   ")
        assertEquals("{}", server.takeRequest().body.readUtf8())

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"approved"}"""))
        ApiClient.approveLogin(loginId, "x".repeat(200))
        assertEquals("""{"label":"${"x".repeat(ApiClient.LOGIN_LABEL_MAX)}"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun approveLogin404IsExpired() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"unknown or expired"}"""))
        assertEquals(ApiClient.ApproveLoginResult.Expired, ApiClient.approveLogin(loginId, "Web"))
    }

    @Test
    fun approveLogin409IsAlreadyUsed() {
        server.enqueue(MockResponse().setResponseCode(409))
        assertEquals(ApiClient.ApproveLoginResult.AlreadyUsed, ApiClient.approveLogin(loginId, "Web"))
    }

    @Test
    fun approveLogin401And403AreAuthErrors() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ApiClient.ApproveLoginResult.AuthError(401), ApiClient.approveLogin(loginId, "Web"))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ApiClient.ApproveLoginResult.AuthError(403), ApiClient.approveLogin(loginId, "Web"))
    }

    @Test
    fun approveLogin500AndUnreachableAreErrors() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(ApiClient.ApproveLoginResult.Error("HTTP 500"), ApiClient.approveLogin(loginId, "Web"))
        RecordingStore.serverBaseUrl = "http://127.0.0.1:1" // nothing listens on port 1
        val result = ApiClient.approveLogin(loginId, "Web")
        assertTrue(result.toString(), result is ApiClient.ApproveLoginResult.Error)
        assertTrue((result as ApiClient.ApproveLoginResult.Error).message.isNotBlank())
    }

    @Test
    fun approveLoginRedirectIsNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere").toString()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"approved"}"""))
        assertEquals(ApiClient.ApproveLoginResult.Error("HTTP 302"), ApiClient.approveLogin(loginId, "Web"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun approveLoginRefusesAMalformedIdWithoutAnyRequest() {
        // Belt and braces: the payload parser already enforces the pattern, but the id becomes a
        // path segment, so the client must never build a URL from an unchecked one.
        assertTrue(ApiClient.approveLogin("../admin", "Web") is ApiClient.ApproveLoginResult.Error)
        assertTrue(ApiClient.approveLogin("short", "Web") is ApiClient.ApproveLoginResult.Error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun approveLoginGoesOnlyToTheConfiguredServerNeverToTheCodesUrl() {
        // A second server stands in for the URL inside a QR code. approveLogin takes only the
        // request id, so the configured server is the only place the request can go.
        val elsewhere = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"approved"}"""))
            assertEquals(ApiClient.ApproveLoginResult.Ok, ApiClient.approveLogin(loginId, "Web"))
            assertEquals(1, server.requestCount)
            assertEquals(0, elsewhere.requestCount)
            assertEquals("/api/v1/login-requests/$loginId/approve", server.takeRequest().path)
        } finally {
            elsewhere.shutdown()
        }
    }
}
