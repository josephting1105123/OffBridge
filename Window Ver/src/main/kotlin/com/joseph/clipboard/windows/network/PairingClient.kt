package com.joseph.clipboard.windows.network

import com.joseph.clipboard.windows.network.control.SessionCrypto
import com.joseph.clipboard.windows.storage.EcdhSession
import com.joseph.clipboard.windows.storage.SecurityVault
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/**
 * Outbound pairing handshake client — initiates PAIR_REQ / PAIR_ACK / PAIR_ACCEPT and
 * authenticated UNPAIR_REQ / UNPAIR_CHALLENGE / UNPAIR_PROOF. Symmetric counterpart to the
 * inbound [PairingDaemon]. Extracted verbatim from ClipboardAppController.
 */
object PairingClient {

    // The outbound handshake blocks on a socket read for up to 2 minutes while the peer's human
    // decides. The requester's Cancel closes this socket to unblock that read, and the flag makes the
    // unblocked handshake refuse to persist trust — otherwise a "cancelled" pair could still complete
    // in the background if the peer accepted a moment later, leaving one-sided trust.
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var cancelled = false
    @Volatile private var activeGate: PairConfirmGate? = null

    /** Aborts an in-progress [pair] from the requester UI. Closing the socket also tells a pc1 peer
     *  not to save. Safe to call when no pairing is in flight. */
    fun cancel() {
        cancelled = true
        activeGate?.cancel()
        try { activeSocket?.close() } catch (_: Exception) {}
    }

    /** The requester's user tapped "Codes match" for the PIN shown with [Event.WaitingForAccept]. */
    fun confirm() {
        activeGate?.confirm()
    }

    /** Outcome delivered to the caller; [visualPin] is non-null when waiting for user acceptance. */
    sealed class Event {
        data class Connecting(val ip: String, val deviceName: String) : Event()
        data class WaitingForResponse(val ip: String, val deviceName: String) : Event()
        data class WaitingForAccept(val ip: String, val deviceName: String, val visualPin: String) : Event()
        /** Pairing succeeded — key and device name already persisted by [onPaired]. */
        object Accepted : Event()
        /** Connection/protocol error. */
        object Failed : Event()
        /** Handshake finished (accepted or rejected) — cleans up in-progress UI. */
        object Done : Event()
    }

    /**
     * Runs the outbound pairing handshake to [ip]. Calls [onEvent] on each state transition (on the
     * caller's thread — not dispatched). Calls [onPaired] (ip, aesKey, deviceName, peerDeviceId)
     * exactly once on successful key agreement so the caller can persist the key, name and id.
     * `peerDeviceId` is null when the peer predates device ids.
     *
     * Blocking — call from an IO dispatcher.
     */
    fun pair(
        ip: String,
        deviceName: String,
        myDeviceName: String,
        onEvent: (Event) -> Unit,
        onPaired: (ip: String, aesKey: ByteArray, peerName: String, peerDeviceId: String?) -> Unit,
    ) {
        var socket: Socket? = null
        cancelled = false
        val gate = PairConfirmGate()
        activeGate = gate
        try {
            onEvent(Event.Connecting(ip, deviceName))
            socket = Socket()
            activeSocket = socket
            socket.soTimeout = 15000
            socket.connect(InetSocketAddress(ip, Ports.PAIRING), 3000)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            val ecdh = EcdhSession()
            val myPubKey = ecdh.getPublicKey()

            // "pv2" advertises PIN-v2 support. Older peers omit it and ignore our extra field, so
            // both sides fall back to v1 (see receiver). The AES key is identical either way.
            // "did:" carries this install's stable id so the peer can retire our old record if we
            // last paired from a different address.
            writer.println(
                WireFrames.pairReq(
                    myDeviceName,
                    Base64.getEncoder().encodeToString(myPubKey),
                    SecurityVault.localDeviceId(),
                )
            )

            onEvent(Event.WaitingForResponse(ip, deviceName))
            val ackResponse = reader.readLineCapped()
            if (ackResponse != null && ackResponse.startsWith("PAIR_ACK|")) {
                val ackParts = ackResponse.split("|")
                val peerPubKey = Base64.getDecoder().decode(ackParts[1])
                // v2 PIN only when the ACK also advertises pv2 (both peers 3.5.0+). An ACK
                // without pv2 (old peer) => v1. Extra fields after index 1 are tolerated.
                val ackTail = ackParts.drop(2)
                val useV2 = ackTail.contains("pv2")
                // Same tail carries the peer's device id (absent on older peers).
                val peerDeviceId = WireFrames.parseDeviceId(ackTail)
                // The peer holds trust until our PAIR_CONFIRM only if it advertised pc1; an older
                // peer has already saved by the time it sends PAIR_ACCEPT.
                val peerAwaitsConfirm = ackTail.contains(WireFrames.CONFIRM_TOKEN)
                val (aesKey, visualPin) = ecdh.deriveSecrets(peerPubKey, useV2)

                val pinShownAt = System.currentTimeMillis()
                onEvent(Event.WaitingForAccept(ip, deviceName, visualPin))
                // The acceptor waits up to 2 min for the human to tap Accept. Keeping the initial
                // 15s read timeout here would abandon the socket while the acceptor still saves the
                // key — creating one-sided trust. Match the acceptor's window (Android sets 120000
                // before its final PAIR_ACCEPT read the same way).
                socket.soTimeout = PairingDaemon.PAIR_CONFIRM_WINDOW_MS.toInt()
                val finalResponse = reader.readLineCapped()

                // PAIR_ACCEPT alone proves nothing: a rogue host that answered our click sends it
                // straight after its ACK. Trust needs our own user's PIN comparison too, in either
                // order. The wait shares the window that started when the PIN appeared, so we give
                // up no later than the peer stops waiting for PAIR_CONFIRM.
                if (finalResponse == "PAIR_ACCEPT" && !cancelled) {
                    val decision = gate.await(PairingDaemon.PAIR_CONFIRM_WINDOW_MS - (System.currentTimeMillis() - pinShownAt))
                    if (decision == true && !cancelled) {
                        var confirmSent = true
                        if (peerAwaitsConfirm) {
                            writer.println(WireFrames.PAIR_CONFIRM)
                            // checkError() flushes and reports a failed write: if the peer never got
                            // the confirm it won't save, so neither do we.
                            confirmSent = !writer.checkError()
                        }
                        if (confirmSent) onPaired(ip, aesKey, deviceName, peerDeviceId)
                    }
                }
            }

            onEvent(Event.Done)
        } catch (e: Exception) {
            // A cancel closes the socket mid-read, surfacing here as a SocketException — that's the
            // user's cancel, not a real failure, so don't log it as one.
            if (!cancelled) {
                System.err.println("Pairing failed with $ip")
                e.printStackTrace()
            }
            onEvent(Event.Done)
        } finally {
            activeSocket = null
            if (activeGate === gate) activeGate = null
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Sends an authenticated unpair request to [ip] using [key] to answer the challenge.
     * Best-effort — errors are silently swallowed (the local trust has already been removed).
     *
     * Blocking — call from an IO dispatcher.
     */
    fun unpair(ip: String, key: ByteArray?) {
        try {
            Socket().use { socket ->
                socket.soTimeout = 3000
                socket.connect(InetSocketAddress(ip, Ports.PAIRING), 2000)
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.println(WireFrames.unpairReq())
                if (key != null) {
                    val challenge = reader.readLineCapped()
                    if (challenge != null && challenge.startsWith("UNPAIR_CHALLENGE|")) {
                        val nonce = Base64.getDecoder().decode(challenge.removePrefix("UNPAIR_CHALLENGE|"))
                        val proof = SessionCrypto.hmacSha256(key, nonce)
                        writer.println(WireFrames.unpairProof(Base64.getEncoder().encodeToString(proof)))
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
