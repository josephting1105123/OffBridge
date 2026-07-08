package com.EntropicSoftwareLab.ClipboardShare

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.ClipboardScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.LiteClipboardScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.SendDeviceUi
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme

/**
 * Clipboard tab. The UI is now Compose ([ClipboardScreen], hosted via [ComposeView]); this fragment
 * keeps all clipboard/network logic and feeds the screen through observable state + callbacks.
 */
class HistoryFragment : Fragment() {

    private val historyState = mutableStateOf<List<String>>(emptyList())
    private val pairedDevicesState = mutableStateOf<List<SendDeviceUi>>(emptyList())
    private val acceptSyncState = mutableStateOf(false)
    private val showTutorialState = mutableStateOf(false)

    private var receiverRegistered = false

    private val historyUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ClipboardService.ACTION_HISTORY_UPDATED) refreshHistory()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val prefs = requireContext().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
        val isLite = prefs.getString("design_mode", "Lite") == "Lite"

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    if (isLite) {
                        LiteClipboardScreen(
                            history = historyState.value,
                            pairedDevices = pairedDevicesState.value,
                            acceptSync = acceptSyncState.value,
                            onAcceptSyncChange = { enabled ->
                                ClipboardService.isSyncEnabled = enabled
                                acceptSyncState.value = enabled
                            },
                            onCopy = { copyToClipboard(it) },
                            onDelete = { deleteEntry(it) },
                            onPair = { onPair(it) },
                            onUnpair = { onUnpair(it) }
                        )
                    } else {
                        ClipboardScreen(
                        history = historyState.value,
                        acceptSync = acceptSyncState.value,
                        onAcceptSyncChange = { enabled ->
                            ClipboardService.isSyncEnabled = enabled
                            acceptSyncState.value = enabled
                        },
                        onPushCurrent = { executeForegroundPush() },
                        onCopy = { copyToClipboard(it) },
                        onDelete = { deleteEntry(it) },
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
        refreshSyncedToggles()
        refreshHistory()
    }

    private fun refreshSyncedToggles() {
        acceptSyncState.value = ClipboardService.isSyncEnabled
    }

    private fun refreshHistory() {
        val ctx = context ?: return
        historyState.value = ClipboardHistoryManager.getHistory(ctx)

        val onlineNodes = TransferDaemon.discoveredNodes.values
        val onlineNames = onlineNodes.associate { it.ip to it.name }
        val onlineOs = onlineNodes.associate { it.ip to it.os }

        pairedDevicesState.value = SecurityVault.getTrustedNodes(ctx).map { ip ->
            val liveName = onlineNames[ip]
            val liveOs = onlineOs[ip]
            if (liveName != null && SecurityVault.getPairedDeviceName(ctx, ip) != liveName) {
                SecurityVault.savePairedDeviceName(ctx, ip, liveName)
            }
            SendDeviceUi(
                ip = ip,
                name = liveName ?: SecurityVault.getPairedDeviceName(ctx, ip) ?: ip,
                isWindows = liveOs?.equals("Windows", ignoreCase = true) ?: false,
                isPaired = true
            )
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshSyncedToggles()
            refreshHistory()
            maybeAutoShowTutorial()
        }
    }

    /** One-shot: the clipboard tutorial fires the first time this tab is opened after install. */
    private fun maybeAutoShowTutorial() {
        if (!isAdded || isHidden) return
        if (TutorialTour.isForced()) return
        val prefs = requireContext().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("clipboard_tutorial_shown", false)) return
        prefs.edit().putBoolean("clipboard_tutorial_shown", true).apply()
        showTutorialState.value = true
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        refreshSyncedToggles()
        val ctx = context
        if (ctx != null && !receiverRegistered) {
            val filter = IntentFilter(ClipboardService.ACTION_HISTORY_UPDATED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(historyUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(historyUpdateReceiver, filter)
            }
            receiverRegistered = true
        }
        refreshHistory()
        if (!isHidden) maybeAutoShowTutorial()
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) {
            try {
                context?.unregisterReceiver(historyUpdateReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered; ignore.
            }
            receiverRegistered = false
        }
    }

    private fun executeForegroundPush() {
        try {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val textToPush = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: "" else ""

            if (textToPush.isNotEmpty()) {
                val serviceIntent = Intent(requireContext(), ClipboardService::class.java).apply {
                    action = ClipboardService.ACTION_EXECUTE_NETWORK_PUSH
                    putExtra("PAYLOAD", textToPush)
                }
                requireContext().startService(serviceIntent)
                Toast.makeText(requireContext(), getString(R.string.history_toast_pushing), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.history_toast_empty), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.history_toast_read_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(item: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Clipboard Share", item))
        Toast.makeText(requireContext(), getString(R.string.history_toast_copied), Toast.LENGTH_SHORT).show()
    }

    private fun deleteEntry(item: String) {
        ClipboardHistoryManager.removeEntry(requireContext(), item)
        requireContext().startService(
            Intent(requireContext(), ClipboardService::class.java).apply {
                action = ClipboardService.ACTION_EXECUTE_NETWORK_DELETE
                putExtra("PAYLOAD", item)
            },
        )
        refreshHistory()
    }

    private fun onPair(ip: String) {
        val device = pairedDevicesState.value.find { it.ip == ip } ?: return
        startActivity(
            Intent(requireContext(), PairingActivity::class.java).apply {
                putExtra("TARGET_NAME", device.name)
                putExtra("TARGET_IP", device.ip)
            }
        )
    }

    private fun onUnpair(ip: String) {
        val device = pairedDevicesState.value.find { it.ip == ip } ?: return
        PairingDaemon.sendUnpairRequest(requireContext(), ip)
        SecurityVault.removeTrustedNode(requireContext(), ip)
        Toast.makeText(requireContext(), getString(R.string.status_unpaired_from, device.name), Toast.LENGTH_SHORT).show()
        refreshHistory()
    }
}
