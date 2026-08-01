package com.EntropicSoftwareLab.ClipboardShare.transport

import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridPayload
import com.EntropicSoftwareLab.ClipboardShare.transport.hotspot.SpecifierJoiner
import com.EntropicSoftwareLab.ClipboardShare.transport.wifidirect.P2pCredentialJoiner
import com.EntropicSoftwareLab.ClipboardShare.transport.wifidirect.P2pFallbackJoiner
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Receiver-side transport ladder (§3 of the V4 plan).
 *
 * Two modes share the same tier executors:
 *  - LOCAL (no control channel / Phase 1): a fixed precondition-filtered
 *    sequence — T1 credential join → T0 specifier → T2 PBC join — advanced
 *    automatically on failure. O(T) tiers, T ≤ 3 here.
 *  - CONTROL (Phase 2): [executeTier] runs exactly one tier on demand from
 *    the ReceiverSessionController when a TRY_TRANSPORT frame arrives; the
 *    sender coordinates the sequence.
 *
 * Every attempt is recorded via [TransportAttemptLog] either way. All
 * callbacks are delivered on the main thread (the joiners guarantee it).
 */
class TransportLadder(
    context: Context,
    private val payload: OffGridPayload
) {

    interface Listener {
        /** A tier attempt began. [ordinal] is 1-based within [total] planned tiers. */
        fun onTierStarted(tier: TransportDescriptor, ordinal: Int, total: Int)
        /** Informational — the ladder advances automatically after this. */
        fun onTierFailed(tier: TransportDescriptor, code: FailureCode)
        fun onConnected(tier: TransportDescriptor, route: TransportRoute)
        fun onAllTiersFailed()
        /** The winning specifier network dropped mid-use (T0/T3 only). */
        fun onRouteLost()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Correlates receiver-side telemetry with the sender's: both ends can
     * derive the same id from the shared session token. Not a secret.
     */
    val ladderId: String = sha256Hex(payload.token).substring(0, 10)

    private var credentialJoiner: P2pCredentialJoiner? = null
    private var specifierJoiner: SpecifierJoiner? = null
    private var fallbackJoiner: P2pFallbackJoiner? = null

    @Volatile private var cancelled = false

    companion object {
        /** Settle gap between tiers that re-drive the Wi-Fi radio (picker → P2P etc.). */
        private const val INTER_TIER_SETTLE_MS = 1_000L

        /**
         * Drops tiers the local ladder cannot possibly run, given what it actually holds.
         *
         * A tier whose inputs are missing does not fail slowly and informatively — it fails in about
         * a second with CONNECT_TIMEOUT, which reads like a radio problem and hides the real cause.
         * The trace that motivated this filter is a LAN-only sender (empty ssid/psk, no GO): after
         * the control channel degraded the ladder still announced "Wi-Fi tier 1/1: P2P_CRED_JOIN",
         * failed instantly, and reported "all Wi-Fi tiers exhausted" — a join of a group whose SSID
         * is the empty string was never going to work.
         *
         * Two classes of precondition:
         *  - CREDENTIALS. Everything that joins an AP (Wi-Fi Direct group or SoftAP alike) needs a
         *    non-blank ssid+psk; [P2P_NEGOTIATED] needs the group owner's MAC instead.
         *  - THE CONTROL CHANNEL. [RECEIVER_AS_GO] and [SENDER_JOINS_AP] only work by relaying creds
         *    in a HOST_READY control frame, so once control has degraded they are structurally dead
         *    and must not be attempted. Credential-free / QR-credential tiers stay eligible.
         *
         * Pure and unit-tested; O(n) over a list that never exceeds a handful of tiers.
         */
        fun eligibleLocalTiers(
            candidates: List<TransportDescriptor>,
            hasApCredentials: Boolean,
            hasGroupOwnerMac: Boolean,
            controlDegraded: Boolean
        ): List<TransportDescriptor> = candidates.filter { tier ->
            when (tier) {
                TransportDescriptor.P2P_CRED_JOIN,
                TransportDescriptor.SPECIFIER,
                TransportDescriptor.LOHS,
                TransportDescriptor.SOFTAP_JOIN -> hasApCredentials

                TransportDescriptor.P2P_NEGOTIATED -> hasGroupOwnerMac

                TransportDescriptor.RECEIVER_AS_GO,
                TransportDescriptor.SENDER_JOINS_AP -> !controlDegraded

                // Retired data paths — never runnable here (executeTier reports UNSUPPORTED).
                TransportDescriptor.BT_L2CAP, TransportDescriptor.AWARE -> false
            }
        }
    }

    // ------------------------------------------------------------ local mode

    /**
     * Phase 1 sequence. P2P_CRED_JOIN leads, with P2P_NEGOTIATED behind it when a
     * group-owner address is known; [hasNearbyPermission] gates these true-P2P
     * tiers (NEARBY_WIFI_DEVICES / fine location). SPECIFIER trails them as the
     * last resort — it is slower and shows an OS consent dialog, and on some devices
     * it never finds the GO's beacon, but it is the only tier that hands the radio
     * decision to the framework, so it is the one chance left when the phone is
     * already associated with another network. Without the permission it is also the
     * sole Wi-Fi fallback, so the ladder is never empty. [preferredOrder] (Phase 3)
     * then reorders by per-peer historical success; unknown ids are ignored, missing
     * ids keep position.
     */
    fun start(
        hasNearbyPermission: Boolean,
        preferredOrder: List<Int>? = null,
        controlDegraded: Boolean = false,
        listener: Listener
    ) {
        cancelled = false
        val tiers = mutableListOf<TransportDescriptor>()
        if (payload.softApBridge) {
            // The QR creds are the sender's mobile HOTSPOT (SoftAP), not a Wi-Fi Direct group — join
            // it directly via WifiNetworkSpecifier. This works WITHOUT the BLE control channel, so the
            // hotspot bridge survives BLE discovery flakiness / Android's rapid-rescan throttle (the
            // "succeeded once, then failed" device traces). No P2P tiers — they'd target no group.
            tiers.add(TransportDescriptor.SOFTAP_JOIN)
        } else if (hasNearbyPermission) {
            tiers.add(TransportDescriptor.P2P_CRED_JOIN)
            if (payload.goDeviceAddress != null) tiers.add(TransportDescriptor.P2P_NEGOTIATED)
            // Last resort, and the only one that can work when this phone's Wi-Fi is already
            // associated with some other network: P2P_CRED_JOIN drives the p2p interface alongside
            // that STA link, which fails outright on chipsets that can't hold both (band-pinned GO
            // on one channel, home AP on another → CONNECT_TIMEOUT and a one-tier ladder that
            // dead-ends). A WifiNetworkSpecifier request targets the GO's SSID as an ordinary AP
            // and lets the framework itself decide how to free the radio. It costs a consent tap
            // and only runs once the fast path has already failed.
            tiers.add(TransportDescriptor.SPECIFIER)
        } else {
            // Without NEARBY_WIFI_DEVICES the true-P2P tiers can't run; keep the
            // specifier picker as the sole Wi-Fi fallback so the ladder isn't empty.
            tiers.add(TransportDescriptor.SPECIFIER)
        }
        if (!preferredOrder.isNullOrEmpty()) {
            // Stable sort: historically-successful tiers first, plan order otherwise.
            // Keys come from a frozen snapshot — never from the list being sorted.
            val defaultOrder = tiers.toList()
            tiers.sortBy { t ->
                val i = preferredOrder.indexOf(t.id)
                if (i >= 0) i else preferredOrder.size + defaultOrder.indexOf(t)
            }
        }
        val hasApCredentials = payload.ssid.isNotBlank() && payload.psk.isNotBlank()
        val runnable = eligibleLocalTiers(
            candidates = tiers,
            hasApCredentials = hasApCredentials,
            hasGroupOwnerMac = payload.goDeviceAddress != null,
            controlDegraded = controlDegraded
        )
        if (runnable.size != tiers.size) {
            val dropped = tiers.filterNot { runnable.contains(it) }.joinToString(",") { it.wireName }
            TransportDiag.log("Wi-Fi ladder: skipping unrunnable tier(s) $dropped (creds=$hasApCredentials controlDegraded=$controlDegraded)")
        }
        if (runnable.isEmpty()) {
            // Nothing here can succeed, so say so now instead of burning a doomed tier's timeout and
            // reporting a misleading CONNECT_TIMEOUT. The caller surfaces the actionable card.
            // Posted, not called inline: every other terminal callback from this class arrives
            // asynchronously, so the listener has never had to be re-entrant against start().
            TransportDiag.log("Wi-Fi ladder: no runnable tier — sender published no joinable network")
            mainHandler.post { if (!cancelled) listener.onAllTiersFailed() }
            return
        }
        runFrom(0, runnable, listener)
    }

    private fun runFrom(index: Int, tiers: List<TransportDescriptor>, listener: Listener) {
        if (cancelled) return
        if (index >= tiers.size) {
            listener.onAllTiersFailed()
            return
        }
        val tier = tiers[index]
        listener.onTierStarted(tier, index + 1, tiers.size)
        executeTier(
            tier,
            ssid = payload.ssid,
            psk = payload.psk,
            mac = payload.goDeviceAddress,
            onLost = { listener.onRouteLost() }
        ) { route, code ->
            if (cancelled) return@executeTier
            if (route != null) {
                listener.onConnected(tier, route)
            } else {
                listener.onTierFailed(tier, code)
                mainHandler.postDelayed(
                    { runFrom(index + 1, tiers, listener) },
                    INTER_TIER_SETTLE_MS
                )
            }
        }
    }

    // ---------------------------------------------------------- tier executor

    /**
     * Runs exactly one tier and reports (route, NONE) or (null, failureCode).
     * Control mode calls this directly with TRY_TRANSPORT parameters; T4 is
     * executed by the controller itself (it owns the BluetoothDevice from the
     * BLE scan), never here.
     */
    fun executeTier(
        tier: TransportDescriptor,
        ssid: String,
        psk: String,
        mac: String?,
        onLost: () -> Unit = {},
        done: (TransportRoute?, FailureCode) -> Unit
    ) {
        val attempt = TransportAttemptLog.begin(appContext, ladderId, tier)
        val finish: (TransportRoute?, FailureCode) -> Unit = { route, code ->
            TransportAttemptLog.end(
                appContext, attempt,
                if (route != null) AttemptOutcome.CONNECTED else AttemptOutcome.FAILED,
                code
            )
            done(route, code)
        }

        when (tier) {
            TransportDescriptor.P2P_CRED_JOIN -> {
                val joiner = P2pCredentialJoiner(appContext).also {
                    credentialJoiner?.teardown()
                    credentialJoiner = it
                }
                joiner.join(ssid, psk, tier.budgetMs, object : P2pCredentialJoiner.Listener {
                    override fun onJoined(groupOwnerAddress: InetAddress, localAddress: InetAddress?) {
                        finish(P2pRoute(groupOwnerAddress, localAddress), FailureCode.NONE)
                    }

                    override fun onFailed() {
                        joiner.teardown()
                        credentialJoiner = null
                        finish(null, FailureCode.CONNECT_TIMEOUT)
                    }
                })
            }

            // SOFTAP_JOIN joins the sender's mobile hotspot (a real SoftAP), same WifiNetworkSpecifier
            // mechanism as SPECIFIER/LOHS — the only correct way to join a SoftAP from an app.
            TransportDescriptor.SPECIFIER, TransportDescriptor.LOHS, TransportDescriptor.SOFTAP_JOIN -> {
                val joiner = specifierJoiner ?: SpecifierJoiner(appContext).also { specifierJoiner = it }
                joiner.connect(ssid, psk, tier.budgetMs.toInt(), object : SpecifierJoiner.Listener {
                    override fun onNetworkAvailable(network: Network) {
                        finish(SpecifierRoute(appContext, network), FailureCode.NONE)
                    }

                    override fun onNetworkUnavailable() {
                        finish(null, FailureCode.PICKER_TIMEOUT)
                    }

                    override fun onNetworkLost() {
                        onLost()
                    }
                })
            }

            TransportDescriptor.P2P_NEGOTIATED -> {
                if (mac == null) {
                    finish(null, FailureCode.UNSUPPORTED)
                    return
                }
                // Release any live specifier request before driving Wi-Fi P2P directly.
                specifierJoiner?.teardown()
                val joiner = P2pFallbackJoiner(appContext).also {
                    fallbackJoiner?.teardown()
                    fallbackJoiner = it
                }
                joiner.join(mac, tier.budgetMs, object : P2pFallbackJoiner.Listener {
                    override fun onJoined(groupOwnerAddress: InetAddress, localAddress: InetAddress?) {
                        finish(P2pRoute(groupOwnerAddress, localAddress), FailureCode.NONE)
                    }

                    override fun onFailed() {
                        finish(null, FailureCode.CONNECT_TIMEOUT)
                    }
                })
            }

            TransportDescriptor.BT_L2CAP, TransportDescriptor.AWARE,
            TransportDescriptor.RECEIVER_AS_GO, TransportDescriptor.SENDER_JOINS_AP -> {
                // BT/Aware are handled out-of-band; RECEIVER_AS_GO and SENDER_JOINS_AP are driven by
                // the controller (a device hosts the AP itself), never via the local ladder's joiners.
                finish(null, FailureCode.UNSUPPORTED)
            }
        }
    }

    /** Stops the in-flight tier and the auto-advance. Joiners stay up for teardown(). */
    fun cancel() {
        cancelled = true
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Full release: the winning route's underlying join (specifier request or
     * P2P group) lives in the joiners, so call this only when the session is
     * truly over — the fragment's onDestroyView.
     */
    fun teardown() {
        cancel()
        credentialJoiner?.teardown()
        credentialJoiner = null
        specifierJoiner?.teardown()
        specifierJoiner = null
        fallbackJoiner?.teardown()
        fallbackJoiner = null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
