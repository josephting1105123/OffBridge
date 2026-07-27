package com.EntropicSoftwareLab.ClipboardShare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.DeviceUi
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.PairedDeviceUi
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.SystemStatusScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pairing / System Status tab (Compose, hosted via [ComposeView]). This fragment keeps the 2s UI
 * polling engine, network-state evaluation and pairing/discovery logic, and feeds [SystemStatusScreen]
 * observable state + callbacks.
 */
class SystemStatusFragment : Fragment() {

    private val isConnectedState = mutableStateOf(true)
    private val statusTextState = mutableStateOf("")
    private val discoverableState = mutableStateOf(true)
    private val deviceNameState = mutableStateOf("")
    private val devicesState = mutableStateOf<List<DeviceUi>>(emptyList())
    private val pairedDevicesState = mutableStateOf<List<PairedDeviceUi>>(emptyList())
    private val scanningState = mutableStateOf(false)
    private val showTutorialState = mutableStateOf(false)

    private val uiJob = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + uiJob)

    companion object {
        const val ACTION_PAIRING_SUCCESSFUL = "com.example.josephclipboardclient.PAIRING_SUCCESSFUL"
    }

    private var receiversRegistered = false

    private val unpairReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.EntropicSoftwareLab.ClipboardShare.UNPAIRED_REMOTELY") refreshDeviceList()
        }
    }

    private val pairingSuccessListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PAIRING_SUCCESSFUL) refreshDeviceList()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    SystemStatusScreen(
                        isConnected = isConnectedState.value,
                        statusText = statusTextState.value,
                        discoverable = discoverableState.value,
                        onDiscoverableChange = { enabled ->
                            SecurityVault.setDiscoverable(requireContext(), enabled)
                            discoverableState.value = enabled
                        },
                        deviceName = deviceNameState.value,
                        onDeviceNameChange = ::onDeviceNameChange,
                        devices = devicesState.value,
                        pairedDevices = pairedDevicesState.value,
                        scanning = scanningState.value,
                        onPair = ::onPair,
                        onUnpair = ::onUnpair,
                        onActionButton = ::onActionButton,
                        onQrPair = {
                            startActivity(Intent(requireContext(), QrPairActivity::class.java))
                        },
                        showTutorial = showTutorialState.value,
                        onShowTutorial = { showTutorialState.value = true },
                        onTutorialDismiss = { showTutorialState.value = false },
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        discoverableState.value = SecurityVault.isDiscoverable(requireContext())
        syncDeviceName()
        startUiPollingEngine()
    }

    private fun onDeviceNameChange(newName: String) {
        deviceNameState.value = newName
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) {
            requireActivity().getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
                .edit().putString("device_name", trimmed).apply()
            TransferDaemon.customDeviceName = trimmed
        }
    }

    private fun syncDeviceName() {
        if (!isAdded) return
        val prefs = requireActivity().getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString("device_name", getDefaultDeviceName()) ?: getDefaultDeviceName()
        if (deviceNameState.value != current) deviceNameState.value = current
    }

    override fun onResume() {
        super.onResume()
        PairingDaemon.start(requireContext().applicationContext)

        val ctx = context
        if (ctx != null && !receiversRegistered) {
            val unpairFilter = IntentFilter("com.EntropicSoftwareLab.ClipboardShare.UNPAIRED_REMOTELY")
            val successFilter = IntentFilter(ACTION_PAIRING_SUCCESSFUL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(unpairReceiver, unpairFilter, Context.RECEIVER_NOT_EXPORTED)
                ctx.registerReceiver(pairingSuccessListener, successFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(unpairReceiver, unpairFilter)
                ctx.registerReceiver(pairingSuccessListener, successFilter)
            }
            receiversRegistered = true
        }

        syncDeviceName()
        discoverableState.value = SecurityVault.isDiscoverable(requireContext())
        refreshDeviceList()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            syncDeviceName()
            discoverableState.value = SecurityVault.isDiscoverable(requireContext())
        }
    }

    override fun onPause() {
        super.onPause()
        if (receiversRegistered) {
            val ctx = context
            try { ctx?.unregisterReceiver(unpairReceiver) } catch (e: IllegalArgumentException) {}
            try { ctx?.unregisterReceiver(pairingSuccessListener) } catch (e: IllegalArgumentException) {}
            receiversRegistered = false
        }
    }

    private fun startUiPollingEngine() {
        uiScope.launch {
            while (isActive) {
                evaluateStateAndRefreshUI()
                delay(2000)
            }
        }
    }

    private fun hasValidNetwork(): Boolean {
        return try {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (ex: Exception) {
            false
        }
    }

    private fun evaluateStateAndRefreshUI() {
        if (!isAdded) return
        if (!TransferDaemon.isRunning && hasValidNetwork()) TransferDaemon.start(requireContext().applicationContext)
        val connected = hasValidNetwork()
        isConnectedState.value = connected
        statusTextState.value = getString(if (connected) R.string.status_active_and_polling else R.string.status_no_network)
        refreshDeviceList()
    }

    private fun refreshDeviceList() {
        if (!isAdded) return
        val ctx = requireContext()
        val allNodes = TransferDaemon.discoveredNodes.values.toList()
        val (paired, unpaired) = allNodes.partition { SecurityVault.getKeyForNode(ctx, it.ip) != null }
        val sorted = paired.sortedBy { it.name } + unpaired.sortedBy { it.name }
        devicesState.value = sorted.map {
            DeviceUi(
                name = it.name,
                ip = it.ip,
                isWindows = it.os.equals("Windows", ignoreCase = true),
                isPaired = SecurityVault.getKeyForNode(ctx, it.ip) != null,
            )
        }
        val onlineNames = allNodes.associate { it.ip to it.name }
        pairedDevicesState.value = SecurityVault.getTrustedNodes(ctx).map { ip ->
            val liveName = onlineNames[ip]
            // Backfill: persist the live name so it survives the device going offline (covers devices
            // paired before names were ever stored).
            if (liveName != null && SecurityVault.getPairedDeviceName(ctx, ip) != liveName) {
                SecurityVault.savePairedDeviceName(ctx, ip, liveName)
            }
            PairedDeviceUi(
                name = liveName ?: SecurityVault.getPairedDeviceName(ctx, ip) ?: ip,
                isOnline = ip in onlineNames,
            )
        }
        scanningState.value = isConnectedState.value && sorted.isEmpty()
    }

    private fun onPair(device: DeviceUi) {
        startActivity(
            Intent(requireContext(), PairingActivity::class.java).apply {
                putExtra("TARGET_NAME", device.name)
                putExtra("TARGET_IP", device.ip)
            },
        )
    }

    private fun onUnpair(device: DeviceUi) {
        PairingDaemon.sendUnpairRequest(requireContext(), device.ip)
        SecurityVault.removeTrustedNode(requireContext(), device.ip)
        Toast.makeText(requireContext(), getString(R.string.status_unpaired_from, device.name), Toast.LENGTH_SHORT).show()
        refreshDeviceList()
    }

    private fun onActionButton() {
        if (isConnectedState.value) {
            Toast.makeText(requireContext(), getString(R.string.status_flushing_cache), Toast.LENGTH_SHORT).show()
            TransferDaemon.clearDiscoveredNodes()
            refreshDeviceList()
        } else {
            Toast.makeText(requireContext(), getString(R.string.status_rebooting_engines), Toast.LENGTH_SHORT).show()
            rebootApplication()
        }
    }

    private fun rebootApplication() {
        try {
            requireContext().stopService(Intent(requireContext(), ClipboardService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val restartIntent = Intent(requireContext(), SplashActivity::class.java).apply {
            putExtra("is_rebooting", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(restartIntent)
        requireActivity().overridePendingTransition(android.R.anim.fade_in, R.anim.stay)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiJob.cancel()
    }

    private fun getDefaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim() ?: ""
        val model = Build.MODEL?.trim() ?: ""
        return if (model.startsWith(manufacturer, ignoreCase = true)) model
        else if (manufacturer.isNotEmpty()) "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        else model.ifEmpty { getString(R.string.status_default_device_name) }
    }
}
