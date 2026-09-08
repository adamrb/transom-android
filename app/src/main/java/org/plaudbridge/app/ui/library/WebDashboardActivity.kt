package org.plaudbridge.app.ui.library

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.plaudbridge.app.R
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.databinding.ActivityWebDashboardBinding
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.common.themeColor
import java.io.ByteArrayInputStream

/**
 * The self-hosted server's web dashboard, embedded. Reached from Settings > Web dashboard.
 *
 * This used to be the Library tab. The tab is native now (see [LibraryFragment]); the WebView
 * stays for what the app has no screens for, above all the Automations editor.
 *
 * The server serves a mobile-friendly SPA at its root; it reads the bearer token from
 * localStorage key "pb_token", so the user never types a login here.
 *
 * Token injection approach (KISS, two layers):
 *  1. onPageStarted: best-effort `localStorage.setItem('pb_token', …)` so the token is usually
 *     in place before the SPA boots.
 *  2. onPageFinished: verify the stored value matches the configured token; if not (the SPA read
 *     localStorage before step 1 landed, or the token changed), set it and reload ONCE per
 *     navigation. A guard flag prevents reload loops when the page itself misbehaves.
 * Both steps run ONLY when the page's URL passes [WebViewOriginPolicy]: the token must never be
 * written into a foreign origin's localStorage.
 *
 * Origin guard: shouldOverrideUrlLoading keeps same-origin navigation inside the WebView, sends
 * external http(s) links to the system browser, and drops everything else. When the server HOST
 * differs from the one whose data the WebView last held (persisted as
 * [RecordingStore.libraryWebViewHost], so it covers restarts and unpair/clearAll), all WebView
 * storage is wiped so the old server's token/cookies never leak to the new one.
 *
 * Two fixes over the old tab:
 *  - A [WebChromeClient] is installed. Without one, WebView answers every JavaScript alert(),
 *    confirm() and prompt() as if the user cancelled, which is why the dashboard's Delete
 *    button (a confirm()) never did anything. The default implementation shows them as dialogs.
 *  - A DownloadListener handles the dashboard's download links. A WebView cannot download on
 *    its own; http(s) URLs go to the system browser, and blob: URLs (the export button's
 *    fallback when the JS bridge is absent) get a hint to use the app's own Export.
 *
 * Native hooks: `window.PlaudBridgeApp` ([PlaudBridgeJsInterface]) lets the dashboard copy text
 * to the clipboard and hand a markdown export to the share sheet, two things a WebView cannot do
 * on its own. The dashboard feature-detects it and falls back to browser behavior when absent.
 */
class WebDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebDashboardBinding

    private var originPolicy = WebViewOriginPolicy(null)
    private var configuredUrl: String? = null
    private var configuredToken: String? = null

    /**
     * One token-triggered reload per requested load (reset in [loadDashboard] and on
     * pull-to-refresh, NOT in onPageStarted: the token reload itself restarts the page, and
     * resetting there would defeat the loop guard).
     */
    private var reloadedForToken = false

    /** Set by onReceivedError for the main frame; checked in onPageFinished. */
    private var mainFrameError = false

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { backCallback.handleOnBackPressed() }
        // Opened for one tab (Settings > Automations) the screen is titled after it; the full
        // dashboard (Settings > Advanced) keeps the generic title.
        binding.titleLabel.text = getString(
            if (requestedTab() == TAB_AUTOMATIONS) R.string.automations else R.string.web_dashboard
        )

        // The page paints the app's palette itself (?theme=); until it does, the WebView shows the
        // window colour rather than a white flash on the dark palette.
        binding.webView.setBackgroundColor(themeColor(android.R.attr.colorBackground))
        binding.webView.settings.apply {
            javaScriptEnabled = true          // the dashboard is an SPA
            domStorageEnabled = true          // it keeps the token in localStorage
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        binding.webView.webViewClient = DashboardWebViewClient()
        // The stock WebChromeClient renders JS alert/confirm/prompt as dialogs. Without it the
        // dashboard's confirm()-guarded actions (Delete) are answered "cancel" every time.
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.setDownloadListener { url, _, _, _, _ ->
            val uri = Uri.parse(url)
            if (uri.scheme.equals("blob", ignoreCase = true)) {
                Toast.makeText(this, R.string.download_use_export, Toast.LENGTH_SHORT).show()
            } else {
                openExternally(uri)
            }
        }
        // window.PlaudBridgeApp: copyText / shareMarkdown for the dashboard's export buttons.
        // Safe to expose because the page is origin-locked and the interface only ever acts on
        // data the page hands it (see PlaudBridgeJsInterface for the full reasoning).
        binding.webView.addJavascriptInterface(
            PlaudBridgeJsInterface(
                appContext = applicationContext,
                pageIsTrusted = { !isDestroyed && originPolicy.allows(binding.webView.url) },
                startActivity = { intent -> if (!isFinishing) startActivity(intent) }
            ),
            PlaudBridgeJsInterface.JS_NAME
        )

        binding.swipeRefresh.setOnRefreshListener {
            if (binding.errorView.visibility == View.VISIBLE) {
                loadDashboard(force = true)
            } else {
                reloadedForToken = false
                binding.webView.reload()
            }
        }
        // Only intercept the pull gesture at the very top of the page
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ -> binding.webView.scrollY > 0 }

        binding.retryButton.setOnClickListener { loadDashboard(force = true) }

        // Back navigates WebView history before leaving the screen.
        onBackPressedDispatcher.addCallback(this, backCallback)

        loadDashboard()
    }

    override fun onResume() {
        super.onResume()
        loadDashboard() // no-op unless the server config changed
    }

    /**
     * (Re)load the dashboard if this is the first load or the server config changed.
     * Wipes all WebView storage when the server HOST differs from the one whose data the
     * WebView last held (tracked persistently, so it covers restarts and unpair/clearAll).
     */
    private fun loadDashboard(force: Boolean = false) {
        val url = RecordingStore.serverBaseUrl
        val token = RecordingStore.serverAuthToken
        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            showError(getString(R.string.library_not_configured))
            return
        }
        val changed = url != configuredUrl || token != configuredToken
        if (!force && !changed && binding.webView.url != null) return

        originPolicy = WebViewOriginPolicy(url)
        configuredUrl = url
        configuredToken = token
        reloadedForToken = false

        val host = Uri.parse(url).host
        if (host != null && RecordingStore.libraryWebViewHost != host) {
            AppLog.i(TAG, "server host changed, wiping WebView storage")
            binding.webView.clearHistory()
            binding.webView.clearCache(true)
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            RecordingStore.libraryWebViewHost = host
        }

        showWeb()
        // ?embedded=1 tells the dashboard it is inside the app: it hides the brand,
        // "Connect a phone" and "Sign out" (which would log this WebView out). ?tab opens that
        // tab and hides the tab switcher; ?theme keeps the page in step with the app's palette
        // (light, dark, or system when the app itself follows the system).
        binding.webView.loadUrl(embeddedUrl(url, requestedTab(), DashboardTheme.current()))
    }

    private fun requestedTab(): String? = intent.getStringExtra(EXTRA_TAB)?.takeIf { it in KNOWN_TABS }

    private fun showError(message: String) {
        binding.errorLabel.text = message
        binding.errorView.visibility = View.VISIBLE
        binding.webView.visibility = View.INVISIBLE
        binding.swipeRefresh.isRefreshing = false
    }

    private fun showWeb() {
        binding.errorView.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
    }

    private fun openExternally(uri: Uri) {
        // External links (and anything non-same-origin) go to the system browser; only ever
        // http(s), so javascript:, intent:, file: etc. are dropped outright.
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            AppLog.w(TAG, "no browser for $uri", e)
        }
    }

    private inner class DashboardWebViewClient : WebViewClient() {

        // API 24+ path
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (originPolicy.allows(request.url.toString())) return false
            openExternally(request.url)
            return true
        }

        // API 21-23 fall back to the deprecated String overload
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (originPolicy.allows(url)) return false
            openExternally(Uri.parse(url))
            return true
        }

        /**
         * POST navigations (form submissions) bypass shouldOverrideUrlLoading entirely, so the
         * origin policy is ALSO enforced here for main-frame loads: a disallowed main-frame
         * request gets an empty response instead of rendering a foreign origin.
         * (Runs on a background thread; the policy is pure.)
         */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame && !originPolicy.allows(request.url.toString())) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // Defense in depth: if a disallowed URL somehow committed anyway, stop it and never
            // run the token injection against it.
            if (!originPolicy.allows(url)) {
                view.stopLoading()
                return
            }
            mainFrameError = false
            // Early best-effort injection, only ever into our own origin.
            val token = configuredToken
            if (token != null) {
                view.evaluateJavascript(TokenInjection.setTokenScript(token), null)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (isDestroyed) return
            binding.swipeRefresh.isRefreshing = false
            if (mainFrameError) return
            showWeb()
            val token = configuredToken
            if (token == null || !originPolicy.allows(url)) return
            // Verify + set + reload once if the SPA booted before the token landed.
            view.evaluateJavascript(TokenInjection.ensureTokenScript(token)) { result ->
                if (result == "\"reload\"" && !reloadedForToken && !isDestroyed) {
                    reloadedForToken = true
                    binding.webView.reload()
                }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (Build.VERSION.SDK_INT >= 23 && request.isForMainFrame) {
                mainFrameError = true
                showError(getString(R.string.library_load_failed))
            }
        }

        // API 21-22
        @Deprecated("Deprecated in Java")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
            if (Build.VERSION.SDK_INT < 23) {
                mainFrameError = true
                showError(getString(R.string.library_load_failed))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.webView.destroy()
    }

    companion object {
        private const val TAG = "WebDashboard"

        /** Intent extra: which dashboard tab to open ([TAB_AUTOMATIONS] or [TAB_RECORDINGS]). */
        const val EXTRA_TAB = "tab"
        const val TAB_AUTOMATIONS = "automations"
        const val TAB_RECORDINGS = "recordings"
        private val KNOWN_TABS = setOf(TAB_AUTOMATIONS, TAB_RECORDINGS)

        /**
         * Dashboard URL for in-app display: same origin, plus the embedded flag, plus the
         * optional tab and theme the dashboard understands (`?tab=automations&theme=light`).
         */
        fun embeddedUrl(base: String, tab: String? = null, theme: String? = null): String =
            Uri.parse(base).buildUpon()
                .appendQueryParameter("embedded", "1")
                .apply {
                    if (tab != null) appendQueryParameter("tab", tab)
                    if (theme != null) appendQueryParameter("theme", theme)
                }
                .build().toString()
    }
}
