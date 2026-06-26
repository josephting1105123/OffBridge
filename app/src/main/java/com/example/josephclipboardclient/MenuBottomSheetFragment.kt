package com.EntropicSoftwareLab.ClipboardShare

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import android.app.Dialog
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
        val btnDiagnostics = view.findViewById<View>(R.id.btn_menu_diagnostics)
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

        btnDiagnostics.setOnClickListener {
            startActivity(Intent(requireContext(), DiagnosticsActivity::class.java))
            dismiss()
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
}