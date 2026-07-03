package com.joseph.clipboard.windows.network

import com.joseph.clipboard.windows.network.control.SessionCrypto
import com.joseph.clipboard.windows.storage.EcdhSession
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
     * caller's thread — not dispatched). Calls [onPaired] (ip, aesKey, deviceName) exactly once on
     * successful key agreement so the caller can persist the key and device name.
     *
     * Blocking — call from an IO dispatcher.
     */
    fun pair(
        ip: String,
        deviceName: String,
        myDeviceName: String,
        onEvent: (Event) -> Unit,
        onPaired: (ip: String, aesKey: ByteArray, peerName: String) -> Unit,
    ) {
        var socket: Socket? = null
        try {
            onEvent(Event.Connecting(ip, deviceName))
            socket = Socket()
            socket.soTimeout = 15000
            socket.connect(InetSocketAddress(ip, Ports.PAIRING), 3000)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            val ecdh = EcdhSession()
            val myPubKey = ecdh.getPublicKey()

            // "pv2" advertises PIN-v2 support. Older peers omit it and ignore our extra field, so
            // both sides fall back to v1 (see receiver). The AES key is identical either way.
            writer.println("PAIR_REQ|$myDeviceName|${Base64.getEncoder().encodeToString(myPubKey)}|pv2")

            onEvent(Event.WaitingForResponse(ip, deviceName))
            val ackResponse = reader.readLine()
            if (ackResponse != null && ackResponse.startsWith("PAIR_ACK|")) {
                val ackParts = ackResponse.split("|")
                val peerPubKey = Base64.getDecoder().decode(ackParts[1])
                // v2 PIN only when the ACK also advertises pv2 (both peers 3.5.0+). An ACK
                // without pv2 (old peer) => v1. Extra fields after index 1 are tolerated.
                val useV2 = ackParts.drop(2).contains("pv2")
                val (aesKey, visualPin) = ecdh.deriveSecrets(peerPubKey, useV2)

                onEvent(Event.WaitingForAccept(ip, deviceName, visualPin))
                // The acceptor waits up to 2 min for the human to tap Accept. Keeping the initial
                // 15s read timeout here would abandon the socket while the acceptor still saves the
                // key — creating one-sided trust. Match the acceptor's window (Android sets 120000
                // before its final PAIR_ACCEPT read the same way).
                socket.soTimeout = 120000
                val finalResponse = reader.readLine()

                if (finalResponse == "PAIR_ACCEPT") {
                    onPaired(ip, aesKey, deviceName)
                }
            }

            onEvent(Event.Done)
        } catch (e: Exception) {
            System.err.println("Pairing failed with $ip")
            e.printStackTrace()
            onEvent(Event.Done)
        } finally {
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
                    val challenge = reader.readLine()
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
