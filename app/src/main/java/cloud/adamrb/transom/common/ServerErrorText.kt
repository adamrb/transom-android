package cloud.adamrb.transom.common

import android.content.Context
import org.json.JSONObject
import cloud.adamrb.transom.R
import cloud.adamrb.transom.net.ApiClient
import java.io.IOException

/**
 * Turns what the network layer knows about a failure into one sentence for the user.
 *
 * Rules (shared with the web dashboard): show the server's own `detail` text when it sent one,
 * otherwise a fixed sentence per situation, and never a status code, an exception message or a
 * "HTTP nnn" string. Pure functions apart from string lookup so they are unit-testable.
 */
object ServerErrorText {

    /**
     * FastAPI's error body is `{"detail": "..."}`. Returns the detail when it is a plain, short,
     * human-readable string; null for validation arrays, empty bodies and non-JSON.
     */
    fun detailFrom(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val detail = try {
            JSONObject(body).opt("detail")
        } catch (e: Exception) {
            return null
        }
        val text = (detail as? String)?.trim() ?: return null
        if (text.isEmpty() || text.length > 300) return null
        // Server-side detail strings are lowercase fragments ("not found"); make them a sentence.
        val sentence = text.replaceFirstChar { it.uppercase() }
        return if (sentence.last() in ".!?" ) sentence else "$sentence."
    }

    /**
     * Sentence for an [ApiClient] result message, i.e. the `message` of the sealed Error results
     * ("HTTP 500", "rejected by the server (422)", "<what> response is not valid JSON", or the
     * exception text of a connection failure).
     */
    fun fromResultMessage(context: Context, message: String?): String {
        val m = message?.trim().orEmpty()
        return when {
            m.startsWith("HTTP ") -> context.getString(R.string.error_server_unexpected)
            m.contains("422") || m.contains("rejected", ignoreCase = true) ->
                context.getString(R.string.error_server_rejected)
            m.contains("not valid JSON", ignoreCase = true) || m.contains("response is not", ignoreCase = true) ->
                context.getString(R.string.error_server_unexpected)
            else -> context.getString(R.string.library_load_failed)
        }
    }

    /**
     * Sentence for an exception thrown while verifying a server (onboarding and the Settings
     * server dialog). Auth failures get their own sentence; any other server answer is "unexpected"
     * plus the server's detail when it gave one; everything else is a connectivity problem.
     */
    fun forServerSetup(context: Context, error: Throwable, detail: String? = null): String = when (error) {
        is ApiClient.ApiException -> when (error.code) {
            401, 403 -> context.getString(R.string.server_setup_auth_failed)
            else -> context.getString(
                R.string.server_setup_error_fmt,
                detail ?: context.getString(R.string.error_server_unexpected)
            )
        }
        is IOException -> context.getString(R.string.server_setup_health_failed)
        else -> context.getString(R.string.server_setup_health_failed)
    }
}
