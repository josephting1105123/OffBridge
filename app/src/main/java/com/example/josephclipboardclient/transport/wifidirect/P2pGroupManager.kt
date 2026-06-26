package com.EntropicSoftwareLab.ClipboardShare.transport.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.security.SecureRandom

/**
 * Sender-side Wi-Fi Direct group lifecycle:
 * removeGroup (clear stale group) → createGroup → requestGroupInfo → credentials.
 *
 * All callbacks are delivered on the main thread.
 */
class P2pGroupManager(context: Context) {

    interface Listener {
        /**
         * [groupOwnerIp] is the actual IPv4 of the group-owner interface, or
         * null if it could not be read — callers fall back to the legacy
         * 192.168.49.1. Newer Android builds can randomize the GO address,
         * so the QR must carry the real one.
         *
         * [goDeviceAddress] is the group-owner's Wi-Fi Direct MAC (for the
         * receiver's P2P fallback join), or null when the OS hid it.
         * [frequencyMhz] is the group's operating frequency, or null if
         * unreadable. [usedBandPin] is false when group creation fell back to
         * the legacy OS-chosen-band path (root cause #1 telemetry).
         */
        fun onGroupReady(
            ssid: String,
            passphrase: String,
            groupOwnerIp: String?,
            goDeviceAddress: String?,
            frequencyMhz: Int?,
            usedBandPin: Boolean
        )
        /** [retryable] is false after the bounded retry budget is exhausted. */
        fun onGroupError(message: String, retryable: Boolean)
        /**
         * The framework channel (and thus the group) died with no other callback.
         * The manager auto-recreates the group; the UI should reflect the gap.
         */
        fun onGroupLost()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    /** Held so the channel-disconnect handler can re-run createGroup with the original listener. */
    private var currentListener: Listener? = null

    // Bounds the channel-disconnect auto-recreate loop. Without a cap, a persistently
    // failing Wi-Fi P2P stack would recreate the group forever (M-06). Reset to 0 on a
    // successful group creation; once it hits MAX_CHANNEL_RECREATES we surface a terminal
    // error instead of looping. Only ever touched on the main thread.
    private var channelRecreateAttempts = 0

    @Volatile private var tornDown = false

    companion object {
        private const val CREATE_RETRY_DELAY_MS = 500L
        private const val MAX_CREATE_ATTEMPTS = 2
        private const val GROUP_INFO_POLL_DELAY_MS = 300L
        private const val MAX_GROUP_INFO_POLLS = 10
        private const val CHANNEL_RECREATE_DELAY_MS = 1_000L
        private const val MAX_CHANNEL_RECREATES = 3
        // teardownAndSettle: let the chip settle after removeGroup before a caller joins another group.
        private const val POST_REMOVE_SETTLE_MS = 800L
        private const val SETTLE_FALLBACK_MS = 2_500L
        private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }

    /**
     * Creates a fresh group and reports its SSID/passphrase. Pre-emptively
     * removes any stale group first — createGroup fails with BUSY otherwise.
     */
    @SuppressLint("MissingPermission")
    fun createGroup(listener: Listener) {
        tornDown = false
        currentListener = listener
        val mgr = manager
        if (mgr == null) {
            listener.onGroupError("Wi-Fi Direct is not available on this device.", false)
            return
        }
        // A real ChannelListener (was null): when the framework drops the channel it
        // silently removes the group with no other callback, so the QR would keep
        // showing dead credentials. Notify the UI and auto-recreate (root cause #2).
        val ch = channel ?: mgr.initialize(appContext, Looper.getMainLooper()) { onChannelDisconnected() }
        channel = ch

        // Force a 2.4 GHz group so 2.4-only tablets can see the DIRECT-… SSID.
        // A null config falls back to the legacy OS-chosen-band path below.
        val config = buildConfig()

        // Clear any orphaned group; "no group to remove" failures are expected and swallowed.
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = attemptCreate(mgr, ch, listener, config, attempt = 1)
            override fun onFailure(reason: Int) = attemptCreate(mgr, ch, listener, config, attempt = 1)
        })
    }

    /**
     * Custom group config pinned to 2.4 GHz. Returns null on any builder
     * failure — the caller then degrades to the legacy createGroup path.
     */
    private fun buildConfig(): WifiP2pConfig? {
        return try {
            val rnd = SecureRandom()
            // Must match DIRECT-xy-…, ≤32 bytes — satisfied by construction.
            val name = "DIRECT-" + randomAlnum(rnd, 2) + "-Share" + randomAlnum(rnd, 4)
            val passphrase = randomAlnum(rnd, 12) // 8–63 chars required
            WifiP2pConfig.Builder()
                .setNetworkName(name)
                .setPassphrase(passphrase)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .enablePersistentMode(false)
                .build()
        } catch (_: Exception) {
            null
        }
    }

    private fun randomAlnum(rnd: SecureRandom, len: Int): String {
        val sb = StringBuilder(len)
        repeat(len) { sb.append(ALNUM[rnd.nextInt(ALNUM.length)]) }
        return sb.toString()
    }

    /**
     * Fallback ladder: config path → BUSY retry (still with config) → one
     * legacy fallback (config = null) → error. A null [config] is the
     * "already on legacy path" flag, so the legacy branch never loops back.
     */
    @SuppressLint("MissingPermission")
    private fun attemptCreate(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        listener: Listener,
        config: WifiP2pConfig?,
        attempt: Int
    ) {
        if (tornDown) return

        val actionListener = object : WifiP2pManager.ActionListener {
            // config != null ⇒ the 2.4 GHz band pin was used; null ⇒ legacy fallback path.
            override fun onSuccess() = pollGroupInfo(mgr, ch, listener, poll = 1, usedBandPin = config != null)
            override fun onFailure(reason: Int) {
                if (tornDown) return
                if (attempt < MAX_CREATE_ATTEMPTS && reason == WifiP2pManager.BUSY) {
                    // Stale group still tearing down — retry once after a short backoff.
                    mainHandler.postDelayed({
                        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() = attemptCreate(mgr, ch, listener, config, attempt + 1)
                            override fun onFailure(r: Int) = attemptCreate(mgr, ch, listener, config, attempt + 1)
                        })
                    }, CREATE_RETRY_DELAY_MS)
                } else if (config != null) {
                    // Any further config-path failure → one fallback to legacy createGroup.
                    // Worst case = pre-fix behavior (OS picks the band).
                    attemptCreate(mgr, ch, listener, config = null, attempt = 1)
                } else {
                    val message = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device."
                        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Turn off your hotspot to use off-grid send."
                        else -> "Could not create the off-grid group. Turn off your hotspot and make sure Wi-Fi is on."
                    }
                    listener.onGroupError(message, reason == WifiP2pManager.BUSY)
                }
            }
        }

        if (config != null) {
            mgr.createGroup(ch, config, actionListener)
        } else {
            mgr.createGroup(ch, actionListener)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pollGroupInfo(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        listener: Listener,
        poll: Int,
        usedBandPin: Boolean
    ) {
        if (tornDown) return
        mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            if (tornDown) return@requestGroupInfo
            val ssid = group?.networkName
            val psk = group?.passphrase
            if (!ssid.isNullOrEmpty() && !psk.isNullOrEmpty()) {
                // freq (API 29+) and owner MAC let us tell which root cause a device hits.
                val frequency = group?.frequency?.takeIf { it > 0 }
                val goDeviceAddress = group?.owner?.deviceAddress
                Log.i(
                    "OffGrid",
                    "GO up ssid=$ssid freq=${frequency ?: -1} bandPin=$usedBandPin mac=$goDeviceAddress"
                )
                // Group is fully up: clear the recreate budget so a later, unrelated
                // channel drop gets the full retry allowance again (M-06).
                channelRecreateAttempts = 0
                listener.onGroupReady(ssid, psk, resolveGroupOwnerIp(group), goDeviceAddress, frequency, usedBandPin)
            } else if (poll < MAX_GROUP_INFO_POLLS) {
                // Some OEMs populate credentials a beat after createGroup succeeds.
                mainHandler.postDelayed(
                    { pollGroupInfo(mgr, ch, listener, poll + 1, usedBandPin) },
                    GROUP_INFO_POLL_DELAY_MS
                )
            } else {
                listener.onGroupError("Could not read the group credentials.", true)
            }
        }
    }

    /** IPv4 of the group-owner side of the P2P interface (we are the GO). */
    private fun resolveGroupOwnerIp(group: WifiP2pGroup?): String? {
        val ifaceName = group?.`interface` ?: return null
        return try {
            val iface = java.net.NetworkInterface.getByName(ifaceName) ?: return null
            iface.inetAddresses.asSequence()
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Framework channel died: the OS has already torn down the group. Null the
     * channel so [createGroup] re-initializes it, tell the UI, and auto-recreate
     * after a short delay — unless we have been torn down deliberately.
     */
    private fun onChannelDisconnected() {
        if (tornDown) return
        channel = null
        val listener = currentListener ?: return
        listener.onGroupLost()
        // Cap the auto-recreate loop: a persistently failing P2P stack would otherwise
        // drop the channel and trigger createGroup forever (M-06). After the budget is
        // spent, surface a terminal (non-retryable) error and stop instead of looping.
        if (channelRecreateAttempts >= MAX_CHANNEL_RECREATES) {
            listener.onGroupError(
                "Wi-Fi Direct keeps dropping the connection. Turn Wi-Fi off and on, then try again.",
                false
            )
            return
        }
        channelRecreateAttempts++
        mainHandler.postDelayed({
            if (!tornDown) createGroup(listener)
        }, CHANNEL_RECREATE_DELAY_MS)
    }

    /** Idempotent: removeGroup on a nonexistent group is a no-op error we swallow. */
    fun teardown() {
        tornDown = true
        currentListener = null
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        } catch (_: Exception) {
        }
        // Release the framework Channel (binder-backed) so it doesn't leak one per recreate.
        try { ch.close() } catch (_: Exception) {}
        channel = null
    }

    /**
     * Like [teardown] but invokes [onDone] (on the main thread) once removeGroup has resolved AND a
     * short settle elapses — or after [SETTLE_FALLBACK_MS] if the framework never calls back. Lets a
     * caller wait for the chip to free up before joining another P2P group; MIUI rejects a join while a
     * GO it just removed is still tearing down (the "failed to join receiver's group" 20s timeout).
     */
    @SuppressLint("MissingPermission")
    fun teardownAndSettle(onDone: () -> Unit) {
        tornDown = true
        currentListener = null
        val mgr = manager
        val ch = channel
        var done = false
        val finish = {
            if (!done) {
                done = true
                // Release the framework Channel (binder-backed) so it doesn't leak one per recreate.
                try { ch?.close() } catch (_: Exception) {}
                channel = null
                onDone()
            }
        }
        if (mgr == null || ch == null) {
            mainHandler.post { finish() }
            return
        }
        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                // A beat after the group is gone before the chip will associate with another group.
                override fun onSuccess() { mainHandler.postDelayed({ finish() }, POST_REMOVE_SETTLE_MS) }
                override fun onFailure(reason: Int) { mainHandler.postDelayed({ finish() }, POST_REMOVE_SETTLE_MS) }
            })
        } catch (_: Exception) {
            mainHandler.post { finish() }
        }
        // Bounded fallback: never let a missing framework callback wedge the join.
        mainHandler.postDelayed({ finish() }, SETTLE_FALLBACK_MS)
    }
}
