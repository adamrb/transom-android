package org.plaudbridge.app.ui.library

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import org.plaudbridge.app.R
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.databinding.FragmentLibraryBinding
import org.plaudbridge.app.storage.RecordingStore
import java.io.ByteArrayInputStream

/**
 * Library Tab — the self-hosted server's web dashboard, embedded.
 *
 * The server serves a mobile-friendly SPA at its root; it reads the bearer token from
 * localStorage key "pb_token", so the user never types a login here.
 *
 * Token injection approach (KISS, two layers):
 *  1. onPageStarted: best-effort `localStorage.setItem('pb_token', …)` so the token is usually
 *     in place before the SPA boots.
 *  2. onPageFinished: verify the stored value matches the configured token; if not (the SPA read
 *     localStorage before step 1 landed, or the token changed), set it and reload ONCE per
 *     navigation — a guard flag prevents reload loops when the page itself misbehaves.
 * Both steps run ONLY when the page's URL passes [WebViewOriginPolicy]: the token must never be
 * written into a foreign origin's localStorage.
 *
 * Origin guard: shouldOverrideUrlLoading keeps same-origin navigation inside the WebView, sends
 * external http(s) links to the system browser, and drops everything else. Server changes are
 * handled by re-checking the stored config on every (re)entry to the tab; when the HOST changes
 * (or after unpair/clearAll, via the persisted [RecordingStore.libraryWebViewHost]), all WebView
 * storage is wiped so the old server's token/cookies never leak to the new one.
 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private var originPolicy = WebViewOriginPolicy(null)
    private var configuredUrl: String? = null
    private var configuredToken: String? = null

    /**
     * One token-triggered reload per requested load (reset in [loadDashboard] and on
     * pull-to-refresh, NOT in onPageStarted — the token reload itself restarts the page, and
     * resetting there would defeat the loop guard).
     */
    private var reloadedForToken = false

    /** Set by onReceivedError for the main frame; checked in onPageFinished. */
    private var mainFrameError = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (_binding != null && binding.webView.canGoBack()) binding.webView.goBack()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.webView.settings.apply {
            javaScriptEnabled = true          // the dashboard is an SPA
            domStorageEnabled = true          // it keeps the token in localStorage
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        binding.webView.webViewClient = LibraryWebViewClient()

        binding.swipeRefresh.setOnRefreshListener {
            if (binding.errorView.visibility == View.VISIBLE) {
                loadDashboard(force = true)
            } else {
                reloadedForToken = false
                binding.webView.reload()
            }
        }
        // Only intercept the pull gesture at the very top of the page
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            _binding != null && binding.webView.scrollY > 0
        }

        binding.retryButton.setOnClickListener { loadDashboard(force = true) }

        // Back navigates WebView history before leaving the tab (enabled only while this
        // fragment is visible AND there is history to go back to).
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        loadDashboard()
    }

    override fun onResume() {
        super.onResume()
        loadDashboard() // no-op unless the server config changed
        updateBackCallback()
    }

    /** Tabs are switched with show/hide, which does not touch the lifecycle. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding == null) return
        if (!hidden) loadDashboard()
        updateBackCallback()
    }

    /**
     * (Re)load the dashboard if this is the first load or the server config changed.
     * Wipes all WebView storage when the server HOST differs from the one whose data the
     * WebView last held (tracked persistently — covers restarts and unpair/clearAll).
     */
    private fun loadDashboard(force: Boolean = false) {
        if (_binding == null) return
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
            AppLog.i(TAG, "server host changed — wiping WebView storage")
            binding.webView.clearHistory()
            binding.webView.clearCache(true)
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            RecordingStore.libraryWebViewHost = host
        }

        showWeb()
        binding.webView.loadUrl(url)
    }

    private fun showError(message: String) {
        if (_binding == null) return
        binding.errorLabel.text = message
        binding.errorView.visibility = View.VISIBLE
        binding.webView.visibility = View.INVISIBLE
        binding.swipeRefresh.isRefreshing = false
    }

    private fun showWeb() {
        binding.errorView.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
    }

    private fun updateBackCallback() {
        backCallback.isEnabled =
            _binding != null && !isHidden && binding.webView.canGoBack()
    }

    private fun openExternally(uri: Uri) {
        // External links (and anything non-same-origin) go to the system browser; only ever
        // http(s) — javascript:, intent:, file: etc. are dropped outright.
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            AppLog.w(TAG, "no browser for $uri", e)
        }
    }

    private inner class LibraryWebViewClient : WebViewClient() {

        // API 24+ path
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (originPolicy.allows(request.url.toString())) return false
            openExternally(request.url)
            return true
        }

        // API 21–23 fall back to the deprecated String overload
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (originPolicy.allows(url)) return false
            openExternally(Uri.parse(url))
            return true
        }

        /**
         * POST navigations (form submissions) bypass shouldOverrideUrlLoading entirely, so the
         * origin policy is ALSO enforced here for main-frame loads: a disallowed main-frame
         * request gets an empty response instead of rendering a foreign origin in the tab.
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
            // Early best-effort injection — only ever into our own origin.
            val token = configuredToken
            if (token != null) {
                view.evaluateJavascript(TokenInjection.setTokenScript(token), null)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (_binding == null) return
            binding.swipeRefresh.isRefreshing = false
            updateBackCallback()
            if (mainFrameError) return
            showWeb()
            val token = configuredToken
            if (token == null || !originPolicy.allows(url)) return
            // Verify + set + reload once if the SPA booted before the token landed.
            view.evaluateJavascript(TokenInjection.ensureTokenScript(token)) { result ->
                if (result == "\"reload\"" && !reloadedForToken && _binding != null) {
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

        // API 21–22
        @Deprecated("Deprecated in Java")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
            if (Build.VERSION.SDK_INT < 23) {
                mainFrameError = true
                showError(getString(R.string.library_load_failed))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.webView.apply {
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        _binding = null
    }

    companion object {
        private const val TAG = "LibraryFragment"
    }
}
