package org.plaudbridge.app.ui.settings

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.plaudbridge.app.BuildConfig
import org.plaudbridge.app.R
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.common.ServerErrorText
import org.plaudbridge.app.databinding.ActivityQrLoginBinding
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore

/**
 * Settings > Sign in on a computer: scan the QR on the web dashboard's login screen and approve
 * it, so that browser gets its own session token from the server (the WhatsApp Web pattern).
 *
 * Opening the screen launches the zxing scanner straight away (the same CaptureActivity as
 * onboarding; it asks for the CAMERA permission itself). The scanned code goes through
 * [QrLoginApproval]: a code for any origin other than the configured server is refused with an
 * explanation and nothing is sent anywhere. A matching code still needs an explicit "Sign in"
 * in a dialog before ApiClient.approveLogin runs, because approving hands a computer access to
 * every recording on the server.
 *
 * The approve request never uses the URL from the QR; ApiClient builds it from the configured
 * server URL, and the payload only contributes the request id.
 */
class QrLoginActivity : AppCompatActivity() {

    /**
     * The one server call this screen makes, behind an interface (real ApiClient by default) so
     * a Robolectric test can drive the dialogs and the result handling without a network stack.
     */
    interface LoginApprover {
        suspend fun approve(requestId: String, label: String): ApiClient.ApproveLoginResult
    }

    private object ApiLoginApprover : LoginApprover {
        override suspend fun approve(requestId: String, label: String) =
            withContext(Dispatchers.IO) { ApiClient.approveLogin(requestId, label) }
    }

    companion object {
        private const val TAG = "QrLogin"

        /**
         * Debug builds only: treat this string as the scanned QR contents instead of opening the
         * camera. Lets a camera-less emulator exercise the whole flow, for example
         * `adb shell am start -n org.plaudbridge.app/.ui.settings.QrLoginActivity --es scanned '{...}'`.
         * Ignored in release builds so nothing can feed the flow from outside.
         */
        const val EXTRA_DEBUG_SCANNED = "scanned"

        /** Swapped by tests; production always uses the ApiClient-backed default. */
        @VisibleForTesting
        var approver: LoginApprover = ApiLoginApprover

        /** What the dashboard lists this session as, e.g. "Web · Pixel 10 Pro Fold". */
        fun sessionLabel(): String = "Web · ${Build.MODEL}".take(ApiClient.LOGIN_LABEL_MAX)
    }

    private lateinit var binding: ActivityQrLoginBinding

    /** True while the scanner opened by itself on entry; cancelling it then closes the screen. */
    private var scannerAutoLaunched = false

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val autoLaunched = scannerAutoLaunched
        scannerAutoLaunched = false
        val contents = result.contents
        if (contents == null) {
            // Cancelled. Coming straight from Settings, the user expects to land back there.
            if (autoLaunched) finish()
            return@registerForActivityResult
        }
        handleScannedCode(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.scanButton.setOnClickListener { launchScanner() }

        val serverHostPort = QrLoginApproval.hostPort(RecordingStore.serverBaseUrl)
        if (!RecordingStore.isServerConfigured || serverHostPort == null) {
            binding.serverLabel.text = getString(R.string.not_configured)
            showStatus(getString(R.string.qr_login_not_configured), isError = true)
            binding.scanButton.isEnabled = false
            return
        }
        binding.serverLabel.text = getString(R.string.qr_login_server_fmt, serverHostPort)

        if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_DEBUG_SCANNED)?.let {
                handleScannedCode(it)
                return
            }
        }
        // First open: go straight to the camera; a rotation must not reopen it over a dialog.
        if (savedInstanceState == null) {
            scannerAutoLaunched = true
            launchScanner()
        }
    }

    private fun launchScanner() {
        scanLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.qr_login_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(true)
        })
    }

    /** Entry point for a scanned (or, in tests and debug builds, injected) QR string. */
    @VisibleForTesting
    fun handleScannedCode(raw: String) {
        when (val decision = QrLoginApproval.decide(RecordingStore.serverBaseUrl, raw)) {
            is QrLoginApproval.Decision.NotConfigured ->
                showStatus(getString(R.string.qr_login_not_configured), isError = true)
            is QrLoginApproval.Decision.Invalid -> {
                AppLog.w(TAG, "sign-in QR rejected: ${decision.reason}")
                showStatus(
                    if (decision.isSetupCode) getString(R.string.qr_login_got_setup_code)
                    else getString(R.string.qr_login_invalid_fmt, decision.reason),
                    isError = true
                )
            }
            is QrLoginApproval.Decision.WrongServer -> {
                AppLog.w(TAG, "sign-in QR for another origin refused: ${decision.codeHostPort}")
                showStatus("", isError = false)
                AlertDialog.Builder(this)
                    .setTitle(R.string.sign_in_on_computer)
                    .setMessage(
                        getString(R.string.qr_login_wrong_server_fmt, decision.codeHostPort, decision.serverHostPort)
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            is QrLoginApproval.Decision.Approve -> confirmApproval(decision.payload)
        }
    }

    private fun confirmApproval(payload: org.plaudbridge.app.common.QrLoginPayload) {
        showStatus("", isError = false)
        AlertDialog.Builder(this)
            .setTitle(R.string.qr_login_confirm_title)
            .setMessage(getString(R.string.qr_login_confirm_fmt, payload.host))
            .setPositiveButton(R.string.sign_in) { _, _ -> approve(payload.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun approve(requestId: String) {
        binding.scanButton.isEnabled = false
        showStatus(getString(R.string.qr_login_approving), isError = false)
        lifecycleScope.launch {
            val result = approver.approve(requestId, sessionLabel())
            binding.scanButton.isEnabled = true
            when (result) {
                is ApiClient.ApproveLoginResult.Ok -> {
                    Toast.makeText(this@QrLoginActivity, R.string.qr_login_success, Toast.LENGTH_LONG).show()
                    finish()
                }
                is ApiClient.ApproveLoginResult.Expired ->
                    showStatus(getString(R.string.qr_login_expired), isError = true)
                is ApiClient.ApproveLoginResult.AlreadyUsed ->
                    showStatus(getString(R.string.qr_login_used), isError = true)
                is ApiClient.ApproveLoginResult.AuthError ->
                    showStatus(getString(R.string.library_auth_failed), isError = true)
                is ApiClient.ApproveLoginResult.Error -> {
                    AppLog.w(TAG, "approve failed: ${result.message}")
                    // One sentence: an unexpected server answer or a connection problem; the
                    // code itself stays in the log.
                    showStatus(ServerErrorText.fromResultMessage(this@QrLoginActivity, result.message), isError = true)
                }
            }
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        binding.statusLabel.text = message
        binding.statusLabel.setTextColor(
            ContextCompat.getColor(this, if (isError) R.color.red else R.color.text_secondary)
        )
    }
}
