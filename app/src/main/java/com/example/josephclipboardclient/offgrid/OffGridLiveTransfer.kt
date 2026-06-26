package com.EntropicSoftwareLab.ClipboardShare.offgrid

import android.os.Handler
import android.os.Looper

/**
 * Process-lifetime owner of a BACKGROUNDED off-grid transfer's transport.
 *
 * The moment a transfer starts, the off-grid screen leaves (the Activity finishes) and both sides
 * land on the transfer-history details page. The file bytes already stream on [TransferDaemon]'s
 * app-scoped daemon, but the Wi-Fi Direct group / joiner they ride on is owned by the off-grid
 * fragment and would be torn down with it in `onDestroyView`. So the fragment "hands off" a teardown
 * closure here (capturing the group/controller — keeping them reachable, NOT the fragment) and this
 * singleton holds it until the transfer reaches a terminal state, then runs it once and disarms.
 * The foreground [P2pSessionService] keeps the process alive in the meantime.
 *
 * Off-grid is strictly one-transfer-at-a-time (guarded by `p2pContext.busy` + a single host activity),
 * so a single slot is enough; a fresh [arm] defensively releases any stale prior transport first.
 */
object OffGridLiveTransfer {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var sessionId: String? = null
    @Volatile private var teardown: (() -> Unit)? = null

    /** True while a backgrounded transfer's transport is held here. */
    val isArmed: Boolean get() = teardown != null

    /**
     * Hand off a backgrounded transfer's [teardown] (run on the main thread on terminal). [sessionId]
     * is the session whose terminal state releases it. Supersedes any prior arm — the old transport
     * is torn down first so a leftover never leaks the radio.
     */
    @Synchronized
    fun arm(sessionId: String, teardown: () -> Unit) {
        this.teardown?.let { old -> main.post { old() } }
        this.sessionId = sessionId
        this.teardown = teardown
    }

    /** Run the held teardown once (main thread) and disarm. Idempotent; no-op when not armed. */
    @Synchronized
    fun finish() {
        val t = teardown ?: return
        teardown = null
        sessionId = null
        main.post { t() }
    }

    /**
     * Terminal signal from the transfer engine: release the transport only if it was handed off for
     * [sessionId]. A no-op when the session is still on-screen (never armed) or a different one.
     */
    @Synchronized
    fun finishIfArmed(sessionId: String) {
        if (this.sessionId != sessionId) return
        val t = teardown ?: return
        teardown = null
        this.sessionId = null
        main.post { t() }
    }
}
