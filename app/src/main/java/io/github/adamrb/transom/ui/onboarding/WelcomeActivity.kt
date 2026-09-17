package io.github.adamrb.transom.ui.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.github.adamrb.transom.TransomApp
import io.github.adamrb.transom.databinding.ActivityWelcomeBinding
import io.github.adamrb.transom.storage.RecordingStore
import io.github.adamrb.transom.ui.main.MainActivity

/**
 * Onboarding first page — Welcome.
 * Flow: Welcome → ServerSetup (self-hosted server URL + token) → Scanning → Success → Main.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Route BEFORE inflating so returning users never see a Welcome flash.
        // Requires: server configured AND (a paired device OR "connect later").
        val hasPairedDevice = RecordingStore.pairedDeviceSNs.isNotEmpty()
        if (RecordingStore.isServerConfigured &&
            (hasPairedDevice || RecordingStore.hasSkippedOnboarding)
        ) {
            val userId = RecordingStore.getOrCreateUserId()
            (application as TransomApp).deviceManager.configure(userId)
            navigateToMain()
            return
        }

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply system bar insets (status bar top + navigation bar bottom)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.getStartedButton.setOnClickListener {
            startActivity(Intent(this, ServerSetupActivity::class.java))
        }

        // Enter Home without pairing a device yet — the server still has to be configured first.
        binding.skipButton.setOnClickListener {
            if (RecordingStore.isServerConfigured) {
                RecordingStore.hasSkippedOnboarding = true
                val userId = RecordingStore.getOrCreateUserId()
                (application as TransomApp).deviceManager.configure(userId)
                navigateToMain()
            } else {
                startActivity(Intent(this, ServerSetupActivity::class.java).apply {
                    putExtra(ServerSetupActivity.EXTRA_SKIP_DEVICE, true)
                })
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
