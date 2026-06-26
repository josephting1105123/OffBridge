package com.EntropicSoftwareLab.ClipboardShare.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Receiver-side fallback for when the [android.net.wifi.WifiNetworkSpecifier]
 * picker can't resolve the sender's group (root cause #3: OEM pickers that
 * exclude BSSes carrying P2P IEs). Performs a real Wi-Fi Direct client join to
 * the group owner identified by its MAC, carried in the QR/NFC payload's
 * optional "mac" field, bypassing the picker entirely.
 *
 * 30 s budget; every [Listener] callback is delivered on the main thread.
 * Requires NEARBY_WIFI_DEVICES (API 33+) or ACCESS_FINE_LOCATION (≤ 32) — the
 * caller gates the join on that permission.
 */
class P2pFallbackJoiner(context: Context) {

    interface Listener {
        /**
         * Group joined. [groupOwnerAddress] is the GO's IPv4; [localAddress] is
         * this device's address on the p2p interface, or null if it could not be
         * read (the caller then connects unbound).
         */
        fun onJoined(groupOwnerAddress: InetAddress, localAddress: InetAddress?)
        fun onFailed()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var listener: Listener? = null
    private var targetMac: String = ""

    @Volatile private var finished = false
    @Volatile private var connectIssued = false

    companion object {
        private const val TAG = "OffGrid"
        private const val TOTAL_BUDGET_MS = 30_000L
        private const val PEER_POLL_INTERVAL_MS = 1_000L
        private const val PEER_POLL_BUDGET_MS = 10_000L
    }

    @SuppressLint("MissingPermission")
    @JvmOverloads
    fun join(mac: String, budgetMs: Long = TOTAL_BUDGET_MS, listener: Listener) {
        val mgr = manager
        if (mgr == null) {
            mainHandler.post { listener.onFailed() }
            return
        }
        finished = false
        connectIssued = false
        this.listener = listener
        this.targetMac = mac.lowercase()

        val ch = mgr.initialize(appContext, Looper.getMainLooper()) {
            // Channel died → fail once.
            failOnce()
        }
        channel = ch

        registerReceiver(mgr, ch)

        // Overall budget watchdog.
        mainHandler.postDelayed({ failOnce() }, budgetMs)

        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                // Discovery refused — try a blind connect anyway (address invitation).
                attemptConnect(mgr, ch)
            }
        })

        // Poll requestPeers for the target MAC; if it never surfaces, blind-connect.
        pollPeers(mgr, ch, elapsed = 0L)
    }

    @SuppressLint("MissingPermission")
    private fun pollPeers(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, elapsed: Long) {
        if (finished || connectIssued) return
        mgr.requestPeers(ch) { peers: WifiP2pDeviceList ->
            if (finished || connectIssued) return@requestPeers
            val match = peers.deviceList.any { it.deviceAddress?.lowercase() == targetMac }
            when {
                match -> attemptConnect(mgr, ch)
                elapsed + PEER_POLL_INTERVAL_MS >= PEER_POLL_BUDGET_MS ->
                    // Peer never surfaced in scans — one blind connect (works on stock).
                    attemptConnect(mgr, ch)
                else -> mainHandler.postDelayed(
                    { pollPeers(mgr, ch, elapsed + PEER_POLL_INTERVAL_MS) },
                    PEER_POLL_INTERVAL_MS
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptConnect(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (finished || connectIssued) return
        connectIssued = true
        val config = WifiP2pConfig().apply {
            deviceAddress = targetMac
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            // The group-formed result arrives via WIFI_P2P_CONNECTION_CHANGED_ACTION.
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                Log.i(TAG, "fallback connect() failed reason=$reason")
                failOnce()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun registerReceiver(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (finished || connectIssued) return
                        mgr.requestPeers(ch) { peers ->
                            if (finished || connectIssued) return@requestPeers
                            if (peers.deviceList.any { it.deviceAddress?.lowercase() == targetMac }) {
                                attemptConnect(mgr, ch)
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        if (finished) return
                        mgr.requestConnectionInfo(ch) { info: WifiP2pInfo ->
                            if (finished) return@requestConnectionInfo
                            val go = info.groupOwnerAddress
                            if (info.groupFormed && go != null) {
                                succeedOnce(go)
                            }
                        }
                    }
                }
            }
        }
        receiver = r
        // System-only broadcasts — register NOT_EXPORTED on API 33+ (ContextCompat handles the rest).
        ContextCompat.registerReceiver(appContext, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun succeedOnce(groupOwnerAddress: InetAddress) {
        if (finished) return
        finished = true
        // Cancel the budget watchdog + any pending polls, then stop reacting.
        mainHandler.removeCallbacksAndMessages(null)
        unregisterReceiver()
        val local = findLocalP2pAddress()
        val l = listener
        // Keep the group up — the socket rides this p2p connection. teardown() removes it later.
        mainHandler.post { l?.onJoined(groupOwnerAddress, local) }
    }

    private fun failOnce() {
        if (finished) return
        finished = true
        val l = listener
        teardown()
        mainHandler.post { l?.onFailed() }
    }

    private fun findLocalP2pAddress(): InetAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.name?.startsWith("p2p") == true && it.isUp }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
        } catch (_: Exception) {
            null
        }
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try { appContext.unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
    }

    /** cancelConnect + removeGroup + unregisterReceiver. Idempotent. */
    @SuppressLint("MissingPermission")
    fun teardown() {
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            try {
                mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) {}
                })
            } catch (_: Exception) {}
            try {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) {}
                })
            } catch (_: Exception) {}
            // Release the framework Channel (binder-backed) so it doesn't leak one per attempt.
            try { ch.close() } catch (_: Exception) {}
        }
        channel = null
        unregisterReceiver()
    }
}
