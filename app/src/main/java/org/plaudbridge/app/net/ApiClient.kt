package org.plaudbridge.app.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.plaudbridge.app.common.AppLog
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

    private fun baseUrl(): String =
        RecordingStore.serverBaseUrl?.trimEnd('/')
            ?: throw IllegalStateException("Server URL not configured")

    private fun authHeader(): String =
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
        durationSec: Double?
    ): UploadResult {
        val metadata = JSONObject().apply {
            put("session_id", sessionId)
            put("device_sn", deviceSn)
            put("started_at", startedAtIso ?: JSONObject.NULL)
            put("duration_s", durationSec ?: JSONObject.NULL)
            put("source", "plaud-bridge-android")
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
}
