package org.plaudbridge.app.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.models.VocabEntry
import org.plaudbridge.app.models.VocabularyEditorText
import org.plaudbridge.app.storage.RecordingStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the self-hosted plaud-bridge-server.
 *
 * All methods are blocking and must be called off the main thread (callers use
 * Dispatchers.IO). Endpoints:
 *   GET  /api/v1/health                          (no auth)
 *   POST /api/v1/plaud/user-token                {"user_id": ...} -> {"access_token", "expires_in", ...}
 *   POST /api/v1/recordings                      multipart "file" + "metadata"
 *   GET  /api/v1/recordings/lookup?device_sn=..&session_id=..
 *   GET  /api/v1/recordings/{id}/transcript      200 ready / 409 pending
 *   PATCH /api/v1/recordings/{id}/marks          {"marks": list of seconds} -> {id, marks, highlights}
 *   GET  /api/v1/recordings?limit=&offset=&q=     {"recordings": [...]} newest first (Library)
 *   GET  /api/v1/recordings/{id}                  one recording object
 *   PATCH /api/v1/recordings/{id}                 {"title": ...} -> renamed object (422 on empty)
 *   POST /api/v1/recordings/{id}/retranscribe     {"id", "status": "pending"}
 *   DELETE /api/v1/recordings/{id}                204
 *   GET  /api/v1/recordings/{id}/audio            audio/mpeg with Range support (streamed by ExoPlayer)
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
            put("source", "plaud-bridge-android")
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
        data class Error(val message: String) : TranscriptResult()
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
        data class Error(val message: String) : RecordingResult()
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
                resp.code == 422 -> RecordingResult.Error("rejected by the server (422)")
                !resp.isSuccessful -> {
                    AppLog.w(TAG, "$what failed: HTTP ${resp.code} (${text.length} bytes)")
                    RecordingResult.Error("HTTP ${resp.code}")
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
        data class Error(val message: String) : ActionResult()
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

    private fun executeAction(req: Request, what: String): ActionResult = try {
        client.newCall(req).execute().use { resp ->
            when {
                resp.isSuccessful -> ActionResult.Ok
                resp.code == 404 -> ActionResult.NotFound
                resp.code == 401 || resp.code == 403 -> ActionResult.AuthError(resp.code)
                else -> {
                    AppLog.w(TAG, "$what failed: HTTP ${resp.code}")
                    ActionResult.Error("HTTP ${resp.code}")
                }
            }
        }
    } catch (e: Exception) {
        ActionResult.Error(e.message ?: "network error")
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
        if (!org.plaudbridge.app.common.QrLoginPayload.ID_PATTERN.matches(requestId)) {
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
