package com.joseph.clipboard.windows.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform golden vector for [PairSeal]. The SAME constants live in the Android module's
 * PairSealVectorTest; if either port drifts (HKDF info, nonce layout, AAD, timestamp encoding,
 * magic), this hex stops matching and the interop break is caught offline — before a phone and a PC
 * fail to confirm each other's key and QR pairing silently stops working. Keep [HOST_VECTOR_HEX]
 * and [PEER_VECTOR_HEX] identical in both files.
 */
class PairSealVectorTest {

    private val key = ByteArray(32) { it.toByte() }                 // 00 01 .. 1f
    private val nonce = ByteArray(12) { (0xA0 + it).toByte() }      // a0 a1 .. ab
    private val ts = 1_700_000_000_000L
    private val tokenHex = "101112131415161718191a1b1c1d1e1f"

    @Test
    fun `host confirmation produces the canonical cross-platform bytes`() {
        assertEquals(
            HOST_VECTOR_HEX,
            PairSeal.sealWithNonce(key, PairSeal.hostConfirmation(tokenHex), ts, nonce).toHex()
        )
    }

    @Test
    fun `peer confirmation produces the canonical cross-platform bytes`() {
        assertEquals(
            PEER_VECTOR_HEX,
            PairSeal.sealWithNonce(key, PairSeal.peerConfirmation(tokenHex), ts, nonce).toHex()
        )
    }

    @Test
    fun `open decodes the canonical vector`() {
        assertArrayEquals(
            PairSeal.hostConfirmation(tokenHex),
            PairSeal.open(key, HOST_VECTOR_HEX.hexToBytes(), ts)
        )
    }

    @Test
    fun `verify accepts the matching confirmation`() {
        assertTrue(PairSeal.verify(key, HOST_VECTOR_HEX.hexToBytes(), PairSeal.hostConfirmation(tokenHex), ts))
    }

    /**
     * The two directions must not be interchangeable. If they were, a passive observer could reflect
     * the host's ACK back as the scanner's FIN and the host would pair with a peer that never
     * derived the key.
     */
    @Test
    fun `host confirmation does not satisfy the peer check`() {
        assertFalse(PairSeal.verify(key, HOST_VECTOR_HEX.hexToBytes(), PairSeal.peerConfirmation(tokenHex), ts))
        assertFalse(PairSeal.verify(key, PEER_VECTOR_HEX.hexToBytes(), PairSeal.hostConfirmation(tokenHex), ts))
    }

    @Test
    fun `a confirmation bound to another token is rejected`() {
        val otherToken = "ffffffffffffffffffffffffffffffff"
        assertFalse(PairSeal.verify(key, HOST_VECTOR_HEX.hexToBytes(), PairSeal.hostConfirmation(otherToken), ts))
    }

    @Test
    fun `a wrong key fails authentication`() {
        val wrong = ByteArray(32) { (it + 1).toByte() }
        assertNull(PairSeal.open(wrong, HOST_VECTOR_HEX.hexToBytes(), ts))
    }

    @Test
    fun `a tampered blob fails authentication`() {
        val tampered = HOST_VECTOR_HEX.hexToBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(PairSeal.open(key, tampered, ts))
    }

    @Test
    fun `a stale confirmation is rejected`() {
        assertNull(PairSeal.open(key, HOST_VECTOR_HEX.hexToBytes(), ts + PairSeal.FRESHNESS_WINDOW_MS + 1))
    }

    @Test
    fun `a truncated blob is rejected without throwing`() {
        assertNull(PairSeal.open(key, ByteArray(8), ts))
        assertNull(PairSeal.open(key, HOST_VECTOR_HEX.hexToBytes().copyOfRange(0, 12), ts))
    }

    /** A ClipboardSeal blob must not open here — the HKDF labels keep the subkeys apart. */
    @Test
    fun `a clipboard-sealed blob is not a pairing confirmation`() {
        val clipboard = ClipboardSeal.sealWithNonce(key, "hello".toByteArray(Charsets.UTF_8), ts, nonce)
        assertNull(PairSeal.open(key, clipboard, ts))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes() = ByteArray(length / 2) {
        substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        const val HOST_VECTOR_HEX =
            "50533100a0a1a2a3a4a5a6a7a8a9aaabf9c57af81e12d41108d4f628f721ffe236989d5d158" +
                "66b64acf0f8c30e84375f53fd3389074ac3ddc767bdc12c9b12eb954b796ba68e2a6e0e004a8225"
        const val PEER_VECTOR_HEX =
            "50533100a0a1a2a3a4a5a6a7a8a9aaabf9c57af81e12d41110dee02ef721ffe236989d5d158" +
                "66b64acf0f8c30e84375f53fd3389074ac3ddc767bdc12c0833ebe2af9b54d39aa0221ec56abf81"
    }
}
