package org.plaudbridge.app.ui.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.plaudbridge.app.common.QrLoginPayload
import org.plaudbridge.app.ui.library.WebViewOriginPolicy

/**
 * Decides what the phone does with a scanned sign-in code. Pure (no Android UI) so the rule
 * that matters for security is unit-tested on its own:
 *
 * The app approves a code ONLY when the code's canonical origin (scheme, host, effective port)
 * equals the configured server's origin. Otherwise a code shown by a stranger's server, or by a
 * lookalike domain, could be approved with the user's token and hand that server a hint that
 * the user exists there. The comparison reuses [WebViewOriginPolicy], which already normalizes
 * default ports and ignores userinfo, so both places agree on what "same server" means.
 *
 * The approve request itself is built from RecordingStore.serverBaseUrl (see
 * ApiClient.approveLogin); the URL inside the QR is never contacted, only compared.
 */
object QrLoginApproval {

    sealed class Decision {
        /** No server configured yet, nothing to approve against. */
        object NotConfigured : Decision()

        /** Not a sign-in code (or a malformed one). [isSetupCode] when it looks like an onboarding QR. */
        data class Invalid(val reason: String, val isSetupCode: Boolean) : Decision()

        /** The code belongs to another origin; both sides as "host:port" for the explanation. */
        data class WrongServer(val codeHostPort: String, val serverHostPort: String) : Decision()

        /** Same origin as the configured server: safe to ask the user and then approve. */
        data class Approve(val payload: QrLoginPayload) : Decision()
    }

    fun decide(serverBaseUrl: String?, raw: String?): Decision {
        val payload = when (val parsed = QrLoginPayload.parse(raw)) {
            is QrLoginPayload.Result.Success -> parsed.payload
            is QrLoginPayload.Result.Failure -> return Decision.Invalid(
                parsed.reason,
                isSetupCode = looksLikeSetupCode(raw)
            )
        }
        val serverHostPort = hostPort(serverBaseUrl) ?: return Decision.NotConfigured
        val codeHostPort = "${payload.host}:${payload.port}"
        if (!WebViewOriginPolicy(serverBaseUrl).allows(payload.url)) {
            return Decision.WrongServer(codeHostPort, serverHostPort)
        }
        return Decision.Approve(payload)
    }

    /** "host:port" of a base URL with the default port made explicit; null when unusable. */
    fun hostPort(baseUrl: String?): String? {
        if (baseUrl.isNullOrBlank()) return null
        val url = baseUrl.toHttpUrlOrNull() ?: return null
        return "${url.host}:${url.port}"
    }

    /** An onboarding QR has a token and no kind; only used to word the error message. */
    private fun looksLikeSetupCode(raw: String?): Boolean =
        org.plaudbridge.app.common.QrSetupPayload.parse(raw) is org.plaudbridge.app.common.QrSetupPayload.Result.Success
}
