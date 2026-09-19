package com.joseph.clipboard.windows.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cross-platform golden vector for [ClipboardSeal]. [VECTOR_HEX] is identical to the Android module's
 * ClipboardSealVectorTest — if the Windows port drifts from Android (HKDF info, nonce layout, AAD,
 * timestamp encoding, magic) the seal no longer reproduces this hex and open() can't decode it, so
 * the interop break is caught here offline instead of silently killing Android<->Windows sync.
 */
class ClipboardSealVectorTest {

    private val key = ByteArray(32) { it.toByte() }                 // 00 01 .. 1f
    private val nonce = ByteArray(12) { (0xA0 + it).toByte() }      // a0 a1 .. ab
    private val ts = 1_700_000_000_000L
    private val plaintext = "cross-platform-vector".toByteArray(Charsets.UTF_8)

    @Test
    fun `seal reproduces the canonical cross-platform bytes`() {
        assertEquals(VECTOR_HEX, ClipboardSeal.sealWithNonce(key, plaintext, ts, nonce).toHex())
    }

    @Test
    fun `open decodes a blob sealed by the other platform`() {
        assertArrayEquals(plaintext, ClipboardSeal.open(key, VECTOR_HEX.hexToBytes(), ts))
    }

    @Test
    fun `tampered vector is rejected`() {
        val b = VECTOR_HEX.hexToBytes()
        b[b.size - 1] = (b[b.size - 1].toInt() xor 0x01).toByte()
        assertNull(ClipboardSeal.open(key, b, ts))
    }

    @Test
    fun `replayed blob is rejected even when first accepted at the early clock edge`() {
        // Mirrors the Android ClipboardSealTest regression: the nonce cache must age by the blob's
        // embedded timestamp, not the receiver acceptance clock, or a replay accepted at the early
        // edge of the window slips back in later (XP-DEF-06 clock-model fix).
        val window = ClipboardSeal.FRESHNESS_WINDOW_MS
        val replayKey = ByteArray(32) { (0x40 + it).toByte() } // distinct key → isolated nonce cache
        val blob = ClipboardSeal.seal(replayKey, "cmd: wipe".toByteArray(), ts)
        assertArrayEquals("cmd: wipe".toByteArray(), ClipboardSeal.open(replayKey, blob, ts - window))
        assertNull(ClipboardSeal.open(replayKey, blob, ts + window - 1))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        // MUST match app/src/test/.../transport/ClipboardSealVectorTest.kt exactly.
        const val VECTOR_HEX = "43533100a0a1a2a3a4a5a6a7a8a9aaab" +
            "9f5014a73574914bb5033d417bf218e3a61d2bd9f609696d305d0a2dc521cace583bf34bb942d6ddf60b5d52cd"
    }
}
