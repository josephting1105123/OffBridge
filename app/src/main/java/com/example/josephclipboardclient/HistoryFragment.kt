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
import com.EntropicSoftwareLab.ClipboardShare.ui.components.circularReveal
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier

class HistoryFragment : Fragment() {

    private val historyState = mutableStateOf<List<String>>(emptyList())
    /** Paired (trusted) devices first, then nearby-but-unpaired ones — NOT paired-only; count with
     *  `isPaired` when a number should mean "actually syncing". */
    private val pairedDevicesState = mutableStateOf<List<SendDeviceUi>>(emptyList())
    private val acceptSyncState = mutableStateOf(false)

    private var receiverRegistered = false

    private val historyUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ClipboardService.ACTION_HISTORY_UPDATED) refreshHistory()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val isLite = isLiteMode(requireContext())
        
        val revealX = arguments?.getFloat("reveal_x", -1f) ?: -1f
        val revealY = arguments?.getFloat("reveal_y", -1f) ?: -1f

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // Lite adds this over the hub — consume touches so empty areas don't fall through to the
            // hub tiles beneath.
            isClickable = true
            setContent {
                AppTheme {
                    if (isLite) {
                        Box(
                            modifier = Modifier.circularReveal(revealX, revealY)
                        ) {
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
                                onUnpair = { onUnpair(it) },
                                onRescan = { rescanDevices() },
                                onBack = { parentFragmentManager.popBackStack() }
                            )
                        }
                    } else {
                        ClipboardScreen(
                            history = historyState.value,
                            // Only actually-paired devices sync — nearby unpaired ones don't count.
                            pairedDeviceCount = pairedDevicesState.value.count { it.isPaired },
                            acceptSync = acceptSyncState.value,
                            onAcceptSyncChange = { enabled ->
                                ClipboardService.isSyncEnabled = enabled
                                acceptSyncState.value = enabled
                            },
                            onCopy = { copyToClipboard(it) },
                            onDelete = { deleteEntry(it) }
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

        val pairedDevices = SecurityVault.getTrustedNodes(ctx).map { ip ->
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
        
        val pairedIps = pairedDevices.map { it.ip }.toSet()
        val nearbyUnpaired = onlineNodes.filter { it.ip !in pairedIps }.map {
            SendDeviceUi(
                ip = it.ip,
                name = it.name,
                isWindows = it.os.equals("Windows", ignoreCase = true),
                isPaired = false
            )
        }
        
        pairedDevicesState.value = pairedDevices + nearbyUnpaired
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshSyncedToggles()
            refreshHistory()
        }
    }

    /** Same cache-flush rescan as the System Status pill: drop stale discoveries, repopulate. */
    private fun rescanDevices() {
        Toast.makeText(requireContext(), getString(R.string.status_flushing_cache), Toast.LENGTH_SHORT).show()
        TransferDaemon.clearDiscoveredNodes()
        refreshHistory()
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

    private fun copyToClipboard(item: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OffBridge", item))
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
