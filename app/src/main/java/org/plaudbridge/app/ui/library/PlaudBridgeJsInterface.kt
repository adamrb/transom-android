package org.plaudbridge.app.ui.library

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.export.ExportFileName
import org.plaudbridge.app.export.TranscriptShare

/**
 * `window.PlaudBridgeApp`: the two native hooks the embedded dashboard may call.
 *
 *  - `copyText(text)`: clipboard + "Transcript copied" toast. The dashboard uses it when
 *    `navigator.clipboard` fails (WebView often denies it without a user-gesture chain).
 *  - `shareMarkdown(filename, markdown)`: writes `cacheDir/exports/<sanitized filename>` and
 *    opens the system share sheet. The dashboard prefers this over a browser download, which a
 *    WebView cannot perform on its own.
 *
 * Why exposing a JS interface is acceptable here:
 *  - The WebView renders only the configured bridge server. [WebViewOriginPolicy] is enforced in
 *    shouldOverrideUrlLoading, shouldInterceptRequest (main frame) and onPageStarted, and both
 *    methods re-check the current main-frame URL on the main thread before acting, so a foreign
 *    page that somehow committed still gets nothing.
 *  - The interface has no read access to anything: no getters, no return values, no file, token
 *    or store access. It cannot leak data back into the page.
 *  - Both methods act solely on data the page supplies about itself (its own transcript text).
 *    The worst a hostile page could do is fill the clipboard or pop a share sheet with its own
 *    content, which any web page can already do via download/share APIs.
 *  - Payloads are capped at [MAX_PAYLOAD_BYTES] and filenames pass through [ExportFileName], so
 *    the page cannot exhaust the cache or write outside `exports/`.
 *  - Only `@JavascriptInterface` methods are reachable (API 17+; minSdk is 21).
 *
 * WebView invokes these on a background thread; all UI work is posted to the main looper.
 */
class PlaudBridgeJsInterface(
    private val appContext: Context,
    /** Main-thread check: is the WebView currently showing the configured server? */
    private val pageIsTrusted: () -> Boolean,
    /** Main-thread launcher for the chooser (the hosting Fragment's startActivity). */
    private val startActivity: (Intent) -> Unit,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {

    @JavascriptInterface
    fun copyText(text: String) {
        if (!withinLimit(text, "copyText")) return
        mainHandler.post {
            if (!pageIsTrusted()) return@post AppLog.w(TAG, "copyText from untrusted page ignored")
            TranscriptShare.copyToClipboard(appContext, text)
        }
    }

    @JavascriptInterface
    fun shareMarkdown(filename: String, markdown: String) {
        if (!withinLimit(markdown, "shareMarkdown")) return
        val safeName = ExportFileName.sanitize(filename)
        mainHandler.post {
            if (!pageIsTrusted()) return@post AppLog.w(TAG, "shareMarkdown from untrusted page ignored")
            try {
                val file = TranscriptShare.writeExport(appContext, safeName, markdown)
                val title = safeName.removeSuffix(ExportFileName.EXTENSION)
                startActivity(TranscriptShare.shareIntent(appContext, file, title, markdown))
            } catch (e: Exception) {
                AppLog.w(TAG, "shareMarkdown failed", e)
            }
        }
    }

    private fun withinLimit(payload: String, method: String): Boolean {
        // A String of more than MAX_PAYLOAD_BYTES chars is over the limit in any encoding, so
        // skip the UTF-8 encode for those; otherwise measure the real byte length.
        val over = payload.length > MAX_PAYLOAD_BYTES ||
            payload.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES
        if (over) AppLog.w(TAG, "$method rejected: payload over ${MAX_PAYLOAD_BYTES} bytes")
        return !over
    }

    companion object {
        private const val TAG = "PlaudBridgeJs"

        /** Name the dashboard looks for: `window.PlaudBridgeApp`. */
        const val JS_NAME = "PlaudBridgeApp"

        /** 2 MB: far above any real transcript, low enough to keep the cache and clipboard sane. */
        const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
    }
}
