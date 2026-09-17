package io.github.adamrb.transom.common

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * The sign-in QR shown on the web dashboard's login screen encodes a JSON string:
 *
 *     {"v":1,"kind":"login","url":"https://bridge.example.com","id":"<opaque request id>"}
 *
 * Scanning it with the phone approves a short-lived login request so the browser that showed
 * the code gets its own session (the WhatsApp Web pattern). The phone's token never leaves the
 * phone except to the configured server, so the payload carries no secret, only the request id.
 *
 * Parsing is as strict as [QrSetupPayload]: `v` must be the integer 1, `kind` must be exactly
 * "login" (a setup code has no kind and would be rejected here, and vice versa), `url` goes
 * through OkHttp's HttpUrl (https only, no userinfo/query/fragment, host canonicalized to its
 * lowercase punycode form so lookalike domains are visible), and `id` must be a url-safe token
 * of 16 to 64 characters because it becomes a path segment of the approve request.
 *
 * The url is only used to check that the code belongs to the configured server (see
 * QrLoginApproval). The approve request itself is always sent to the configured server.
 */
data class QrLoginPayload(
    /** Canonical base URL (lowercase punycode host, default port dropped, no trailing slash). */
    val url: String,
    /** Canonical ASCII host, for the dialogs. */
    val host: String,
    /** Effective port (explicit or the https default 443), for the dialogs. */
    val port: Int,
    /** Opaque login request id, validated against [ID_PATTERN]. */
    val id: String
) {

    enum class Error { NOT_JSON, UNSUPPORTED_VERSION, WRONG_KIND, MISSING_FIELD, BAD_URL, BAD_ID }

    sealed class Result {
        data class Success(val payload: QrLoginPayload) : Result()
        data class Failure(val error: Error, val reason: String) : Result()
    }

    companion object {
        const val SUPPORTED_VERSION = 1
        const val KIND = "login"

        /** Url-safe base64 alphabet; the server issues 32 characters, bounds leave room to change. */
        val ID_PATTERN: Regex = Regex("^[A-Za-z0-9_-]{16,64}$")

        /**
         * True when [raw] is a JSON object declaring `"kind":"login"`, regardless of whether the
         * rest is valid. Used by the setup scanner to say "this is a sign-in code" instead of
         * a generic parse error.
         */
        fun isLoginKind(raw: String?): Boolean {
            if (raw.isNullOrBlank()) return false
            val obj = try { JSONObject(raw) } catch (_: Exception) { return false }
            return obj.opt("kind") == KIND
        }

        fun parse(raw: String?): Result {
            if (raw.isNullOrBlank()) {
                return Result.Failure(Error.NOT_JSON, "empty QR code")
            }
            val obj = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return Result.Failure(Error.NOT_JSON, "not a Transom sign-in code")
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

            // Strict equality on the raw value: a missing kind (setup code) and a mistyped one
            // are both "not a sign-in code".
            if (obj.opt("kind") != KIND) {
                return Result.Failure(Error.WRONG_KIND, "not a sign-in code")
            }

            val rawUrl = (obj.opt("url") as? String)?.trim()
            if (rawUrl.isNullOrBlank()) {
                return Result.Failure(Error.MISSING_FIELD, "missing server URL")
            }
            val id = (obj.opt("id") as? String)?.trim()
            if (id.isNullOrBlank()) {
                return Result.Failure(Error.MISSING_FIELD, "missing request id")
            }
            if (!ID_PATTERN.matches(id)) {
                return Result.Failure(Error.BAD_ID, "malformed request id")
            }

            val httpUrl = rawUrl.toHttpUrlOrNull()
                ?: return Result.Failure(Error.BAD_URL, "invalid server URL")
            if (httpUrl.scheme != "https") {
                return Result.Failure(Error.BAD_URL, "server URL must use https://")
            }
            if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) {
                // https://bridge.example.com@evil.example: the real host is evil.example
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
                QrLoginPayload(
                    url = canonical,
                    host = httpUrl.host,
                    port = httpUrl.port,
                    id = id
                )
            )
        }
    }
}
