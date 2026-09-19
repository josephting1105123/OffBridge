package com.joseph.clipboard.windows.network.control

import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Control-plane wire protocol — port of the Android `transport.control.ControlProtocol`
 * (the subset a PC QR-session sender needs; frame layout pinned by ControlPlaneVectorTest).
 *
 * | Type | Name             | Direction | Payload                                              |
 * | 0x01 | HELLO_RX         | rx→tx     | token HMAC, receiver pubkey, caps, P2P MAC, identity |
 * | 0x02 | HELLO_ACK        | tx→rx     | key-confirmation HMAC, sender caps, ladder plan      |
 * | 0x03 | TRY_TRANSPORT    | tx→rx     | transport id + params (ssid/psk/nk)                  |
 * | 0x04 | TRANSPORT_RESULT | rx→tx     | transport id, outcome, failure code                  |
 * | 0x05 | DATA_READY       | tx→rx     | exact ip:port the TCP server is reachable on         |
 * | 0x06 | ABORT            | both      | reason code                                          |
 * | 0x07 | PING / 0x08 PONG | both      | liveness, 5 s interval, 3 misses = channel dead      |
 * | 0x09 | REQ_TRANSPORT    | rx→tx     | receiver-requested tier (ignored on the PC)          |
 * | 0x0A | HOST_READY       | rx→tx     | receiver rendezvous creds (never ordered by the PC)  |
 *
 * HELLO frames are plaintext (they bootstrap the key); all others are sealed
 * by [SessionCrypto]. JSON bodies, UTF-8.
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
    const val HOST_READY = 0x0A

    const val HEADER_LEN = 7

    /** Fixed GATT characteristic UUIDs (the SERVICE UUID is per-session, token-derived). */
    val CHAR_RX_UUID: UUID = UUID.fromString("8f6d6b22-4e3c-4a76-9339-1c0e6f44b001")
    val CHAR_TX_UUID: UUID = UUID.fromString("8f6d6b22-4e3c-4a76-9339-1c0e6f44b002")

    /**
     * Session service UUID = HMAC-SHA256(token, "ble-uuid")[0..15] with the
     * RFC 4122 v4 version/variant bits forced. One-way: seeing the UUID
     * reveals nothing about the token; possessing it grants nothing (admission
     * still requires the HMAC handshake). Matches the phone's hardware scan filter.
     */
    fun sessionServiceUuid(token: ByteArray): UUID {
        val h = SessionCrypto.hmacSha256(token, "ble-uuid".toByteArray(Charsets.UTF_8))
        val bytes = h.copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte() // version 4
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // IETF variant
        val bb = ByteBuffer.wrap(bytes)
        return UUID(bb.long, bb.long)
    }

    /** Plaintext frame (HELLO_RX / HELLO_ACK): same header, seq = 0, clear body. */
    fun plainFrame(type: Int, payload: JsonObject): ByteArray {
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
