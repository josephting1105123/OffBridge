package com.joseph.clipboard.windows.network.control

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Raw, reliable, message-oriented control link — port of the Android
 * `transport.control.ControlChannel`. [BleControlPeripheral] implements this
 * after GATT chunking/reassembly; the [ControlSession] layered on top adds
 * crypto, sequencing and liveness.
 *
 * Implementations deliver [onMessage]/[onClosed] on the owner's executor and
 * accept [send] from any thread.
 */
interface ControlChannel {
    /** Queues one complete frame for delivery. Silently drops after close. */
    fun send(frame: ByteArray)

    /** One complete reassembled frame from the peer. */
    var onMessage: ((ByteArray) -> Unit)?

    /** The underlying link died (disconnect, advertise failure, teardown). Fires once. */
    var onClosed: (() -> Unit)?

    fun close()
}

/**
 * A [ControlChannel] this device can *advertise* — the host half of the off-grid QR ladder. Windows
 * implements it with a WinRT BLE GATT server; a platform with no GATT-peripheral support simply has
 * no implementation, and [com.joseph.clipboard.desktop.platform.OffGridHost.createControlPeripheral]
 * returns null so the QR session falls back to its QR-only path.
 */
interface ControlPeripheral : ControlChannel {
    /** Begin advertising [serviceUuid]. False when the adapter cannot advertise. */
    fun start(serviceUuid: java.util.UUID): Boolean

    /** A central subscribed to the TX characteristic, with the negotiated MTU. */
    var onSubscribed: ((mtu: Int) -> Unit)?
}

/**
 * Authenticated, encrypted, live session over a [ControlChannel] — port of the
 * Android `transport.control.ControlSession` with the main-thread Handler
 * replaced by the owner's single-threaded [executor] (all callbacks and
 * liveness ticks run there, so controller state needs no extra locking).
 * Created only after the HELLO exchange validated; from then on every frame
 * is sealed, sequenced and liveness-checked. PING every 5 s, dead after
 * 3 missed pongs — the same schedule the phone runs, so both ends agree on
 * when the channel is lost.
 */
class ControlSession(
    private val channel: ControlChannel,
    private val crypto: SessionCrypto,
    private val executor: ScheduledExecutorService
) {
    /** Decrypted application frames (never PING/PONG). Runs on [executor]. */
    var onFrame: ((type: Int, payload: JsonObject) -> Unit)? = null

    /** Liveness lost (3 missed pongs) or the underlying link closed. Fires once, on [executor]. */
    var onDead: (() -> Unit)? = null

    /**
     * A plaintext HELLO_RX arrived after the session formed — our HELLO_ACK never reached the phone
     * and it is retrying. Without this the frame fails [SessionCrypto.open] and vanishes, leaving
     * the phone to time out against a session this side believes is healthy. Mirrors the phone's
     * `ControlSession.onPlaintextHello`.
     */
    var onPlaintextHello: (() -> Unit)? = null

    private var missedPongs = 0
    private var dead = false
    private var pingFuture: ScheduledFuture<*>? = null

    /**
     * True once ANY sealed frame from the phone has opened — PING/PONG included, so a linked but
     * otherwise silent phone still counts within one ping interval. Proof that the phone holds a
     * session of its own, which separates "our HELLO_ACK was lost" from "the phone is linked and
     * something else re-sent a HELLO". Mirrors the phone's `ControlSession.peerHeard`.
     */
    @Volatile
    var peerHeard = false
        private set

    fun start() {
        channel.onMessage = { raw -> executor.execute { handleRaw(raw) } }
        channel.onClosed = { executor.execute { die() } }
        pingFuture = executor.scheduleWithFixedDelay(
            { pingTick() }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    private fun pingTick() {
        if (dead) return
        if (missedPongs >= MAX_MISSED_PONGS) {
            die()
            return
        }
        missedPongs++
        send(ControlProtocol.PING, JsonObject())
    }

    @Synchronized
    fun send(type: Int, payload: JsonObject) {
        if (dead) return
        try {
            channel.send(crypto.seal(type, payload.toString().toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
        }
    }

    private fun handleRaw(raw: ByteArray) {
        if (dead) return
        val frame = ControlProtocol.parse(raw) ?: return
        // HELLO_RX is plaintext by definition, so it can never open as a sealed frame. Reaching
        // here means the phone is retransmitting its HELLO because our ACK was lost.
        if (frame.type == ControlProtocol.HELLO_RX) {
            onPlaintextHello?.invoke()
            return
        }
        val clear = crypto.open(frame.type, frame.seq, frame.body) ?: return
        peerHeard = true
        val payload = try {
            JsonParser.parseString(String(clear, Charsets.UTF_8)).asJsonObject
        } catch (_: Exception) {
            return
        }
        when (frame.type) {
            ControlProtocol.PING -> send(ControlProtocol.PONG, JsonObject())
            ControlProtocol.PONG -> missedPongs = 0
            else -> onFrame?.invoke(frame.type, payload)
        }
    }

    private fun die() {
        if (dead) return
        dead = true
        pingFuture?.cancel(false)
        val cb = onDead
        onDead = null
        cb?.invoke()
    }

    fun close() {
        dead = true
        pingFuture?.cancel(false)
        channel.close()
    }

    private companion object {
        const val PING_INTERVAL_MS = 5_000L
        const val MAX_MISSED_PONGS = 3
    }
}
