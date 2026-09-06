package org.plaudbridge.app.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.R
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.common.QrSetupPayload
import org.plaudbridge.app.databinding.ActivityServerSetupBinding
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.main.MainActivity

/**
 * Onboarding: point the app at your self-hosted plaud-bridge-server.
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
            is QrSetupPayload.Result.Success -> {
                binding.serverUrlInput.setText(parsed.payload.url)
                binding.serverTokenInput.setText(parsed.payload.token)
                testConnection()
            }
            is QrSetupPayload.Result.Failure -> {
                AppLog.w(TAG, "QR parse failed: ${parsed.error} — ${parsed.reason}")
                showStatus(getString(R.string.qr_invalid_fmt, parsed.reason), isError = true)
            }
        }
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
            binding.continueButton.alpha = 0.5f
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
                } catch (e: ApiClient.ApiException) {
                    AppLog.w(TAG, "auth check failed", e)
                    val msg = if (e.code == 401 || e.code == 403) {
                        getString(R.string.server_setup_auth_failed)
                    } else {
                        getString(R.string.server_setup_error_fmt, e.message ?: "")
                    }
                    msg to null
                } catch (e: Exception) {
                    AppLog.w(TAG, "connection test failed", e)
                    getString(R.string.server_setup_error_fmt, e.message ?: "network error") to null
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
            plaudToken?.let { org.plaudbridge.app.net.TokenManager.store(it) }
            verified = true
            binding.continueButton.isEnabled = true
            binding.continueButton.alpha = 1f
            showStatus(getString(R.string.server_setup_success), isError = false)
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        binding.statusLabel.text = message
        binding.statusLabel.setTextColor(
            ContextCompat.getColor(this, if (isError) R.color.red else R.color.green)
        )
        binding.statusLabel.visibility = View.VISIBLE
    }

    private fun onContinue() {
        if (!verified) return
        val userId = RecordingStore.getOrCreateUserId()
        (application as PlaudBridgeApp).deviceManager.configure(userId)

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
