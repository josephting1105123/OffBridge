package com.EntropicSoftwareLab.ClipboardShare.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
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
 * Tier T1 (§3.2 of the V4 plan): join the sender's EXISTING autonomous group
 * as a Wi-Fi P2P client using the SSID/PSK carried in the QR — no
 * WifiNetworkSpecifier picker, no GO-side consent, no MAC discovery.
 *
 * `WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(psk)` (API 29,
 * exactly minSdk) is the framework's fast-connection path for this case: the
 * supplicant associates directly with the GO's BSS, bypassing the scan-results
 * visibility problem that produces the OS "cannot find device" dialog.
 *
 * Budget-bounded; every [Listener] callback is delivered on the main thread.
 * Requires NEARBY_WIFI_DEVICES (API 33+) or ACCESS_FINE_LOCATION (≤ 32) —
 * the caller gates the attempt on that permission.
 */
class P2pCredentialJoiner(context: Context) {

    interface Listener {
        /**
         * Group joined as client. [groupOwnerAddress] is the GO's IPv4;
         * [localAddress] is this device's address on the p2p interface, or
         * null if it could not be read (the caller then connects unbound).
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
    private var targetSsid: String = ""

    @Volatile private var finished = false

    companion object {
        private const val TAG = "OffGrid"
    }

    /**
     * [budgetMs] is the tier's wall-clock budget (15 s per the tier table);
     * the joiner enforces it internally so a missing framework callback can
     * never wedge the ladder.
     */
    @SuppressLint("MissingPermission")
    fun join(ssid: String, psk: String, budgetMs: Long, listener: Listener) {
        val mgr = manager
        if (mgr == null) {
            mainHandler.post { listener.onFailed() }
            return
        }
        finished = false
        this.listener = listener
        this.targetSsid = ssid

        val ch = mgr.initialize(appContext, Looper.getMainLooper()) { failOnce() }
        channel = ch

        registerReceiver(mgr, ch)
        mainHandler.postDelayed({ failOnce() }, budgetMs)

        val config = try {
            WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(psk)
                .build()
        } catch (e: Exception) {
            // Builder rejects non-DIRECT-xy names / out-of-range PSKs; a v1
            // payload that passed schema validation can still trip this on
            // OEM-mangled SSIDs.
            Log.i(TAG, "credential-join config rejected: ${e.message}")
            failOnce()
            return
        }

        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            // The group-formed result arrives via WIFI_P2P_CONNECTION_CHANGED_ACTION.
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                Log.i(TAG, "credential-join connect() failed reason=$reason")
                failOnce()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun registerReceiver(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (finished) return
                if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                mgr.requestConnectionInfo(ch) { info: WifiP2pInfo ->
                    if (finished) return@requestConnectionInfo
                    val go = info.groupOwnerAddress
                    // We must have come up as CLIENT of the sender's group. CONNECTION_CHANGED is a
                    // sticky broadcast, so a leftover pre-existing client group can be delivered the
                    // instant we register — confirm the joined group is actually the one we asked for
                    // (networkName == requested SSID) before reporting success, otherwise we'd hand the
                    // caller the wrong GO address.
                    if (info.groupFormed && !info.isGroupOwner && go != null) {
                        mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
                            if (finished) return@requestGroupInfo
                            if (group != null && group.networkName == targetSsid) {
                                succeedOnce(go)
                            }
                        }
                    }
                }
            }
        }
        receiver = r
        ContextCompat.registerReceiver(appContext, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun succeedOnce(groupOwnerAddress: InetAddress) {
        if (finished) return
        finished = true
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
