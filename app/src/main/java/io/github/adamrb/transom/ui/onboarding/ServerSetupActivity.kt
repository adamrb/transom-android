package io.github.adamrb.transom.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R as MaterialR
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.adamrb.transom.TransomApp
import io.github.adamrb.transom.R
import io.github.adamrb.transom.common.AppLog
import io.github.adamrb.transom.common.QrLoginPayload
import io.github.adamrb.transom.common.QrSetupPayload
import io.github.adamrb.transom.common.ServerErrorText
import io.github.adamrb.transom.databinding.ActivityServerSetupBinding
import io.github.adamrb.transom.net.ApiClient
import io.github.adamrb.transom.storage.RecordingStore
import io.github.adamrb.transom.ui.common.themeColor
import io.github.adamrb.transom.ui.main.MainActivity

/**
 * Onboarding: point the app at your self-hosted transom-server.
 *
 * "Test connection" hits GET /api/v1/health (no auth) and then verifies the auth token with an
 * authenticated POST /api/v1/plaud/user-token — which doubles as the first Plaud-token fetch,
 * so it is cached and the SDK can initialize right away. Only after a successful test can the
 * user continue to device scanning/pairing.
 */
class ServerSetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SKIP_DEVICE = "extra_skip_device"
        private const val TAG = "ServerSetup"
    }

    private lateinit var binding: ActivityServerSetupBinding

    /** Set true after a successful test of the CURRENT field values. */
    private var verified = false

    /**
     * QR onboarding: the server dashboard shows a QR encoding
     * {"v":1,"url":"https://...","token":"..."}. Scanning fills both fields and runs the same
     * "Test connection" verification as manual entry. The scanner (zxing-android-embedded's
     * CaptureActivity) requests the CAMERA permission itself — the app never asks for it.
     */
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult // cancelled
        when (val parsed = QrSetupPayload.parse(contents)) {
            is QrSetupPayload.Result.Success -> confirmScannedServer(parsed.payload)
            is QrSetupPayload.Result.Failure -> {
                AppLog.w(TAG, "QR parse failed: ${parsed.error} — ${parsed.reason}")
                // The dashboard's login screen shows a different QR (kind "login") that signs a
                // browser in; it carries no token, so it cannot set the server up. Say so instead
                // of the generic "missing auth token".
                val message = if (QrLoginPayload.isLoginKind(contents)) getString(R.string.qr_setup_got_login_code)
                else getString(R.string.qr_invalid_fmt, parsed.reason)
                showStatus(message, isError = true)
            }
        }
    }

    /**
     * A scanned server is never contacted or persisted silently: show the canonical ASCII
     * host:port (punycode for Unicode homographs, so lookalike domains are visible) and only
     * fill the fields + run the verification after explicit confirmation.
     */
    private fun confirmScannedServer(payload: QrSetupPayload) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.qr_confirm_title)
            .setMessage(getString(R.string.qr_confirm_fmt, "${payload.host}:${payload.port}"))
            .setPositiveButton(R.string.connect) { _, _ ->
                binding.serverUrlInput.setText(payload.url)
                binding.serverTokenInput.setText(payload.token)
                testConnection()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.backButton.setOnClickListener { finish() }

        // Pre-fill saved values (also used when editing from Settings-driven re-onboarding)
        RecordingStore.serverBaseUrl?.let { binding.serverUrlInput.setText(it) }
        RecordingStore.serverAuthToken?.let { binding.serverTokenInput.setText(it) }

        val invalidate = {
            verified = false
            binding.continueButton.isEnabled = false
            binding.statusLabel.text = ""
        }
        binding.serverUrlInput.addTextChangedListener(SimpleWatcher { invalidate() })
        binding.serverTokenInput.addTextChangedListener(SimpleWatcher { invalidate() })
        invalidate()

        binding.scanQrButton.setOnClickListener {
            qrScanLauncher.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.qr_scan_prompt))
                setBeepEnabled(false)
                setOrientationLocked(true)
            })
        }
        binding.testButton.setOnClickListener { testConnection() }
        binding.continueButton.setOnClickListener { onContinue() }
    }

    private fun normalizedUrl(): String? {
        var url = binding.serverUrlInput.text.toString().trim().trimEnd('/')
        if (url.isBlank()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return url
    }

    private fun testConnection() {
        val url = normalizedUrl()
        val token = binding.serverTokenInput.text.toString().trim()
        if (url == null || token.isBlank()) {
            showStatus(getString(R.string.server_setup_missing_fields), isError = true)
            return
        }
        // HTTPS only: Android's default network security config blocks cleartext HTTP, so an
        // http:// URL would only produce an opaque network error later (see README for LAN use).
        // The user just sees "Use an https:// address."; the why belongs in the README.
        if (!url.startsWith("https://")) {
            showStatus(getString(R.string.server_setup_https_required), isError = true)
            return
        }

        binding.testButton.isEnabled = false
        showStatus(getString(R.string.server_setup_testing), isError = false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (!ApiClient.checkHealth(url)) {
                        return@withContext getString(R.string.server_setup_health_failed) to null
                    }
                    // Authenticated call: fetch (and cache) the Plaud user token.
                    val userId = RecordingStore.getOrCreateUserId()
                    val plaudToken = ApiClient.fetchUserToken(url, token, userId)
                    null to plaudToken
                } catch (e: Exception) {
                    // One sentence for the user (wrong token, unreachable, unexpected answer);
                    // the code and exception text only go to the log.
                    AppLog.w(TAG, "connection test failed", e)
                    ServerErrorText.forServerSetup(this@ServerSetupActivity, e) to null
                }
            }

            binding.testButton.isEnabled = true
            val (error, plaudToken) = result
            if (error != null) {
                showStatus(error, isError = true)
                return@launch
            }
            // Persist settings + the freshly fetched Plaud token (with its expires_in).
            RecordingStore.serverBaseUrl = url
            RecordingStore.serverAuthToken = token
            plaudToken?.let { io.github.adamrb.transom.net.TokenManager.store(it) }
            verified = true
            binding.continueButton.isEnabled = true
            showStatus(getString(R.string.server_setup_success), isError = false)
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        binding.statusLabel.text = message
        binding.statusLabel.setTextColor(
            themeColor(if (isError) MaterialR.attr.colorError else R.attr.pbColorSuccess)
        )
        binding.statusLabel.visibility = View.VISIBLE
    }

    private fun onContinue() {
        if (!verified) return
        val userId = RecordingStore.getOrCreateUserId()
        (application as TransomApp).deviceManager.configure(userId)

        if (intent.getBooleanExtra(EXTRA_SKIP_DEVICE, false)) {
            RecordingStore.hasSkippedOnboarding = true
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        } else {
            startActivity(Intent(this, ScanningActivity::class.java))
        }
        finish()
    }
}

/** Tiny TextWatcher helper (only afterTextChanged is interesting here). */
private class SimpleWatcher(val onChange: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = onChange()
}
