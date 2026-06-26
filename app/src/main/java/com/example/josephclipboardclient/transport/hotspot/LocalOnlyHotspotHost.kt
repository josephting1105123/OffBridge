package com.EntropicSoftwareLab.ClipboardShare.transport.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.EntropicSoftwareLab.ClipboardShare.transport.FailureCode
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Tier T3 host (§3.2): a LocalOnlyHotspot on the sender. Its beacon is a
 * NORMAL access-point beacon — no P2P Information Elements — so receiver
 * pickers that filter P2P BSSes (the root cause of "cannot find device")
 * resolve it reliably. Credentials are OS-generated and relayed to the
 * receiver over the encrypted control channel.
 *
 * Preconditions enforced by the OS, surfaced as [FailureCode.LOHS_DENIED]:
 * no concurrent tethering, Location on, and STA/AP concurrency support.
 * The caller must tear down ALL Wi-Fi P2P state first — GO + LOHS conflict.
 */
@SuppressLint("MissingPermission")
class LocalOnlyHotspotHost(context: Context) {

    interface Listener {
        /** Hotspot is beaconing. [hostIp] is the AP-side IPv4 the TCP server is reachable on. */
        fun onStarted(ssid: String, psk: String, hostIp: String)
        fun onFailed(code: FailureCode)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wifiManager =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var finished = false

    companion object {
        private const val TAG = "OffGrid"
        /** Pre-Android-11 LOHS default; used only when interface enumeration fails. */
        private const val LEGACY_LOHS_HOST_IP = "192.168.43.1"
    }

    fun start(budgetMs: Long, listener: Listener) {
        val mgr = wifiManager
        if (mgr == null) {
            mainHandler.post { listener.onFailed(FailureCode.UNSUPPORTED) }
            return
        }
        finished = false
        mainHandler.postDelayed({
            if (!finished) {
                finished = true
                listener.onFailed(FailureCode.LOHS_DENIED)
            }
        }, budgetMs)

        try {
            mgr.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    if (finished) {
                        try { res.close() } catch (_: Exception) {}
                        return
                    }
                    reservation = res
                    val creds = extractCredentials(res)
                    if (creds == null) {
                        finished = true
                        mainHandler.removeCallbacksAndMessages(null)
                        stop()
                        listener.onFailed(FailureCode.LOHS_DENIED)
                        return
                    }
                    finished = true
                    mainHandler.removeCallbacksAndMessages(null)
                    listener.onStarted(creds.first, creds.second, resolveHostIp())
                }

                override fun onFailed(reason: Int) {
                    if (finished) return
                    finished = true
                    mainHandler.removeCallbacksAndMessages(null)
                    Log.i(TAG, "LOHS failed reason=$reason")
                    listener.onFailed(FailureCode.LOHS_DENIED)
                }

                override fun onStopped() {
                    // OS reclaimed the hotspot (tethering started, etc.) — the
                    // active route drops; the data plane surfaces it as a lost
                    // connection and the resume machinery takes over.
                    reservation = null
                }
            }, mainHandler)
        } catch (e: SecurityException) {
            if (!finished) {
                finished = true
                mainHandler.removeCallbacksAndMessages(null)
                listener.onFailed(FailureCode.PERMISSION_DENIED)
            }
        } catch (e: Exception) {
            if (!finished) {
                finished = true
                mainHandler.removeCallbacksAndMessages(null)
                Log.i(TAG, "LOHS start threw: ${e.message}")
                listener.onFailed(FailureCode.LOHS_DENIED)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractCredentials(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cfg = res.softApConfiguration
                val ssid = cfg.ssid
                val psk = cfg.passphrase
                if (ssid.isNullOrEmpty() || psk.isNullOrEmpty()) null else Pair(ssid, psk)
            } else {
                val cfg = res.wifiConfiguration ?: return null
                val ssid = cfg.SSID?.removeSurrounding("\"")
                val psk = cfg.preSharedKey?.removeSurrounding("\"")
                if (ssid.isNullOrEmpty() || psk.isNullOrEmpty()) null else Pair(ssid, psk)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The AP-side interface address. Heuristic over live interfaces — names
     * starting with "ap", "swlan" or "softap" first, then any fresh
     * 192.168.x.x that is not the STA iface — with the historical
     * 192.168.43.1 as the final fallback.
     */
    private fun resolveHostIp(): String {
        try {
            val candidates = mutableListOf<Pair<String, String>>()
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .forEach { iface ->
                    iface.inetAddresses.asSequence()
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                        .forEach { addr ->
                            addr.hostAddress?.let { candidates.add(iface.name to it) }
                        }
                }
            candidates.firstOrNull { (name, _) ->
                name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")
            }?.let { return it.second }
            candidates.firstOrNull { (name, ip) ->
                name != "wlan0" && ip.startsWith("192.168.")
            }?.let { return it.second }
        } catch (_: Exception) {}
        return LEGACY_LOHS_HOST_IP
    }

    /** Releases the reservation; the OS tears the AP down. Idempotent. */
    fun stop() {
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        try { reservation?.close() } catch (_: Exception) {}
        reservation = null
    }
}
