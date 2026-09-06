package org.plaudbridge.app.ui.library

import java.net.URI

/**
 * Same-origin guard for the Library WebView.
 *
 * The WebView must only ever render the configured bridge server — everything else (external
 * links in the dashboard, redirects, phishing lookalikes) leaves the WebView. An origin is the
 * triple (scheme, host, effective port):
 *
 *  - the scheme must match the server's exactly (no https→http downgrade);
 *  - hosts compare case-insensitively; `URI.host` excludes userinfo, so a lookalike such as
 *    `https://bridge.example.com@evil.com/` compares as `evil.com` and is denied;
 *  - ports compare after default-port normalization (`https://h` == `https://h:443`), so an
 *    explicit non-default port only matches itself;
 *  - any path/query/fragment under the origin is allowed (subpaths are fine);
 *  - anything unparseable, relative, or without a host (about:blank, javascript:, data:) is denied.
 *
 * Pure JVM (java.net.URI, no android.*) so the rules are unit-testable.
 */
class WebViewOriginPolicy(serverBaseUrl: String?) {

    private val origin: Origin? = parseOrigin(serverBaseUrl)

    /** True if [url] belongs to the configured server's origin (and one is configured). */
    fun allows(url: String?): Boolean {
        val allowed = origin ?: return false
        return parseOrigin(url) == allowed
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    private fun parseOrigin(url: String?): Origin? {
        if (url.isNullOrBlank()) return null
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isBlank()) return null
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> return null // non-http(s) scheme without a known default port: deny
        }
        return Origin(scheme, host, port)
    }
}
