package com.EntropicSoftwareLab.ClipboardShare.pairing

import com.EntropicSoftwareLab.ClipboardShare.EcdhSession
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * The single pairing code this device is currently showing.
 *
 * A QR window is one-shot: [claim] hands the session to the first scanner that presents a matching,
 * unexpired token and leaves nothing behind, so a code photographed off someone's screen and
 * replayed a second later finds an empty slot. That atomic swap — not the socket, and not the UI —
 * is what bounds how many devices one displayed code can pair.
 *
 * Byte-identical counterpart: Windows `network/QrPairHost.kt`.
 */
object QrPairHost {

    /** An open QR window: the ephemeral key half the scanner will complete, and its admission token. */
    class Session(
        val ecdh: EcdhSession,
        val token: ByteArray,
        val expiresAtMs: Long,
        /** The address published in the code, so trust lands on the address the peer actually dialed. */
        val advertisedIp: String,
        /** The host's LAN address when the code also advertised one, else null. */
        val lanIp: String?,
    ) {
        fun tokenHex(): String = token.joinToString("") { "%02x".format(it) }
    }

    private val pending = AtomicReference<Session?>(null)

    /**
     * Opens a new window, retiring any previous one. Returns the session so the caller can render
     * its payload; the caller owns building the QR (only it knows the addresses and AP creds).
     */
    fun open(
        ecdh: EcdhSession,
        token: ByteArray,
        nowMs: Long,
        advertisedIp: String,
        lanIp: String?,
        validityMs: Long = PairQrPayload.VALIDITY_MS,
    ): Session {
        val session = Session(ecdh, token, nowMs + validityMs, advertisedIp, lanIp)
        pending.set(session)
        return session
    }

    /**
     * Atomically claims the open window for [token]. Returns null when nothing is pending, the
     * token doesn't match, or the window has expired — every one of which the caller answers with
     * the same denial, so a prober learns nothing about which it was.
     *
     * The compare is constant-time: a leaked token prefix would let an attacker on the LAN grind
     * the rest of it while the code is still on screen.
     */
    fun claim(token: ByteArray, nowMs: Long): Session? {
        val current = pending.get() ?: return null
        if (!MessageDigest.isEqual(current.token, token)) return null
        if (nowMs >= current.expiresAtMs) {
            // Expired: drop it so the slot doesn't hold a dead session until the UI closes.
            pending.compareAndSet(current, null)
            return null
        }
        // Only the caller that wins this swap gets the session; a racing second scanner sees null.
        return if (pending.compareAndSet(current, null)) current else null
    }

    /** Puts a claimed session back when the handshake failed before either side stored trust. */
    fun restore(session: Session) {
        pending.compareAndSet(null, session)
    }

    /** Closes the window (the QR screen went away). Any later dial-in is denied. */
    fun close() {
        pending.set(null)
    }

    /** True while a code is on screen and still valid — drives the "code expired" UI state. */
    fun isOpen(nowMs: Long): Boolean = pending.get()?.let { nowMs < it.expiresAtMs } ?: false
}
