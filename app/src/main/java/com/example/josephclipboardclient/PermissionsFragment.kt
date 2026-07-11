package com.EntropicSoftwareLab.ClipboardShare

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.EntropicSoftwareLab.ClipboardShare.R

class PermissionsFragment : Fragment() {

    interface OnPermissionsListener {
        fun onOnboardingFinished()
    }

    private var listener: OnPermissionsListener? = null

    private val requestNotificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Toast.makeText(requireContext(), getString(R.string.perm_notification_granted_toast), Toast.LENGTH_SHORT).show()
        } else if (isNotificationPermanentlyDenied()) {
            // The system dialog can no longer appear — same dead-end the Nearby flow already
            // handles (AND-37): without a Settings route the Finish button stays disabled forever.
            Toast.makeText(requireContext(), getString(R.string.perm_notification_settings_toast), Toast.LENGTH_LONG).show()
            openAppSettings()
        } else {
            Toast.makeText(requireContext(), getString(R.string.perm_notification_recommended_toast), Toast.LENGTH_LONG).show()
        }
        updateNotificationButtonState()
        checkAllPermissions()
    }

    private val requestBatteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshPermissionStates()
    }

    private val requestNearbyLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Toast.makeText(requireContext(), getString(R.string.perm_nearby_granted_toast), Toast.LENGTH_SHORT).show()
        } else if (isNearbyPermanentlyDenied()) {
            // The system dialog can no longer appear (denied permanently / OEM-suppressed) — send the
            // user to the app's permission settings, where the Nearby devices toggle lives. Without
            // this, pressing the button is a silent no-op and the permission never changes.
            Toast.makeText(requireContext(), getString(R.string.perm_nearby_settings_toast), Toast.LENGTH_LONG).show()
            openAppSettings()
        }
        updateNearbyButtonState()
        checkAllPermissions()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnPermissionsListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement OnPermissionsListener")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_permissions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val batteryButton = view.findViewById<Button>(R.id.btn_go_to_battery_settings)
        val notificationButton = view.findViewById<Button>(R.id.btn_request_notification_permission)
        val finishButton = view.findViewById<Button>(R.id.btn_finish)

        finishButton.isEnabled = false

        batteryButton.setOnClickListener {
            requestBatteryOptimization()
        }

        notificationButton.setOnClickListener {
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
                    Toast.makeText(requireContext(), getString(R.string.perm_notification_already_granted_toast), Toast.LENGTH_SHORT).show()
                hasNotificationPermission() -> { /* already granted */ }
                isNotificationPermanentlyDenied() -> {
                    Toast.makeText(requireContext(), getString(R.string.perm_notification_settings_toast), Toast.LENGTH_LONG).show()
                    openAppSettings()
                }
                else -> {
                    markNotificationRequested()
                    requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        finishButton.setOnClickListener {
            listener?.onOnboardingFinished()
        }

        val nearbyButton = view.findViewById<Button>(R.id.btn_request_nearby_permission)
        nearbyButton.setOnClickListener {
            when {
                hasNearbyPermission() -> { /* already granted */ }
                isNearbyPermanentlyDenied() -> {
                    Toast.makeText(requireContext(), getString(R.string.perm_nearby_settings_toast), Toast.LENGTH_LONG).show()
                    openAppSettings()
                }
                else -> {
                    markNearbyRequested()
                    requestNearbyLauncher.launch(nearbyPermission())
                }
            }
        }

        updateBatteryButtonState()
        updateNotificationButtonState()
        updateNearbyButtonState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    /**
     * Re-detect every permission/exemption state and repaint the buttons. Also called by
     * [OnboardingActivity.onResume] because a ViewPager2 page fragment's onResume isn't reliably fired
     * when returning from system settings — so setting Battery to "No restrictions" (or toggling NFC)
     * externally is now picked up automatically, no button press needed.
     */
    fun refreshPermissionStates() {
        updateBatteryButtonState()
        updateNotificationButtonState()
        updateNearbyButtonState()
        checkAllPermissions()
        if (!isBatteryOptimizationDisabled()) scheduleBatteryRechecks()
    }

    /**
     * [PowerManager.isIgnoringBatteryOptimizations] can still return the old value for a short
     * window after the user flips the setting and comes back — the instant refresh on resume then
     * paints a stale state that only corrects on the next unrelated UI event. Re-poll a few times
     * so the exemption is picked up as soon as the system commits it.
     */
    private val batteryRecheckDelaysMs = longArrayOf(500L, 1500L, 3000L)
    private val batteryRecheckRunnables = mutableListOf<Runnable>()

    private fun scheduleBatteryRechecks() {
        val v = view ?: return
        cancelBatteryRechecks()
        for (delay in batteryRecheckDelaysMs) {
            val recheck = Runnable {
                if (view == null || context == null) return@Runnable
                if (isBatteryOptimizationDisabled()) {
                    cancelBatteryRechecks()
                    updateBatteryButtonState()
                    checkAllPermissions()
                }
            }
            batteryRecheckRunnables.add(recheck)
            v.postDelayed(recheck, delay)
        }
    }

    private fun cancelBatteryRechecks() {
        val v = view
        batteryRecheckRunnables.forEach { v?.removeCallbacks(it) }
        batteryRecheckRunnables.clear()
    }

    override fun onDestroyView() {
        cancelBatteryRechecks()
        super.onDestroyView()
    }

    private fun nearbyPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun hasNearbyPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), nearbyPermission()) == PackageManager.PERMISSION_GRANTED

    // ---- Shared permanently-denied machinery (one copy for every gated permission) ----

    private fun permRequested(flagKey: String): Boolean =
        requireContext().getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(flagKey, false)

    private fun markPermRequested(flagKey: String) {
        requireContext().getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(flagKey, true).apply()
    }

    /** True only once we've actually asked AND the system won't show the dialog again — i.e. the user
     *  must toggle it in Settings. (Before the first ask, rationale is also false, hence the flag.) */
    private fun isPermanentlyDenied(permission: String, flagKey: String): Boolean {
        val granted = ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
        if (granted || !permRequested(flagKey)) return false
        return !shouldShowRequestPermissionRationale(permission)
    }

    private fun nearbyRequested(): Boolean = permRequested("nearby_perm_requested")

    private fun markNearbyRequested() = markPermRequested("nearby_perm_requested")

    private fun isNearbyPermanentlyDenied(): Boolean =
        isPermanentlyDenied(nearbyPermission(), "nearby_perm_requested")

    private fun updateNearbyButtonState() {
        val btn = view?.findViewById<Button>(R.id.btn_request_nearby_permission) ?: return
        when {
            hasNearbyPermission() -> {
                btn.text = getString(R.string.perm_permission_granted_label)
                btn.isEnabled = false
            }
            isNearbyPermanentlyDenied() -> {
                btn.text = getString(R.string.perm_go_to_settings_label)
                btn.isEnabled = true
            }
            else -> {
                btn.text = getString(R.string.perm_request_permission_label)
                btn.isEnabled = true
            }
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            })
        } catch (_: Exception) {}
    }

    private fun checkAllPermissions() {
        val finishButton = view?.findViewById<Button>(R.id.btn_finish) ?: return
        finishButton.isEnabled = isBatteryOptimizationDisabled() && hasNotificationPermission() && hasNearbyPermission()
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Granted by default on older versions
        }
    }

    private fun markNotificationRequested() = markPermRequested("notification_perm_requested")

    private fun isNotificationPermanentlyDenied(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return isPermanentlyDenied(Manifest.permission.POST_NOTIFICATIONS, "notification_perm_requested")
    }

    private fun updateBatteryButtonState() {
        val batteryButton = view?.findViewById<Button>(R.id.btn_go_to_battery_settings) ?: return
        if (isBatteryOptimizationDisabled()) {
            batteryButton.text = getString(R.string.perm_permission_granted_label)
            batteryButton.isEnabled = false
        } else {
            batteryButton.text = getString(R.string.perm_go_to_settings_label)
            batteryButton.isEnabled = true
        }
    }

    private fun updateNotificationButtonState() {
        val notificationButton = view?.findViewById<Button>(R.id.btn_request_notification_permission) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (hasNotificationPermission()) {
                notificationButton.text = getString(R.string.perm_permission_granted_label)
                notificationButton.isEnabled = false
            } else if (isNotificationPermanentlyDenied()) {
                notificationButton.text = getString(R.string.perm_go_to_settings_label)
                notificationButton.isEnabled = true
            } else {
                notificationButton.text = getString(R.string.perm_request_permission_label)
                notificationButton.isEnabled = true
            }
        } else {
            notificationButton.text = getString(R.string.perm_granted_by_default_label)
            notificationButton.isEnabled = false
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)
        }
        return true // Not applicable on older versions
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:${requireContext().packageName}")
                requestBatteryLauncher.launch(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), getString(R.string.perm_battery_settings_error_toast), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}