package com.EntropicSoftwareLab.ClipboardShare.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
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
 * Tier T2, sender half (§3.2): bidirectional negotiated connect. Both ends
 * call connect() toward each other's P2P MAC with WPS PBC — simultaneous
 * intents suppress the consent dialogs and bypass SSID scanning entirely.
 * The sender runs this with groupOwnerIntent = 15 (it hosts the TCP server),
 * the receiver's half is the existing PBC join with intent 0.
 *
 * Budget-bounded; callbacks on the main thread.
 */
@SuppressLint("MissingPermission")
class P2pNegotiatedConnector(context: Context) {

    interface Listener {
        /**
         * Negotiated group formed. [isGroupOwner] tells the sender whether its
         * TCP server is reachable ([localP2pAddress] is then the DATA_READY ip);
         * a sender that lost the GO negotiation fails the tier instead.
         */
        fun onGroupFormed(isGroupOwner: Boolean, groupOwnerAddress: InetAddress?, localP2pAddress: InetAddress?)
        fun onFailed()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var listener: Listener? = null

    @Volatile private var finished = false

    companion object {
        private const val TAG = "OffGrid"
    }

    fun connect(peerMac: String, goIntent: Int, budgetMs: Long, listener: Listener) {
        val mgr = manager
        if (mgr == null) {
            mainHandler.post { listener.onFailed() }
            return
        }
        finished = false
        this.listener = listener

        val ch = mgr.initialize(appContext, Looper.getMainLooper()) { failOnce() }
        channel = ch

        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (finished) return
                if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                mgr.requestConnectionInfo(ch) { info: WifiP2pInfo ->
                    if (finished || !info.groupFormed) return@requestConnectionInfo
                    succeedOnce(info)
                }
            }
        }
        receiver = r
        ContextCompat.registerReceiver(appContext, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        mainHandler.postDelayed({ failOnce() }, budgetMs)

        // discoverPeers primes the supplicant; connect() is issued regardless —
        // a blind address-targeted connect works on stock when the peer is
        // simultaneously connecting back.
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })

        val config = WifiP2pConfig().apply {
            deviceAddress = peerMac.lowercase()
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = goIntent
        }
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                Log.i(TAG, "negotiated connect() failed reason=$reason")
                failOnce()
            }
        })
    }

    private fun succeedOnce(info: WifiP2pInfo) {
        if (finished) return
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        unregisterReceiver()
        val local = findLocalP2pAddress()
        val l = listener
        mainHandler.post { l?.onGroupFormed(info.isGroupOwner, info.groupOwnerAddress, local) }
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

    /** cancelConnect + removeGroup + unregister. Idempotent. */
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
