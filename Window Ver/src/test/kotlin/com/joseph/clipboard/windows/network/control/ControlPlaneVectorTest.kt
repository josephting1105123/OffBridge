package com.joseph.clipboard.windows.network.control

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cross-platform golden vectors for the BLE control plane. The SAME constants live in the Android
 * module's ControlPlaneVectorTest (app/src/test/.../transport/control/) — this module reimplements
 * the wire contract to act as a PC QR-session sender, so if either side drifts (UUID derivation,
 * HKDF info, direction salts, nonce layout, frame header, chunk header) a vector stops matching and
 * the PC↔phone interop break is caught offline. Keep the companion constants identical in both files.
 */
class ControlPlaneVectorTest {

    private val token = ByteArray(16) { it.toByte() }                 // 00..0f
    private val ikm = ByteArray(32) { it.toByte() }                   // 00..1f
    private val k0 = ByteArray(32) { (0x10 + it).toByte() }           // 10..2f
    private val token0 = ByteArray(16) { (0xA0 + it).toByte() }       // a0..af

    @Test
    fun `session service UUID derivation matches the canonical vector`() {
        assertEquals(UUID_VECTOR, ControlProtocol.sessionServiceUuid(token).toString())
    }

    @Test
    fun `HKDF-SHA256 matches the canonical vector`() {
        assertEquals(
            HKDF_VECTOR_HEX,
            SessionCrypto.hkdfSha256(ikm, "ctrl-v1".toByteArray(Charsets.UTF_8), 32).toHex()
        )
    }

    @Test
    fun `HELLO hm and ack HMACs match the canonical vectors`() {
        assertEquals(HM_VECTOR_HEX, SessionCrypto.hmacSha256(k0, token0).toHex())
        assertEquals(
            ACK_VECTOR_HEX,
            SessionCrypto.hmacSha256(k0, token0 + "ack".toByteArray(Charsets.UTF_8)).toHex()
        )
    }

    @Test
    fun `first sealed sender frame matches the canonical vector`() {
        val sealed = SessionCrypto(ikm, isSender = true)
            .seal(0x03, """{"t":7}""".toByteArray(Charsets.UTF_8))
        assertEquals(SEALED_FRAME_HEX, sealed.toHex())
    }

    @Test
    fun `receiver opens the canonical sealed frame and rejects its replay`() {
        val frame = ControlProtocol.parse(SEALED_FRAME_HEX.hexToBytes())!!
        val receiver = SessionCrypto(ikm, isSender = false)
        assertArrayEquals(
            """{"t":7}""".toByteArray(Charsets.UTF_8),
            receiver.open(frame.type, frame.seq, frame.body)
        )
        assertNull(receiver.open(frame.type, frame.seq, frame.body)) // replay
    }

    @Test
    fun `GATT chunking matches the canonical vector and round-trips`() {
        val frame = ByteArray(40) { it.toByte() }
        val chunks = GattChunker.split(frame, mtu = 23)
        assertEquals(CHUNKS_VECTOR, chunks.joinToString(":") { it.toHex() })

        var reassembled: ByteArray? = null
        val reassembler = GattReassembler { reassembled = it }
        chunks.forEach { reassembler.accept(it) }
        assertArrayEquals(frame, reassembled)
    }

    @Test
    fun `plaintext HELLO header matches the canonical vector`() {
        assertEquals(PLAIN_HEADER_HEX, SessionCrypto.header(0x01, 0L, 7).toHex())
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        // Pinned from a verified run; mirrored byte-for-byte in the Android test.
        const val UUID_VECTOR = "0e7b6825-c8a3-488a-a9d2-8964521b762e"
        const val HKDF_VECTOR_HEX =
            "8d45b1e0e773c57c9cc921defe349553a5bdf71944d7203c7f819f1f5d822797"
        const val HM_VECTOR_HEX =
            "7536fd15de79f5a92314ca3fe1efb366a50d3c86b95786aa283cba5b74e7edef"
        const val ACK_VECTOR_HEX =
            "67815a6e0f59cc5f9838316284872dea8c5cc8543e1251393dd576c8b7a05b14"
        const val SEALED_FRAME_HEX =
            "0300000001001723af2fee319aed2a3ebda805f19797678a0cdddfa1c1b2"
        // Chunk header = [rolling idx][flags: bit0 END, bit1 START]. The first chunk carries 0x02,
        // the last 0x01, a single-chunk frame 0x03. Older builds mask bit0 only, so START is
        // invisible to them — the vector changed, the interop did not.
        const val CHUNKS_VECTOR =
            "0002000102030405060708090a0b0c0d0e0f1011:" +
                "010012131415161718191a1b1c1d1e1f20212223:" +
                "020124252627"
        const val PLAIN_HEADER_HEX = "01000000000007"
    }
}
