package cloud.adamrb.transom.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import cloud.adamrb.transom.common.AppLog
import cloud.adamrb.transom.common.ServerErrorText
import cloud.adamrb.transom.models.RoutingRun
import cloud.adamrb.transom.models.ServerRecording
import cloud.adamrb.transom.models.VocabEntry
import cloud.adamrb.transom.models.VocabularyEditorText
import cloud.adamrb.transom.storage.RecordingStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the self-hosted transom-server.
 *
 * All methods are blocking and must be called off the main thread (callers use
 * Dispatchers.IO). Endpoints:
 *   GET  /api/v1/health                          (no auth)
 *   POST /api/v1/plaud/user-token                {"user_id": ...} -> {"access_token", "expires_in", ...}
 *   POST /api/v1/recordings                      multipart "file" + "metadata"
 *   GET  /api/v1/recordings/lookup?device_sn=..&session_id=..
 *   GET  /api/v1/recordings/{id}/transcript      200 ready / 409 pending
 *   PATCH /api/v1/recordings/{id}/marks          {"marks": list of seconds} -> {id, marks, highlights}
 *   PATCH /api/v1/recordings/{id}/speakers       {"renames": {"Speaker 1": "Alex"}} -> the transcript document
 *   GET  /api/v1/recordings?limit=&offset=&q=     {"recordings": [...]} newest first (Library)
 *   GET  /api/v1/recordings/{id}                  one recording object
 *   PATCH /api/v1/recordings/{id}                 {"title": ...} -> renamed object (422 on empty)
 *   POST /api/v1/recordings/{id}/retranscribe     {"id", "status": "pending"}
 *   DELETE /api/v1/recordings/{id}                204
 *   GET  /api/v1/recordings/{id}/audio            audio/mpeg with Range support (streamed by ExoPlayer)
 *   GET  /api/v1/recordings/{id}/routing          {"runs": [...], "deliveries": [...]} newest run first
 *   POST /api/v1/recordings/{id}/route            re-run the AI router -> the new run object
 *                                                  (optional body {"instructions": ...}, Idempotency-Key header)
 *   POST /api/v1/deliveries/{id}/retry            retry a failed delivery (200 / 409 not retryable)
 *   GET  /api/v1/vocabulary                       {"entries": [...], "editor_text": "...", "hotwords": "..."}
 *   PUT  /api/v1/vocabulary                       {"entries": [...]} replaces the list -> {"entries": [...]}
 *   POST /api/v1/login-requests/{id}/approve      {"label": ...} -> {"status":"approved", ...} (web sign-in QR)
 *
 * Security notes:
 * - Redirects are disabled: a redirecting proxy must never turn into a spoofed "success"
 *   that leads to marking a recording uploaded (and possibly deleting it from the device).
 * - Logging is restricted to status codes and byte counts — never response bodies, tokens,
 *   or transcript content.
 */
object ApiClient {

    private const val TAG = "ApiClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val jsonType = "application/json".toMediaType()

    class ApiException(val code: Int, message: String) : Exception("HTTP $code: $message")

    /**
     * The server's human-readable `detail` for a rejected request ({"detail": "Automations are
     * turned off on the server"}), or null when the body is not JSON, has no string detail (FastAPI
     * validation errors carry a list), or is empty. Screens show this text, never the status code.
     */
    internal fun detailOf(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(body)
            val detail = obj.opt("detail")
            (detail as? String)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** Configured server root without a trailing slash; throws when onboarding never saved one. */
    internal fun baseUrl(): String =
        RecordingStore.serverBaseUrl?.trimEnd('/')
            ?: throw IllegalStateException("Server URL not configured")

    /**
     * "Bearer <token>" for the Authorization header. Internal (not private) because the detail
     * screen's ExoPlayer streams audio through its own HTTP stack and needs the same header.
     */
    internal fun authHeader(): String =
        "Bearer ${RecordingStore.serverAuthToken ?: throw IllegalStateException("Server auth token not configured")}"

    // MARK: - Health / auth verification

    /** GET /api/v1/health with an explicit base URL (used by onboarding before settings persist). */
    fun checkHealth(base: String): Boolean {
        val req = Request.Builder().url("${base.trimEnd('/')}/api/v1/health").get().build()
        client.newCall(req).execute().use { resp -> return resp.isSuccessful }
    }

    /** Result of POST /api/v1/plaud/user-token. [expiresInSec] <= 0 when the server omitted it. */
    data class UserToken(val accessToken: String, val expiresInSec: Long)

    /**
     * Verify the server auth token by requesting a Plaud user token for [userId] against an
     * explicit base URL + token (used by onboarding "Test connection").
     */
    fun fetchUserToken(base: String, authToken: String, userId: String): UserToken {
        val body = JSONObject().put("user_id", userId).toString().toRequestBody(jsonType)
        val req = Request.Builder()
            .url("${base.trimEnd('/')}/api/v1/plaud/user-token")
            .header("Authorization", "Bearer $authToken")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                AppLog.w(TAG, "user-token failed: HTTP ${resp.code} (${text.length} bytes)")
                throw ApiException(resp.code, "user-token request rejected")
            }
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw ApiException(resp.code, "user-token response is not valid JSON")
            }
            val token = json.optString("access_token")
            if (token.isBlank()) throw ApiException(resp.code, "no access_token in response")
            return UserToken(
                accessToken = token,
                expiresInSec = json.optLong("expires_in", 0L)
            )
        }
    }

    /** Fetch a fresh Plaud user access token using the stored server settings. */
    fun fetchUserToken(userId: String): UserToken =
        fetchUserToken(
            baseUrl(),
            RecordingStore.serverAuthToken ?: throw IllegalStateException("Server auth token not configured"),
            userId
        )

    // MARK: - Recording upload

    /** A STRICTLY validated upload result: [id] is always non-blank. */
    data class UploadResult(val id: String, val duplicate: Boolean)

    /**
     * POST /api/v1/recordings (multipart/form-data).
     *
     * Contract, enforced exactly (anything else throws and the upload is retried later —
     * a recording is only ever marked uploaded / deleted from the device on a validated result):
     *   201 + JSON {"id": <non-blank>, "duplicate": false}  -> stored now
     *   200 + JSON {"id": <non-blank>, "duplicate": true}   -> server already had it
     */
    fun uploadRecording(
        file: File,
        sessionId: Long,
        deviceSn: String,
        startedAtIso: String?,
        durationSec: Double?,
        marks: List<Double>? = null
    ): UploadResult {
        val metadata = JSONObject().apply {
            put("session_id", sessionId)
            put("device_sn", deviceSn)
            put("started_at", startedAtIso ?: JSONObject.NULL)
            put("duration_s", durationSec ?: JSONObject.NULL)
            put("source", "transom-android")
            // Only when known: an absent key means "not read yet", and the marks then follow via
            // patchMarks. An empty list is a real answer (no button presses) and is sent as such.
            if (marks != null) put("marks", marksJson(marks))
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mpeg".toMediaType()))
            .addFormDataPart("metadata", metadata.toString())
            .build()
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/recordings")
            .header("Authorization", authHeader())
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code != 200 && resp.code != 201) {
                AppLog.w(TAG, "upload failed: HTTP ${resp.code} (${text.length} bytes)")
                throw ApiException(resp.code, "upload rejected")
            }
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw ApiException(resp.code, "upload response is not valid JSON")
            }
            // Strict JSON types: optString/getBoolean COERCE ({"id":123} -> "123",
            // {"duplicate":"true"} -> true), which would let a malformed response pass
            // validation and ultimately trigger a device delete. Require the exact types.
            val id = json.opt("id")
            if (id !is String || id.isBlank()) {
                throw ApiException(resp.code, "upload response id must be a non-blank string")
            }
            val duplicate = json.opt("duplicate")
            if (duplicate !is Boolean) {
                throw ApiException(resp.code, "upload response duplicate must be a boolean")
            }
            // Exact contract pairing: 201 must be a fresh store, 200 must be a duplicate.
            val contractOk = (resp.code == 201 && !duplicate) || (resp.code == 200 && duplicate)
            if (!contractOk) {
                throw ApiException(resp.code, "upload response violates the status/duplicate contract")
            }
            return UploadResult(id = id, duplicate = duplicate)
        }
    }

    // MARK: - Marks

    private fun marksJson(marks: List<Double>): JSONArray = JSONArray().apply { marks.forEach { put(it) } }

    /** Typed result of PATCH /recordings/{id}/marks. */
    sealed class PatchMarksResult {
        object Ok : PatchMarksResult()
        /** 404: the server does not know this recording id (stale/foreign id). */
        object NotFound : PatchMarksResult()
        data class AuthError(val code: Int) : PatchMarksResult()
        data class Error(val message: String) : PatchMarksResult()
    }

    /**
     * PATCH /api/v1/recordings/{id}/marks: replace the recording's marks (offsets in seconds).
     * Used when the marks were read off the device after the audio had already been uploaded.
     * Never throws; every failure is a typed result so the caller can decide what to retry.
     */
    fun patchMarks(recordingId: String, marks: List<Double>): PatchMarksResult {
        val body = JSONObject().put("marks", marksJson(marks)).toString().toRequestBody(jsonType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/recordings/$recordingId/marks")
            .header("Authorization", authHeader())
            .patch(body)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.isSuccessful -> PatchMarksResult.Ok
                    resp.code == 404 -> PatchMarksResult.NotFound
                    resp.code == 401 || resp.code == 403 -> PatchMarksResult.AuthError(resp.code)
                    else -> {
                        AppLog.w(TAG, "patch marks failed: HTTP ${resp.code} (${text.length} bytes)")
                        PatchMarksResult.Error("HTTP ${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            PatchMarksResult.Error(e.message ?: "network error")
        }
    }

    // MARK: - Transcript

    /** Typed result of the recording-id lookup. */
    sealed class LookupResult {
        data class Found(val id: String) : LookupResult()
        /** 404: the server has no such recording (not uploaded / not registered yet). */
        object NotFound : LookupResult()
        data class AuthError(val code: Int) : LookupResult()
        data class Error(val message: String) : LookupResult()
    }

    /** GET /api/v1/recordings/lookup — server-side recording id for (device_sn, session_id). */
    fun lookupRecordingId(deviceSn: String, sessionId: Long): LookupResult {
        val url = "${baseUrl()}/api/v1/recordings/lookup?device_sn=$deviceSn&session_id=$sessionId"
        val req = Request.Builder().url(url).header("Authorization", authHeader()).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.code == 404 -> LookupResult.NotFound
                    resp.code == 401 || resp.code == 403 -> LookupResult.AuthError(resp.code)
                    !resp.isSuccessful -> {
                        AppLog.w(TAG, "lookup failed: HTTP ${resp.code} (${text.length} bytes)")
                        LookupResult.Error("HTTP ${resp.code}")
                    }
                    else -> {
                        // Same strictness as the upload contract: id must be a JSON string.
                        val id = try { JSONObject(text).opt("id") as? String } catch (e: Exception) { null }
                        if (id.isNullOrBlank()) LookupResult.Error("lookup response has no string id")
                        else LookupResult.Found(id)
                    }
                }
            }
        } catch (e: Exception) {
            LookupResult.Error(e.message ?: "network error")
        }
    }

    // MARK: - App update (APK hosted on the bridge server)

    /** Raw result of GET /api/v1/apk/info: 200 body text, 404 = no APK hosted. */
    sealed class ApkInfoResult {
        data class Ok(val json: String) : ApkInfoResult()
        object NotHosted : ApkInfoResult()
        data class Error(val message: String) : ApkInfoResult()
    }

    /** GET /api/v1/apk/info (Bearer auth). Parsing/validation happens in UpdateManager. */
    fun fetchApkInfo(): ApkInfoResult {
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/apk/info")
            .header("Authorization", authHeader())
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.code == 404 -> ApkInfoResult.NotHosted
                    !resp.isSuccessful -> {
                        AppLog.w(TAG, "apk info failed: HTTP ${resp.code} (${text.length} bytes)")
                        ApkInfoResult.Error("HTTP ${resp.code}")
                    }
                    else -> ApkInfoResult.Ok(text)
                }
            }
        } catch (e: Exception) {
            ApkInfoResult.Error(e.message ?: "network error")
        }
    }

    /**
     * GET /api/v1/apk/file (Bearer auth) → stream the APK binary to [dest], BOUNDED:
     * a Content-Length (when present) must equal [expectedSizeBytes], and streaming aborts as
     * soon as more than the expected size arrives — the server never gets to fill the disk and
     * fail verification afterwards. Throws on any HTTP/IO/bound failure (deleting the partial
     * file). The caller MUST still verify sha256/size/identity before doing anything with the
     * file — see UpdateManager.
     */
    fun downloadApk(dest: File, expectedSizeBytes: Long) {
        require(expectedSizeBytes > 0) { "expectedSizeBytes must be positive" }
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/apk/file")
            .header("Authorization", authHeader())
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.w(TAG, "apk download failed: HTTP ${resp.code}")
                    throw ApiException(resp.code, "apk download rejected")
                }
                val body = resp.body ?: throw ApiException(resp.code, "empty apk response")
                val contentLength = body.contentLength() // -1 when chunked/unknown
                if (contentLength >= 0 && contentLength != expectedSizeBytes) {
                    throw ApiException(
                        resp.code,
                        "apk Content-Length $contentLength does not match manifest size $expectedSizeBytes"
                    )
                }
                var written = 0L
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > expectedSizeBytes) {
                                throw ApiException(
                                    resp.code,
                                    "apk stream exceeded manifest size $expectedSizeBytes — aborted"
                                )
                            }
                            output.write(buf, 0, n)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    sealed class TranscriptResult {
        /** rawJson = {"text": "...", "segments": [...]} as returned by the server. */
        data class Ready(val rawJson: String) : TranscriptResult()
        /** 409: the server knows the recording but the transcription is not done yet. */
        object Pending : TranscriptResult()
        /** 404: the server does not know this recording id (stale/foreign id). */
        object NotFound : TranscriptResult()
        data class AuthError(val code: Int) : TranscriptResult()
        /** [detail] is the server's own sentence for a rejected request, when it sent one. */
        data class Error(val message: String, val detail: String? = null) : TranscriptResult()
    }

    /**
     * PATCH /api/v1/recordings/{id}/speakers with {"renames": {"Speaker 1": "Alex", ...}}: rename
     * speakers throughout the transcript. Answers with the full transcript document, the same
     * shape as [fetchTranscript], so the screen re-renders from it. 404 also means an older
     * server without the endpoint; 422 (blank new name) carries the server's `detail`.
     */
    fun renameSpeakers(recordingId: String, renames: Map<String, String>): TranscriptResult {
        val body = JSONObject().put("renames", JSONObject().apply {
            renames.forEach { (old, new) -> put(old, new) }
        }).toString().toRequestBody(jsonType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/recordings/$recordingId/speakers")
            .header("Authorization", authHeader())
            .patch(body)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.isSuccessful -> TranscriptResult.Ready(text)
                    resp.code == 404 -> TranscriptResult.NotFound
                    resp.code == 401 || resp.code == 403 -> TranscriptResult.AuthError(resp.code)
                    else -> {
                        AppLog.w(TAG, "rename speakers failed: HTTP ${resp.code} (${text.length} bytes)")
                        TranscriptResult.Error("HTTP ${resp.code}", detailOf(text))
                    }
                }
            }
        } catch (e: Exception) {
            TranscriptResult.Error(e.message ?: "network error")
        }
    }

    /** GET /api/v1/recordings/{id}/transcript. */
    fun fetchTranscript(recordingId: String): TranscriptResult {
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/recordings/$recordingId/transcript")
            .header("Authorization", authHeader())
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.isSuccessful -> TranscriptResult.Ready(text)
                    resp.code == 409 -> TranscriptResult.Pending
                    resp.code == 404 -> TranscriptResult.NotFound
                    resp.code == 401 || resp.code == 403 -> TranscriptResult.AuthError(resp.code)
                    else -> {
                        AppLog.w(TAG, "transcript failed: HTTP ${resp.code} (${text.length} bytes)")
                        TranscriptResult.Error("HTTP ${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            TranscriptResult.Error(e.message ?: "network error")
        }
    }

    // MARK: - Library (server-side recordings)

    /** Streaming URL for a recording's audio; the caller must send [authHeader] with it. */
    fun recordingAudioUrl(recordingId: String): String =
        "${baseUrl()}/api/v1/recordings/$recordingId/audio"

    /** Typed result of GET /api/v1/recordings. Never throws; auth and network problems are values. */
    sealed class ListResult {
        data class Ok(val recordings: List<ServerRecording>) : ListResult()
        data class AuthError(val code: Int) : ListResult()
        data class Error(val message: String) : ListResult()
    }

    /**
     * GET /api/v1/recordings?limit=200&offset=0[&q=...]: the server's recordings, newest first.
     * [query] is the dashboard's search (title and transcript text); blank means no filter.
     * 200 rows is far more than a personal server holds today, so paging is deferred.
     */
    fun listRecordings(query: String? = null): ListResult {
        val url = "${baseUrl()}/api/v1/recordings".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "200")
            .addQueryParameter("offset", "0")
            .apply { query?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("q", it) } }
            .build()
        val req = Request.Builder().url(url).header("Authorization", authHeader()).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.code == 401 || resp.code == 403 -> ListResult.AuthError(resp.code)
                    !resp.isSuccessful -> {
                        AppLog.w(TAG, "list recordings failed: HTTP ${resp.code} (${text.length} bytes)")
                        ListResult.Error("HTTP ${resp.code}")
                    }
                    else -> try {
                        ListResult.Ok(ServerRecording.listFromJson(text))
                    } catch (e: Exception) {
                        ListResult.Error("list response is not valid JSON")
                    }
                }
            }
        } catch (e: Exception) {
            ListResult.Error(e.message ?: "network error")
        }
    }

    /** Typed result of the single-recording reads and writes that return the recording object. */
    sealed class RecordingResult {
        data class Ok(val recording: ServerRecording) : RecordingResult()
        object NotFound : RecordingResult()
        data class AuthError(val code: Int) : RecordingResult()
        /** [detail] is the server's own sentence for a rejected request, when it sent one. */
        data class Error(val message: String, val detail: String? = null) : RecordingResult()
    }

    /** GET /api/v1/recordings/{id}. */
    fun fetchRecording(recordingId: String): RecordingResult {
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/recordings/$recordingId")
            .header("Authorization", authHeader())
            .get()
            .build()
        return executeForRecording(req, "fetch recording")
    }

    /**
     * PATCH /api/v1/recordings/{id} with {"title": ...}. The server answers 422 for an empty
     * title; that surfaces as [RecordingResult.Error] so the dialog can say why nothing changed.
     */
    fun renameRecording(recordingId: String, title: String): RecordingResult {
        val body = JSONObject().put("title", title).toString().toRequestBody(jsonType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/recordings/$recordingId")
            .header("Authorization", authHeader())
            .patch(body)
            .build()
        return executeForRecording(req, "rename recording")
    }

    private fun executeForRecording(req: Request, what: String): RecordingResult = try {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            when {
                resp.code == 404 -> RecordingResult.NotFound
                resp.code == 401 || resp.code == 403 -> RecordingResult.AuthError(resp.code)
                resp.code == 422 -> RecordingResult.Error("rejected by the server (422)", detailOf(text))
                !resp.isSuccessful -> {
                    AppLog.w(TAG, "$what failed: HTTP ${resp.code} (${text.length} bytes)")
                    RecordingResult.Error("HTTP ${resp.code}", detailOf(text))
                }
                else -> try {
                    RecordingResult.Ok(ServerRecording.fromJson(JSONObject(text)))
                } catch (e: Exception) {
                    RecordingResult.Error("$what response is not a recording")
                }
            }
        }
    } catch (e: Exception) {
        RecordingResult.Error(e.message ?: "network error")
    }

    /** Typed result of the body-less server actions (delete, re-transcribe). */
    sealed class ActionResult {
        object Ok : ActionResult()
        object NotFound : ActionResult()
        data class AuthError(val code: Int) : ActionResult()
        /**
         * [code] is the HTTP status when the server answered at all (null for a network failure);
         * [detail] its own sentence for the refusal, when it sent one. Screens show [detail] or a
         * generic line, never [message], which exists for logs and tests.
         */
        data class Error(val message: String, val code: Int? = null, val detail: String? = null) : ActionResult()
    }

    /** DELETE /api/v1/recordings/{id} (204). Removes audio and transcript on the server. */
    fun deleteRecording(recordingId: String): ActionResult {
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/recordings/$recordingId")
            .header("Authorization", authHeader())
            .delete()
            .build()
        return executeAction(req, "delete recording")
    }

    /** POST /api/v1/recordings/{id}/retranscribe: queue the recording for transcription again. */
    fun retranscribe(recordingId: String): ActionResult {
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/recordings/$recordingId/retranscribe")
            .header("Authorization", authHeader())
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return executeAction(req, "retranscribe")
    }

    private fun executeAction(req: Request, what: String, http: OkHttpClient = client): ActionResult = try {
        http.newCall(req).execute().use { resp ->
            when {
                resp.isSuccessful -> ActionResult.Ok
                resp.code == 404 -> ActionResult.NotFound
                resp.code == 401 || resp.code == 403 -> ActionResult.AuthError(resp.code)
                else -> {
                    AppLog.w(TAG, "$what failed: HTTP ${resp.code}")
                    ActionResult.Error("HTTP ${resp.code}", resp.code, detailOf(resp.body?.string()))
                }
            }
        }
    } catch (e: Exception) {
        ActionResult.Error(e.message ?: "network error")
    }

    // MARK: - Automations (AI router runs and their deliveries)

    /** Typed result of GET /api/v1/recordings/{id}/routing. Never throws once the server is configured. */
    sealed class RoutingResult {
        /** [runs] newest first; empty when routing is disabled or has not run yet. */
        data class Ok(val runs: List<RoutingRun>) : RoutingResult()
        /** 404: the recording is gone, or the server predates the routing endpoint. */
        object NotFound : RoutingResult()
        data class AuthError(val code: Int) : RoutingResult()
        data class Error(val message: String) : RoutingResult()
    }

    /** GET /api/v1/recordings/{id}/routing: every router run for the recording with its deliveries. */
    fun fetchRouting(recordingId: String): RoutingResult {
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/recordings/$recordingId/routing")
            .header("Authorization", authHeader())
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.code == 404 -> RoutingResult.NotFound
                    resp.code == 401 || resp.code == 403 -> RoutingResult.AuthError(resp.code)
                    !resp.isSuccessful -> {
                        AppLog.w(TAG, "fetch routing failed: HTTP ${resp.code} (${text.length} bytes)")
                        RoutingResult.Error("HTTP ${resp.code}")
                    }
                    else -> try {
                        RoutingResult.Ok(RoutingRun.listFromJson(text))
                    } catch (e: Exception) {
                        RoutingResult.Error("routing response is not valid JSON")
                    }
                }
            }
        } catch (e: Exception) {
            RoutingResult.Error(e.message ?: "network error")
        }
    }

    /**
     * The route endpoint runs the router model synchronously (two model attempts of up to 120 s
     * each, then up to five 30 s webhooks, about 390 s worst case); the client must outwait that
     * or it reports a failure for a run the server is still carrying out.
     */
    private const val REROUTE_READ_TIMEOUT_S = 600L

    /**
     * POST /api/v1/recordings/{id}/route: run the AI router over the recording again. The server
     * answers with the new run object, which the caller does not need: deliveries report back
     * asynchronously, so the screen re-reads [fetchRouting] a few seconds later instead. The
     * call is synchronous on the server (model call plus webhooks), so it gets its own read
     * timeout rather than the shared 120 s one, which a slow model could exceed while the run
     * still completes.
     */
    /** Longest instructions text the route endpoint accepts; longer ones are cut rather than rejected. */
    const val ROUTE_INSTRUCTIONS_MAX = 2000

    /**
     * Re-run the router. [idempotencyKey] (per user intent, reused on retry) makes a re-sent
     * request return the run the server already made instead of starting another one with
     * duplicate side effects; the server replays by key and dedupes concurrent duplicates.
     * [instructions], when given, travel as the JSON body {"instructions": ...} (trimmed, cut to
     * [ROUTE_INSTRUCTIONS_MAX]); blank instructions mean the same empty-bodied request as before.
     */
    fun rerunRouting(recordingId: String, idempotencyKey: String? = null, instructions: String? = null): ActionResult {
        val text = instructions?.trim()?.take(ROUTE_INSTRUCTIONS_MAX)?.takeIf { it.isNotEmpty() }
        val body = if (text == null) ByteArray(0).toRequestBody(null)
        else JSONObject().put("instructions", text).toString().toRequestBody(jsonType)
        val builder = Request.Builder()
            .url("${baseUrl()}/api/v1/recordings/$recordingId/route")
            .header("Authorization", authHeader())
            .post(body)
        if (!idempotencyKey.isNullOrBlank()) builder.header("Idempotency-Key", idempotencyKey)
        val req = builder.build()
        val patient = client.newBuilder().readTimeout(REROUTE_READ_TIMEOUT_S, TimeUnit.SECONDS).build()
        return executeAction(req, "rerun routing", patient)
    }

    /** Typed result of POST /api/v1/deliveries/{id}/retry. */
    sealed class RetryResult {
        object Ok : RetryResult()
        /** 409: the delivery is not in a failed state any more (already retried, or it succeeded). */
        object Conflict : RetryResult()
        object NotFound : RetryResult()
        data class AuthError(val code: Int) : RetryResult()
        /** [detail] is the server's own sentence for a rejected request, when it sent one. */
        data class Error(val message: String, val detail: String? = null) : RetryResult()
    }

    /** POST /api/v1/deliveries/{id}/retry: run a failed delivery again. */
    fun retryDelivery(deliveryId: String): RetryResult {
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/deliveries/$deliveryId/retry")
            .header("Authorization", authHeader())
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> RetryResult.Ok
                    resp.code == 409 -> RetryResult.Conflict
                    resp.code == 404 -> RetryResult.NotFound
                    resp.code == 401 || resp.code == 403 -> RetryResult.AuthError(resp.code)
                    else -> {
                        AppLog.w(TAG, "retry delivery failed: HTTP ${resp.code}")
                        RetryResult.Error("HTTP ${resp.code}", detailOf(resp.body?.string()))
                    }
                }
            }
        } catch (e: Exception) {
            RetryResult.Error(e.message ?: "network error")
        }
    }

    // MARK: - Custom vocabulary

    /** Typed result of the vocabulary reads and writes. Never throws once the server is configured. */
    sealed class VocabularyResult {
        /** [editorText] is the server's rendering of [entries] in the editor format. */
        data class Ok(val entries: List<VocabEntry>, val editorText: String) : VocabularyResult()
        /** 404: the server predates the vocabulary feature (the dashboard shows the same hint). */
        object Unsupported : VocabularyResult()
        data class AuthError(val code: Int) : VocabularyResult()
        data class Error(val message: String) : VocabularyResult()
    }

    /** GET /api/v1/vocabulary. */
    fun fetchVocabulary(): VocabularyResult {
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/vocabulary")
            .header("Authorization", authHeader())
            .get()
            .build()
        return executeForVocabulary(req, "fetch vocabulary")
    }

    /**
     * PUT /api/v1/vocabulary: REPLACE the whole list with [entries]. The server normalizes
     * (trims, dedupes case-insensitively, drops self-aliases) and answers with what it kept, so
     * callers should render the response rather than what they sent. 422 (a term over 64
     * characters, more than 20 aliases, more than 500 entries) surfaces as [VocabularyResult.Error].
     */
    fun saveVocabulary(entries: List<VocabEntry>): VocabularyResult {
        val body = JSONObject().put("entries", VocabEntry.listToJson(entries)).toString().toRequestBody(jsonType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/vocabulary")
            .header("Authorization", authHeader())
            .put(body)
            .build()
        return executeForVocabulary(req, "save vocabulary")
    }

    /** Typed result of the vault import. Never throws once the server is configured. */
    sealed class VocabularyImportResult {
        /** The server's merged list and how many entries were new. */
        data class Ok(val entries: List<VocabEntry>, val added: Int) : VocabularyImportResult()
        /** 404: the server predates the vocabulary feature. */
        object Unsupported : VocabularyImportResult()
        data class AuthError(val code: Int) : VocabularyImportResult()
        /** [detail] is the server's own sentence for a rejected request, when it sent one. */
        data class Error(val message: String, val detail: String? = null) : VocabularyImportResult()
    }

    /**
     * POST /api/v1/vocabulary/import: MERGE [entries] into the server's list (the endpoint the
     * server's own `contrib/vocab_from_obsidian.py` uses). A merge never removes anything:
     * existing terms and aliases stay, new ones are added, and the answer is the merged list plus
     * how many were new. The entries come from [VocabularyImport.parseGazetteer].
     */
    fun importVocabulary(entries: List<VocabEntry>): VocabularyImportResult {
        val body = JSONObject().put("entries", VocabEntry.listToJson(entries)).toString().toRequestBody(jsonType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/vocabulary/import")
            .header("Authorization", authHeader())
            .post(body)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.code == 404 -> VocabularyImportResult.Unsupported
                    resp.code == 401 || resp.code == 403 -> VocabularyImportResult.AuthError(resp.code)
                    !resp.isSuccessful -> {
                        AppLog.w(TAG, "import vocabulary failed: HTTP ${resp.code} (${text.length} bytes)")
                        VocabularyImportResult.Error("HTTP ${resp.code}", ServerErrorText.detailFrom(text))
                    }
                    else -> try {
                        val json = JSONObject(text)
                        VocabularyImportResult.Ok(
                            VocabEntry.listFromJson(json.optJSONArray("entries")),
                            json.optInt("added", 0)
                        )
                    } catch (e: Exception) {
                        VocabularyImportResult.Error("import vocabulary response is not valid JSON")
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "import vocabulary request failed", e)
            VocabularyImportResult.Error(e.message ?: "network error")
        }
    }

    private fun executeForVocabulary(req: Request, what: String): VocabularyResult = try {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            when {
                resp.code == 404 -> VocabularyResult.Unsupported
                resp.code == 401 || resp.code == 403 -> VocabularyResult.AuthError(resp.code)
                resp.code == 422 -> VocabularyResult.Error("rejected by the server (422)")
                !resp.isSuccessful -> {
                    AppLog.w(TAG, "$what failed: HTTP ${resp.code} (${text.length} bytes)")
                    VocabularyResult.Error("HTTP ${resp.code}")
                }
                else -> try {
                    val json = JSONObject(text)
                    val entries = VocabEntry.listFromJson(json.optJSONArray("entries"))
                    // The PUT response carries no editor_text; render it locally the way the
                    // server would so both results look the same to the editor.
                    val editorText =
                        if (json.has("editor_text") && !json.isNull("editor_text")) json.getString("editor_text")
                        else VocabularyEditorText.format(entries)
                    VocabularyResult.Ok(entries, editorText)
                } catch (e: Exception) {
                    VocabularyResult.Error("$what response is not valid JSON")
                }
            }
        }
    } catch (e: Exception) {
        VocabularyResult.Error(e.message ?: "network error")
    }

    // MARK: - Web dashboard sign-in (QR approval)

    /** Typed result of the sign-in approval. Never throws once the server is configured. */
    sealed class ApproveLoginResult {
        object Ok : ApproveLoginResult()
        /** 404: the login request expired (3 minutes) or never existed. */
        object Expired : ApproveLoginResult()
        /** 409: another approval already consumed this request. */
        object AlreadyUsed : ApproveLoginResult()
        data class AuthError(val code: Int) : ApproveLoginResult()
        data class Error(val message: String) : ApproveLoginResult()
    }

    /** Longest label the server accepts; longer ones are cut rather than rejected with a 422. */
    const val LOGIN_LABEL_MAX = 120

    /**
     * POST /api/v1/login-requests/{id}/approve with {"label": ...}: approve the browser that
     * displayed the sign-in QR. The server then mints a session token for THAT browser; the
     * phone's own token travels only in the Authorization header, and only to [baseUrl] (the
     * configured server), never to the URL inside the QR. [requestId] must already have passed
     * QrLoginPayload's pattern check; it is re-checked here because it becomes a path segment.
     */
    fun approveLogin(requestId: String, label: String?): ApproveLoginResult {
        if (!cloud.adamrb.transom.common.QrLoginPayload.ID_PATTERN.matches(requestId)) {
            return ApproveLoginResult.Error("malformed login request id")
        }
        val body = JSONObject().apply {
            val trimmed = label?.trim()?.take(LOGIN_LABEL_MAX)
            if (!trimmed.isNullOrEmpty()) put("label", trimmed)
        }.toString().toRequestBody(jsonType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/v1/login-requests/$requestId/approve")
            .header("Authorization", authHeader())
            .post(body)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> ApproveLoginResult.Ok
                    resp.code == 404 -> ApproveLoginResult.Expired
                    resp.code == 409 -> ApproveLoginResult.AlreadyUsed
                    resp.code == 401 || resp.code == 403 -> ApproveLoginResult.AuthError(resp.code)
                    else -> {
                        AppLog.w(TAG, "approve login failed: HTTP ${resp.code}")
                        ApproveLoginResult.Error("HTTP ${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            ApproveLoginResult.Error(e.message ?: "network error")
        }
    }
}
