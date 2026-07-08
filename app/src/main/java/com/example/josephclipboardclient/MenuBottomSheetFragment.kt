package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import android.app.Dialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.EntropicSoftwareLab.ClipboardShare.ui.components.DesignModeToggle
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MenuBottomSheetFragment : BottomSheetDialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.TransparentBottomSheetDialogTheme)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        // Backdrop only — sheets keep their native slide-up entrance.
        dialog.window?.applyModalBackdrop()
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_menu, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnSettings = view.findViewById<View>(R.id.btn_menu_settings)
        val btnWindows = view.findViewById<View>(R.id.btn_menu_windows)
        val toggleDesignMode = view.findViewById<ComposeView>(R.id.toggle_design_mode)
        val btnRestart = view.findViewById<View>(R.id.btn_menu_restart)
        val btnPowerOff = view.findViewById<View>(R.id.btn_menu_power_off)

        btnSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
            dismiss()
        }

        btnWindows.setOnClickListener {
            startActivity(Intent(requireContext(), WindowsVersionActivity::class.java))
            dismiss()
        }

        // Diagnostics moved to Settings -> Debugging; this slot is now the Basic/Pro design-mode
        // switch. Selecting a mode writes it to FileBridgePrefs and restarts through SplashActivity
        // (same is_rebooting + NEW_TASK|CLEAR_TASK pattern as "Restart services" / the language
        // change in SettingsFragment) so MainActivity rebuilds its shell from scratch.
        toggleDesignMode.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        toggleDesignMode.setContent {
            AppTheme {
                val prefs = requireContext().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
                val currentIsLite = prefs.getString("design_mode", "Lite") == "Lite"
                DesignModeToggle(
                    options = listOf(getString(R.string.design_mode_basic), getString(R.string.design_mode_pro)),
                    selectedIndex = if (currentIsLite) 0 else 1,
                    onSelect = { index ->
                        val newMode = if (index == 0) "Lite" else "Pro"
                        if (newMode != prefs.getString("design_mode", "Lite")) {
                            prefs.edit().putString("design_mode", newMode).apply()
                            dismiss()
                            restartApp()
                        }
                    },
                )
            }
        }

        btnRestart.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.menu_restart_toast), Toast.LENGTH_SHORT).show()
            try {
                val serviceIntent = Intent(requireContext(), ClipboardService::class.java)
                requireContext().stopService(serviceIntent)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(serviceIntent)
                } else {
                    requireContext().startService(serviceIntent)
                }
            } catch (e: Exception) { e.printStackTrace() }

            val restartIntent = Intent(requireContext(), SplashActivity::class.java)
            restartIntent.putExtra("is_rebooting", true)
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(restartIntent)
            requireActivity().overridePendingTransition(android.R.anim.fade_in, R.anim.stay)
        }

        btnPowerOff.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.menu_power_off_toast), Toast.LENGTH_SHORT).show()
            try {
                // Full shutdown: release the daemon's wakelock / Wi-Fi / multicast locks and cancel
                // its coroutines BEFORE stopping the service, so "Power Off" actually frees the CPU
                // and radio instead of leaving them pinned for the rest of the process lifetime.
                TransferDaemon.stop()
                PairingDaemon.stop()
                val serviceIntent = Intent(requireContext(), ClipboardService::class.java)
                requireContext().stopService(serviceIntent)
            } catch (e: Exception) { e.printStackTrace() }

            // Graceful shutdown: dismiss this sheet first so its dialog window is
            // properly torn down, then let the activity finish normally. Avoid
            // Runtime.exit(0), which abruptly kills the process and can leak the
            // dialog window or corrupt an in-flight SharedPreferences write.
            dismiss()
            requireActivity().finishAndRemoveTask()
        }
    }

    /** Bounces the daemon service and restarts through SplashActivity — the same shell-reload
     * pattern "Restart services" and the Settings language change already use — so MainActivity
     * re-reads design_mode from FileBridgePrefs and rebuilds the correct (Lite/Pro) shell. */
    private fun restartApp() {
        try {
            val serviceIntent = Intent(requireContext(), ClipboardService::class.java)
            requireContext().stopService(serviceIntent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(serviceIntent)
            } else {
                requireContext().startService(serviceIntent)
            }
        } catch (e: Exception) { e.printStackTrace() }

        val restartIntent = Intent(requireContext(), SplashActivity::class.java)
        restartIntent.putExtra("is_rebooting", true)
        restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(restartIntent)
        requireActivity().overridePendingTransition(android.R.anim.fade_in, R.anim.stay)
    }
}