package org.plaudbridge.app.common

import org.json.JSONObject

/**
 * The setup QR shown by the plaud-bridge-server dashboard encodes a JSON string:
 *
 *     {"v":1,"url":"https://bridge.example.com","token":"..."}
 *
 * Parsing is strict about types: `v` must be the integer 1 (a future server can bump it to
 * signal an incompatible payload), and `url`/`token` must be non-blank JSON strings —
 * JSONObject's optString would happily coerce numbers/booleans, so raw values are type-checked.
 * The URL must be https:// (the app rejects cleartext everywhere else too).
 */
data class QrSetupPayload(val url: String, val token: String) {

    enum class Error { NOT_JSON, UNSUPPORTED_VERSION, MISSING_FIELD, BAD_URL }

    sealed class Result {
        data class Success(val payload: QrSetupPayload) : Result()
        data class Failure(val error: Error, val reason: String) : Result()
    }

    companion object {
        const val SUPPORTED_VERSION = 1

        fun parse(raw: String?): Result {
            if (raw.isNullOrBlank()) {
                return Result.Failure(Error.NOT_JSON, "empty QR code")
            }
            val obj = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return Result.Failure(Error.NOT_JSON, "not a Plaud Bridge setup code")
            }

            // org.json parses whole JSON numbers as Int (or Long when they overflow Int).
            val version = when (val v = obj.opt("v")) {
                is Int -> v
                is Long -> return Result.Failure(Error.UNSUPPORTED_VERSION, "unsupported version $v")
                else -> return Result.Failure(Error.MISSING_FIELD, "missing version field")
            }
            if (version != SUPPORTED_VERSION) {
                return Result.Failure(Error.UNSUPPORTED_VERSION, "unsupported version $version")
            }

            val url = (obj.opt("url") as? String)?.trim()
            if (url.isNullOrBlank()) {
                return Result.Failure(Error.MISSING_FIELD, "missing server URL")
            }
            val token = (obj.opt("token") as? String)?.trim()
            if (token.isNullOrBlank()) {
                return Result.Failure(Error.MISSING_FIELD, "missing auth token")
            }
            if (!url.startsWith("https://")) {
                return Result.Failure(Error.BAD_URL, "server URL must start with https://")
            }
            return Result.Success(QrSetupPayload(url.trimEnd('/'), token))
        }
    }
}
