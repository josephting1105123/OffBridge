package com.EntropicSoftwareLab.ClipboardShare.transport.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper

/**
 * Generalized [android.net.wifi.WifiNetworkSpecifier] join (was
 * offgrid.P2pClientManager): given any SSID/PSK it resolves the network via
 * the OS picker. Used by tier T0 (Wi-Fi Direct GO credentials) and tier T3
 * (LocalOnlyHotspot credentials relayed over the control channel) — a LOHS
 * beacon carries no P2P IEs, so pickers that filter P2P BSSes still find it.
 *
 * Critical detail: every socket for the transfer must be created from
 * [Network.getSocketFactory] — the OS does not route to a specifier network
 * by default. The acquired [Network] is handed to the caller for exactly
 * that purpose.
 */
class SpecifierJoiner(context: Context) {

    interface Listener {
        fun onNetworkAvailable(network: Network)
        /** User rejected the OS consent dialog, or the target network is gone. */
        fun onNetworkUnavailable()
        fun onNetworkLost()
    }

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var watchdog: Runnable? = null

    @Volatile private var callbackFired = false

    // Set true once teardown() runs, so a late framework onLost (delivered after
    // the caller has already advanced to the next tier) does not fire a stale
    // onNetworkLost / onRouteLost. Reset on each connect().
    @Volatile private var released = false

    fun connect(ssid: String, psk: String, timeoutMs: Int, listener: Listener) {
        teardown()
        callbackFired = false
        released = false

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(psk)
            .build()

        // NET_CAPABILITY_INTERNET must be removed: the group has no internet
        // and the request never resolves otherwise.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                callbackFired = true
                cancelWatchdog()
                mainHandler.post { listener.onNetworkAvailable(network) }
            }

            override fun onUnavailable() {
                callbackFired = true
                cancelWatchdog()
                mainHandler.post { listener.onNetworkUnavailable() }
                // Release the request/callback now (mirrors the watchdog path) — otherwise the
                // NetworkCallback leaks until the next connect() and a late onLost could still fire.
                teardown()
            }

            override fun onLost(network: Network) {
                // Ignore a late onLost once torn down: the caller already moved
                // on, so delivering onNetworkLost now would be a stale event.
                if (released) return
                mainHandler.post { if (!released) listener.onNetworkLost() }
            }
        }
        callback = networkCallback
        // Bounded request: an unmatched picker (no matching SSID, or the user
        // dismisses it) fires onUnavailable() within the timeout instead of
        // spinning forever.
        connectivityManager.requestNetwork(request, networkCallback, timeoutMs)

        // Some OEMs swallow onUnavailable() entirely. A watchdog (timeout + grace)
        // synthesizes it once so the receiver UI never hangs on "Searching…".
        val wd = Runnable {
            if (!callbackFired) {
                callbackFired = true
                listener.onNetworkUnavailable()
                teardown()
            }
        }
        watchdog = wd
        mainHandler.postDelayed(wd, timeoutMs + WATCHDOG_GRACE_MS)
    }

    private fun cancelWatchdog() {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
    }

    private companion object {
        const val WATCHDOG_GRACE_MS = 5_000L
    }

    /**
     * Releases the network request; the phone returns to its home Wi-Fi
     * automatically. Idempotent.
     */
    fun teardown() {
        released = true
        cancelWatchdog()
        callback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
    }
}
