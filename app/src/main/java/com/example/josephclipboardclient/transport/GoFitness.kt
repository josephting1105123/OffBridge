package com.EntropicSoftwareLab.ClipboardShare.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.EntropicSoftwareLab.ClipboardShare.data.db.OffGridRepository
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Quick estimate (0..15) of how good THIS device is as a Wi-Fi Direct group owner (GO),
 * exchanged over the control channel to elect the GO. Instant (no probe): a static capability
 * base plus a LEARNED self-GO-reliability term that dominates once data exists — so a device that
 * repeatedly fails as GO lowers its own score and yields the role to the more-capable peer. The
 * 0..15 range also lines up with the Wi-Fi Direct goIntent scale.
 */
object GoFitness {

    /** Compute ONCE per session and cache it — the HELLO field and the election must agree. */
    fun score(context: Context): Int {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return 0
        if (!wifi.isWifiEnabled) return 0  // can't host a group with Wi-Fi off

        var staticBase = 3
        try {
            // STA+AP concurrency (API 30+) is the strongest "good GO" signal.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifi.isStaApConcurrencySupported) staticBase += 2
            if (wifi.is5GHzBandSupported) staticBase += 1
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) staticBase += 1  // newer Wi-Fi P2P stack
        // staticBase ∈ 3..7

        val (s, f) = OffGridRepository.selfGoCounts(app)
        val n = s + f
        val learned = if (n == 0) 0 else {
            val ratio = s.toDouble() / n
            val weight = min(n, 6) / 6.0                  // confidence ramps over the first ~6 outcomes
            ((ratio - 0.5) * 14.0 * weight).roundToInt()  // ∈ ~[-7, +7]
        }
        return (staticBase + learned).coerceIn(0, 15)
    }

    /**
     * Conservative election: the receiver hosts ONLY if it scores strictly higher than the sender
     * (ties → sender hosts, today's behavior; a legacy peer's -1 never wins). Both devices evaluate
     * this on the same two numbers, so they reach the same verdict without extra negotiation.
     */
    fun electReceiverAsGo(senderGof: Int, receiverGof: Int): Boolean = receiverGof > senderGof

    /**
     * "Whoever is free hosts" — but SAFELY. Returns true only when the receiver is the single free
     * phone and the sender is stuck on a Wi-Fi network, the one case where leading with the receiver
     * hosting (RECEIVER_AS_GO) clearly beats the busy sender's group. In every other case the sender
     * leads with P2P_CRED_JOIN (reuse the already-live group — leading with RECEIVER_AS_GO would tear
     * that group down and then skip the join as UNSUPPORTED: the documented self-defeating plan). The
     * GoFitness tiebreak therefore does NOT reorder ahead of P2P_CRED_JOIN; it only gates whether
     * RECEIVER_AS_GO is kept as the fallback (see buildPlan).
     */
    fun shouldReceiverLeadAsHost(senderFree: Boolean, receiverFree: Boolean): Boolean =
        receiverFree && !senderFree

    /**
     * Does the receiver lead as the Wi-Fi Direct host (GO)? The rule is "the BUSY device hosts, the
     * FREE device joins" — because a single-radio phone can only act as a P2P GO on its own STA channel
     * (Single-Channel Concurrency), so the device whose STA is occupied must be the GO; the free device
     * (no STA pinning it to another channel) can follow as the client. The receiver therefore leads with
     * RECEIVER_AS_GO exactly when the receiver is the busy one and the sender is free.
     *
     * (Earlier this branch was an unconditional "sender hosts", which made a free sender create the group
     * and a Wi-Fi-connected receiver try to join it across channels — the MCC wall that times out on MIUI.)
     *
     * Params: [senderHasHotspot]/[receiverHasHotspot] decide the host by ownership in the hotspot
     * branches. [senderFree]/[receiverFree] drive the both-hotspot tiebreak AND the no-hotspot Wi-Fi
     * Direct case. Never leads with a receiver that can't host. Mirrors [SenderSessionController.buildPlan].
     */
    fun electHost(
        senderHasHotspot: Boolean,
        receiverHasHotspot: Boolean,
        senderFree: Boolean,
        receiverFree: Boolean,
        receiverCanHost: Boolean
    ): Boolean {
        if (!receiverCanHost) return false
        return when {
            senderHasHotspot && receiverHasHotspot -> shouldReceiverLeadAsHost(senderFree, receiverFree)
            senderHasHotspot && !receiverHasHotspot -> true   // sender busy with its hotspot → free receiver hosts
            // No sender hotspot (the plain Wi-Fi Direct case): the busy receiver hosts on its own STA
            // channel and the free sender joins. Sender busy / both free / both busy → sender hosts.
            else -> senderFree && !receiverFree
        }
    }
}
