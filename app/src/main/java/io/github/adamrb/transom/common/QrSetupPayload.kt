package io.github.adamrb.transom.common

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * The setup QR shown by the transom-server dashboard encodes a JSON string:
 *
 *     {"v":1,"url":"https://bridge.example.com","token":"..."}
 *
 * Parsing is strict about types: `v` must be the integer 1 (a future server can bump it to
 * signal an incompatible payload), and `url`/`token` must be non-blank JSON strings —
 * JSONObject's optString would happily coerce numbers/booleans, so raw values are type-checked.
 *
 * URL validation goes through OkHttp's HttpUrl (the same parser every later request uses):
 * https only, userinfo (`https://good.com@evil.com`) and fragments rejected, and the host
 * canonicalized to its lowercase ASCII/punycode form — a Unicode homograph like
 * `https://bridgе.example` surfaces as `xn--...`, which the confirmation dialog then shows.
 * A scanned server is NEVER contacted or persisted without that dialog (see
 * ServerSetupActivity).
 */
data class QrSetupPayload(
    /** Canonical base URL (lowercase punycode host, default port dropped, no trailing slash). */
    val url: String,
    val token: String,
    /** Canonical ASCII host, for the confirmation dialog. */
    val host: String,
    /** Effective port (explicit or the https default 443), for the confirmation dialog. */
    val port: Int
) {

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
                return Result.Failure(Error.NOT_JSON, "not a Transom setup code")
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

            val rawUrl = (obj.opt("url") as? String)?.trim()
            if (rawUrl.isNullOrBlank()) {
                return Result.Failure(Error.MISSING_FIELD, "missing server URL")
            }
            val token = (obj.opt("token") as? String)?.trim()
            if (token.isNullOrBlank()) {
                return Result.Failure(Error.MISSING_FIELD, "missing auth token")
            }

            val httpUrl = rawUrl.toHttpUrlOrNull()
                ?: return Result.Failure(Error.BAD_URL, "invalid server URL")
            if (httpUrl.scheme != "https") {
                return Result.Failure(Error.BAD_URL, "server URL must use https://")
            }
            if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) {
                // https://bridge.example.com@evil.example — the real host is evil.example
                return Result.Failure(Error.BAD_URL, "server URL must not contain credentials")
            }
            if (httpUrl.fragment != null) {
                return Result.Failure(Error.BAD_URL, "server URL must not contain a #fragment")
            }
            if (httpUrl.query != null) {
                return Result.Failure(Error.BAD_URL, "server URL must not contain a query string")
            }

            // Canonical form via HttpUrl: lowercase punycode host, default port omitted.
            val canonical = httpUrl.newBuilder().build().toString().trimEnd('/')
            return Result.Success(
                QrSetupPayload(
                    url = canonical,
                    token = token,
                    host = httpUrl.host,
                    port = httpUrl.port
                )
            )
        }
    }
}
