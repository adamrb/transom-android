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
 *   POST /api/v1/plaud/user-token                {"user_id": ...} -> {"access_token", ...}
 *   POST /api/v1/recordings                      multipart "file" + "metadata"
 *   GET  /api/v1/recordings/lookup?device_sn=..&session_id=..
 *   GET  /api/v1/recordings/{id}/transcript      200 ready / 404,409 pending
 */
object ApiClient {

    private const val TAG = "ApiClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
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

    /**
     * Verify the server auth token by requesting a Plaud user token for [userId] against an
     * explicit base URL + token (used by onboarding "Test connection"). Returns the access token.
     */
    fun fetchUserToken(base: String, authToken: String, userId: String): String {
        val body = JSONObject().put("user_id", userId).toString().toRequestBody(jsonType)
        val req = Request.Builder()
            .url("${base.trimEnd('/')}/api/v1/plaud/user-token")
            .header("Authorization", "Bearer $authToken")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                AppLog.w(TAG, "user-token failed: HTTP ${resp.code} ${text.take(200)}")
                throw ApiException(resp.code, text.take(200))
            }
            val token = JSONObject(text).optString("access_token")
            if (token.isBlank()) throw ApiException(resp.code, "no access_token in response")
            return token
        }
    }

    /** Fetch a fresh Plaud user access token using the stored server settings. */
    fun fetchUserToken(userId: String): String =
        fetchUserToken(
            baseUrl(),
            RecordingStore.serverAuthToken ?: throw IllegalStateException("Server auth token not configured"),
            userId
        )

    // MARK: - Recording upload

    data class UploadResult(val id: String?, val duplicate: Boolean)

    /**
     * POST /api/v1/recordings (multipart/form-data).
     * 201 -> stored; 200 with duplicate:true -> the server already has it (also success).
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
                AppLog.w(TAG, "upload failed: HTTP ${resp.code} ${text.take(200)}")
                throw ApiException(resp.code, text.take(200))
            }
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            return UploadResult(
                id = json.optString("id").takeIf { it.isNotBlank() },
                duplicate = json.optBoolean("duplicate", false)
            )
        }
    }

    // MARK: - Transcript

    /** GET /api/v1/recordings/lookup — server-side recording id for (device_sn, session_id), or null. */
    fun lookupRecordingId(deviceSn: String, sessionId: Long): String? {
        val url = "${baseUrl()}/api/v1/recordings/lookup?device_sn=$deviceSn&session_id=$sessionId"
        val req = Request.Builder().url(url).header("Authorization", authHeader()).get().build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                AppLog.w(TAG, "lookup failed: HTTP ${resp.code} ${text.take(200)}")
                return null
            }
            return JSONObject(text).optString("id").takeIf { it.isNotBlank() }
        }
    }

    sealed class TranscriptResult {
        /** rawJson = {"text": "...", "segments": [...]} as returned by the server. */
        data class Ready(val rawJson: String) : TranscriptResult()
        object Pending : TranscriptResult()
        data class Error(val message: String) : TranscriptResult()
    }

    /** GET /api/v1/recordings/{id}/transcript — Ready when done, Pending on 404/409. */
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
                    resp.code == 404 || resp.code == 409 -> TranscriptResult.Pending
                    else -> TranscriptResult.Error("HTTP ${resp.code}: ${text.take(200)}")
                }
            }
        } catch (e: Exception) {
            TranscriptResult.Error(e.message ?: "network error")
        }
    }
}
