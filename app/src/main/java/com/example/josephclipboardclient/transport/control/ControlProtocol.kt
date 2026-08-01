package com.EntropicSoftwareLab.ClipboardShare.transport.control

import android.os.Handler
import android.os.Looper
import com.EntropicSoftwareLab.ClipboardShare.transport.SessionCrypto
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Control-plane wire protocol (§4 of the V4 plan).
 *
 * | Type | Name             | Direction | Payload                                              |
 * | 0x01 | HELLO_RX         | rx→tx     | token HMAC, receiver pubkey, caps, P2P MAC, identity |
 * | 0x02 | HELLO_ACK        | tx→rx     | key-confirmation HMAC, sender caps, ladder plan      |
 * | 0x03 | TRY_TRANSPORT    | tx→rx     | transport id + params (ssid/psk/ip/port/mac/psm)     |
 * | 0x04 | TRANSPORT_RESULT | rx→tx     | transport id, outcome, failure code                  |
 * | 0x05 | DATA_READY       | tx→rx     | exact ip:port the TCP server is reachable on         |
 * | 0x06 | ABORT            | both      | reason code                                          |
 * | 0x07 | PING / 0x08 PONG | both      | liveness, 5 s interval, 3 misses = channel dead      |
 * | 0x09 | REQ_TRANSPORT    | rx→tx     | receiver-requested tier (legacy; BT escape retired)  |
 * | 0x0A | HOST_READY       | rx→tx     | receiver's rendezvous creds {ssid,psk,nk,...}        |
 *
 * HELLO frames are plaintext (they bootstrap the key); all others are sealed
 * by [SessionCrypto]. JSON bodies, UTF-8.
 *
 * Capability fields carried in HELLO_RX (rx→tx) and HELLO_ACK (tx→rx), all optional/additive so a
 * legacy peer that omits them degrades to [com.EntropicSoftwareLab.ClipboardShare.transport.DeviceCaps.legacyDefault]:
 *   cja (Boolean) canJoinAp · cha (Boolean) canHostAp · hsa (Boolean) hotspotActive
 *   sac (Boolean) staApConcurrency · hcr (Boolean) hasCreds · nk (Int) NetKind of the offered AP
 *   sta (Boolean) staConnected — my Wi-Fi STA is already on a real network (NOT free to host); drives
 *       the "whoever is free hosts" GO election in [GoFitness.shouldReceiverLeadAsHost].
 * HELLO_ACK additionally carries the sender's `cap` bitmap, `gof`, and ladder `plan` (as before).
 */
object ControlProtocol {

    const val HELLO_RX = 0x01
    const val HELLO_ACK = 0x02
    const val TRY_TRANSPORT = 0x03
    const val TRANSPORT_RESULT = 0x04
    const val DATA_READY = 0x05
    const val ABORT = 0x06
    const val PING = 0x07
    const val PONG = 0x08
    const val REQ_TRANSPORT = 0x09

    /** RECEIVER_AS_GO: receiver→sender, the receiver's freshly-created group creds (sealed). */
    const val HOST_READY = 0x0A

    const val HEADER_LEN = 7

    /** Fixed GATT characteristic UUIDs (the SERVICE UUID is per-session, token-derived). */
    val CHAR_RX_UUID: UUID = UUID.fromString("8f6d6b22-4e3c-4a76-9339-1c0e6f44b001")
    val CHAR_TX_UUID: UUID = UUID.fromString("8f6d6b22-4e3c-4a76-9339-1c0e6f44b002")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Session service UUID = HMAC-SHA256(token, "ble-uuid")[0..15] with the
     * RFC 4122 v4 version/variant bits forced. One-way: seeing the UUID
     * reveals nothing about the token; possessing it grants nothing (admission
     * still requires the HMAC handshake). O(1) hardware scan filtering.
     */
    fun sessionServiceUuid(token: ByteArray): UUID {
        val h = SessionCrypto.hmacSha256(token, "ble-uuid".toByteArray(Charsets.UTF_8))
        val bytes = h.copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte() // version 4
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // IETF variant
        val bb = ByteBuffer.wrap(bytes)
        return UUID(bb.long, bb.long)
    }

    /** Token-derived RFCOMM service-record UUID (legacy data-tier dial; kept for compatibility). */
    fun rfcommServiceUuid(token: ByteArray): UUID = derivedUuid(token, "rfcomm-uuid")

    /**
     * Token-derived RFCOMM service-record UUID for the Bluetooth Classic CONTROL channel — the
     * fallback when BLE GATT fails (e.g. the HONOR ROD2-W09 HELLO_ACK timeout). Distinct label so it
     * never collides with the legacy data UUID above.
     */
    fun rfcommControlUuid(token: ByteArray): UUID = derivedUuid(token, "rfcomm-ctl-uuid")

    private fun derivedUuid(token: ByteArray, label: String): UUID {
        val h = SessionCrypto.hmacSha256(token, label.toByteArray(Charsets.UTF_8))
        val bytes = h.copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val bb = ByteBuffer.wrap(bytes)
        return UUID(bb.long, bb.long)
    }

    /** Plaintext frame (HELLO_RX / HELLO_ACK): same header, seq = 0, clear body. */
    fun plainFrame(type: Int, payload: JSONObject): ByteArray {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        return SessionCrypto.header(type, 0L, body.size) + body
    }

    data class Frame(val type: Int, val seq: Long, val body: ByteArray)

    /** Parses one complete frame; null when malformed or length-inconsistent. */
    fun parse(raw: ByteArray): Frame? {
        if (raw.size < HEADER_LEN) return null
        val bb = ByteBuffer.wrap(raw)
        val type = bb.get().toInt() and 0xFF
        val seq = bb.int.toLong() and 0xFFFFFFFFL
        val len = bb.short.toInt() and 0xFFFF
        if (raw.size != HEADER_LEN + len) return null
        return Frame(type, seq, raw.copyOfRange(HEADER_LEN, raw.size))
    }
}

/**
 * Authenticated, encrypted, live session over a [ControlChannel]. Created
 * only after the HELLO exchange validated (the controllers do that with
 * plaintext frames); from then on every frame is sealed, sequenced and
 * liveness-checked.
 */
class ControlSession(
    private val channel: ControlChannel,
    private val crypto: SessionCrypto
) {
    /** Decrypted application frames (never PING/PONG). Main thread. */
    var onFrame: ((type: Int, payload: JSONObject) -> Unit)? = null

    /** Liveness lost (3 missed pongs) or the underlying link closed. Fires once. */
    var onDead: (() -> Unit)? = null

    /**
     * A plaintext HELLO_RX arrived after the session formed — i.e. our HELLO_ACK never reached the
     * peer and it is retrying. Without this the frame would fail [SessionCrypto.open] and vanish,
     * leaving the peer to time out against a session we think is fine. The sender re-sends its ACK.
     */
    var onPlaintextHello: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var missedPongs = 0
    private var dead = false

    /**
     * True once ANY sealed frame from the peer has opened — PING/PONG included, so a linked but
     * otherwise silent peer still counts within one ping interval. It is the proof that the peer
     * holds a session of its own, which is what separates "our HELLO_ACK was lost" from "the peer is
     * linked and something else re-sent a HELLO".
     */
    @Volatile var peerHeard = false
        private set

    private val pingTask = object : Runnable {
        override fun run() {
            if (dead) return
            if (missedPongs >= MAX_MISSED_PONGS) {
                die()
                return
            }
            missedPongs++
            send(ControlProtocol.PING, JSONObject())
            mainHandler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    fun start() {
        channel.onMessage = { raw -> mainHandler.post { handleRaw(raw) } }
        channel.onClosed = { mainHandler.post { die() } }
        mainHandler.postDelayed(pingTask, PING_INTERVAL_MS)
    }

    @Synchronized
    fun send(type: Int, payload: JSONObject) {
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
        // here means the peer is retransmitting its HELLO because our ACK was lost.
        if (frame.type == ControlProtocol.HELLO_RX) {
            onPlaintextHello?.invoke()
            return
        }
        val clear = crypto.open(frame.type, frame.seq, frame.body) ?: return
        peerHeard = true
        val payload = try { JSONObject(String(clear, Charsets.UTF_8)) } catch (_: Exception) { return }
        when (frame.type) {
            ControlProtocol.PING -> send(ControlProtocol.PONG, JSONObject())
            ControlProtocol.PONG -> missedPongs = 0
            else -> onFrame?.invoke(frame.type, payload)
        }
    }

    private fun die() {
        if (dead) return
        dead = true
        mainHandler.removeCallbacks(pingTask)
        val cb = onDead
        onDead = null
        cb?.invoke()
    }

    fun close() {
        dead = true
        mainHandler.removeCallbacks(pingTask)
        channel.close()
    }

    private companion object {
        const val PING_INTERVAL_MS = 5_000L
        const val MAX_MISSED_PONGS = 3
    }
}
