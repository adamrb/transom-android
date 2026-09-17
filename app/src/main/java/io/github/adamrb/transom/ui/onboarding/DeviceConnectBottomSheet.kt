package io.github.adamrb.transom.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.adamrb.transom.TransomApp
import io.github.adamrb.transom.R
import io.github.adamrb.transom.databinding.FragmentDeviceConnectBinding
import io.github.adamrb.transom.models.DeviceConnectionState
import io.github.adamrb.transom.models.ScannedDevice
import io.github.adamrb.transom.storage.RecordingStore
import io.github.adamrb.transom.ui.common.themeColor
import kotlinx.coroutines.launch

/**
 * Device connection BottomSheet
 * Shown after a device is found; supports swiping left/right to switch between multiple devices
 */
class DeviceConnectBottomSheet : BottomSheetDialogFragment() {

    companion object {
        /** Fragment-result key fired when the sheet is dismissed (lets the host re-arm its guard). */
        const val RESULT_DISMISSED = "device_connect_sheet_dismissed"
    }

    private var _binding: FragmentDeviceConnectBinding? = null
    private val binding get() = _binding!!

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        parentFragmentManager.setFragmentResult(RESULT_DISMISSED, Bundle())
    }

    private val app get() = requireActivity().application as TransomApp
    private val deviceManager get() = app.deviceManager

    private var currentDevices: List<ScannedDevice> = emptyList()
    private var currentIndex = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeviceConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Fixed height.
        // Use the dialog's own context for density rather than Fragment.resources/requireContext():
        // onShow can fire after the fragment is detached (e.g. fast disconnect/scan transitions),
        // and requireContext() would then throw "not attached to a context".
        (dialog as? BottomSheetDialog)?.let { bsd ->
            bsd.setOnShowListener {
                val bottomSheet = bsd.findViewById<FrameLayout>(
                    com.google.android.material.R.id.design_bottom_sheet
                )
                bottomSheet?.let {
                    val behavior = BottomSheetBehavior.from(it)
                    val height = (378 * it.resources.displayMetrics.density).toInt()
                    it.layoutParams.height = height
                    behavior.peekHeight = height
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }

        binding.closeButton.setOnClickListener { dismiss() }
        binding.connectButton.setOnClickListener { onConnectTapped() }

        // Swipe left/right to switch devices
        view.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var startX = 0f
            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> startX = event.x
                    android.view.MotionEvent.ACTION_UP -> {
                        val dx = event.x - startX
                        if (dx > 100 && currentIndex > 0) {
                            currentIndex--
                            updateDeviceDisplay()
                        } else if (dx < -100 && currentIndex < currentDevices.size - 1) {
                            currentIndex++
                            updateDeviceDisplay()
                        }
                    }
                }
                return true
            }
        })

        observeDevices()
    }

    private fun observeDevices() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    deviceManager.scannedDevices.collect { devices ->
                        // Preserve the current selection across list refreshes (mirrors iOS merge)
                        val prevSN = currentDevices.getOrNull(currentIndex)?.serialNumber
                        currentDevices = devices
                        currentIndex = devices.indexOfFirst { it.serialNumber == prevSN }
                            .takeIf { it >= 0 } ?: 0
                        updateDeviceDisplay()
                        updateDots()
                    }
                }
                launch {
                    deviceManager.connectionState.collect { state ->
                        when (state) {
                            is DeviceConnectionState.Connecting -> {
                                binding.connectingRow.visibility = View.VISIBLE
                                binding.connectButton.visibility = View.GONE
                                binding.deviceNameLabel.visibility = View.INVISIBLE
                                binding.dotsContainer.visibility = View.INVISIBLE
                            }
                            is DeviceConnectionState.Connected -> {
                                dismiss()
                            }
                            is DeviceConnectionState.Failed -> {
                                // Match iOS: no toast; just restore the connect button
                                binding.connectingRow.visibility = View.GONE
                                binding.connectButton.visibility = View.VISIBLE
                                binding.deviceNameLabel.visibility = View.VISIBLE
                                binding.dotsContainer.visibility = View.VISIBLE
                                when {
                                    // Recovery itself failed: show its reason once.
                                    recoveryRunning -> {
                                        recoveryRunning = false
                                        showRecoveryResult(state.message)
                                    }
                                    // A user-initiated connect failed: the device may still be
                                    // locked by a previous account (lifecycle guide §3.3) —
                                    // offer the brick-recovery flow.
                                    awaitingConnectResult -> {
                                        awaitingConnectResult = false
                                        offerRecovery()
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    private fun updateDeviceDisplay() {
        if (currentDevices.isEmpty()) return
        val device = currentDevices[currentIndex]
        binding.deviceNameLabel.text = device.name
        binding.deviceSnLabel.text = getString(R.string.serial_fmt, device.serialNumber)
    }

    private fun updateDots() {
        binding.dotsContainer.removeAllViews()
        if (currentDevices.size <= 1) {
            binding.dotsContainer.visibility = View.GONE
            return
        }
        binding.dotsContainer.visibility = View.VISIBLE
        val dp = resources.displayMetrics.density
        for (i in currentDevices.indices) {
            val dot = View(requireContext())
            val isActive = i == currentIndex
            val width = if (isActive) (24 * dp).toInt() else (12 * dp).toInt()
            val height = (4 * dp).toInt()
            val params = android.widget.LinearLayout.LayoutParams(width, height)
            params.marginEnd = (8 * dp).toInt()
            dot.layoutParams = params
            dot.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 2 * dp
                setColor(requireContext().themeColor(if (isActive) MaterialR.attr.colorPrimary else MaterialR.attr.colorOutline))
            }
            binding.dotsContainer.addView(dot)
        }
    }

    /** Set on a user-initiated connect; a Failed while set offers the recovery flow once. */
    private var awaitingConnectResult = false

    /** Recovery flow in flight; a Failed while set is the recovery outcome, shown as an alert. */
    private var recoveryRunning = false

    /**
     * The failed handshake may mean the firmware is still locked by a previous account
     * (lifecycle guide §3.3). Offer the free recovery path: query bind history → wipe the
     * stale bond with the matching historical id → reconnect as the current user.
     */
    private fun offerRecovery() {
        val device = currentDevices.getOrNull(currentIndex) ?: return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.recorder_locked_title)
            .setMessage(getString(R.string.recorder_locked_message_fmt, device.name.ifBlank { getString(R.string.default_recorder_name) }))
            .setPositiveButton(R.string.recover) { _, _ ->
                recoveryRunning = true
                deviceManager.startDeviceRecovery(device)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRecoveryResult(message: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.recorder_recovery_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun onConnectTapped() {
        if (currentDevices.isEmpty()) return
        val device = currentDevices[currentIndex]
        val userId = RecordingStore.userId ?: return
        awaitingConnectResult = true
        // Keep scanning while connecting (mirrors iOS): new devices still merge into the pager
        deviceManager.connect(device, userId)
        // Note: do not persist lastConnectedDeviceSN on tap, otherwise a failed connection would also be marked as
        // "previously connected", causing the next launch to wrongly land on Home. The SN is written by DeviceManager.onDeviceConnected
        // only after the handshake succeeds.
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
