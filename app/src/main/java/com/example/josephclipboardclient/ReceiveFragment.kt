package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridSendActivity
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.ReceiveScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme

/**
 * Bottom-nav Receive screen (Compose, hosted via [ComposeView]). Open Camera → QR scanner; NFC
 * receive → tap-position guide (PRESENTATION_NFC). NFC button is disabled when there's no NFC
 * hardware, and deep-links to settings when NFC is turned off.
 */
class ReceiveFragment : Fragment() {

    private val deviceNameState = mutableStateOf("")
    private val nfcUnavailableState = mutableStateOf(false)
    private val showTutorialState = mutableStateOf(false)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val prefs = requireContext().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
        val isLite = prefs.getString("design_mode", "Lite") == "Lite"

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    if (isLite) {
                        com.EntropicSoftwareLab.ClipboardShare.ui.screens.LiteReceiveScreen(
                            deviceName = deviceNameState.value,
                            onDeviceNameChange = ::onDeviceNameChange,
                            onOpenCamera = ::launchScanner
                        )
                    } else {
                        ReceiveScreen(
                        deviceName = deviceNameState.value,
                        onDeviceNameChange = ::onDeviceNameChange,
                        nfcUnavailable = nfcUnavailableState.value,
                        onOpenCamera = ::launchScanner,
                        onNfcReceive = ::onNfcReceive,
                        showTutorial = showTutorialState.value,
                        onShowTutorial = { showTutorialState.value = true },
                        onTutorialDismiss = { showTutorialState.value = false },
                    )
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        syncDeviceNameField()
        refreshNfc()
    }

    private fun onDeviceNameChange(newName: String) {
        deviceNameState.value = newName
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) {
            requireActivity().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
                .edit().putString("device_name", trimmed).apply()
            TransferDaemon.customDeviceName = trimmed
        }
    }

    private fun syncDeviceNameField() {
        if (!isAdded) return
        val prefs = requireContext().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
        val current = prefs.getString("device_name", getDefaultDeviceName()) ?: getDefaultDeviceName()
        if (deviceNameState.value != current) deviceNameState.value = current
    }

    private fun getDefaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim() ?: ""
        val model = Build.MODEL?.trim() ?: ""
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else if (manufacturer.isNotEmpty()) {
            "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        } else {
            model.ifEmpty { getString(R.string.settings_default_device_name) }
        }
    }

    override fun onResume() {
        super.onResume()
        syncDeviceNameField()
        refreshNfc()
        if (!isHidden) maybeAutoShowTutorial()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            syncDeviceNameField()
            refreshNfc()
            maybeAutoShowTutorial()
        }
    }

    private fun launchScanner() {
        startActivity(
            Intent(requireContext(), OffGridSendActivity::class.java).apply {
                putExtra(OffGridSendActivity.EXTRA_MODE, OffGridSendActivity.MODE_RECEIVE)
            },
        )
    }

    /** NFC button: deep-link to settings when NFC is off, else open the tap-guide (PRESENTATION_NFC). */
    private fun onNfcReceive() {
        val adapter = NfcAdapter.getDefaultAdapter(requireContext()) ?: return
        if (!adapter.isEnabled) {
            try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (_: Exception) {}
            return
        }
        startActivity(
            Intent(requireContext(), OffGridSendActivity::class.java).apply {
                putExtra(OffGridSendActivity.EXTRA_MODE, OffGridSendActivity.MODE_RECEIVE)
                putExtra(OffGridSendActivity.EXTRA_PRESENTATION, OffGridSendActivity.PRESENTATION_NFC)
            },
        )
    }

    private fun refreshNfc() {
        if (!isAdded) return
        nfcUnavailableState.value = NfcAdapter.getDefaultAdapter(requireContext()) == null
    }

    private fun maybeAutoShowTutorial() {
        if (!isAdded || isHidden) return
        if (TutorialTour.isForced()) return
        val prefs = requireContext().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("receive_tutorial_shown", false)) return
        prefs.edit().putBoolean("receive_tutorial_shown", true).apply()
        showTutorialState.value = true
    }
}
