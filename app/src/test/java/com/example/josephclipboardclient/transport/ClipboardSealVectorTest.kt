package com.EntropicSoftwareLab.ClipboardShare.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-platform golden vector for [ClipboardSeal]. The SAME constants live in the Windows module's
 * ClipboardSealVectorTest; if either port drifts (HKDF info, nonce layout, AAD, timestamp encoding,
 * magic), this hex stops matching and the interop break is caught offline — before it silently kills
 * Android<->Windows clipboard sync. Keep [VECTOR_HEX] identical in both files.
 */
class ClipboardSealVectorTest {

    private val key = ByteArray(32) { it.toByte() }                 // 00 01 .. 1f
    private val nonce = ByteArray(12) { (0xA0 + it).toByte() }      // a0 a1 .. ab
    private val ts = 1_700_000_000_000L
    private val plaintext = "cross-platform-vector".toByteArray(Charsets.UTF_8)

    @Test
    fun `seal produces the canonical cross-platform bytes`() {
        assertEquals(VECTOR_HEX, ClipboardSeal.sealWithNonce(key, plaintext, ts, nonce).toHex())
    }

    @Test
    fun `open decodes the canonical vector`() {
        assertArrayEquals(plaintext, ClipboardSeal.open(key, VECTOR_HEX.hexToBytes(), ts))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        // Canonical sealed blob (hex) for key=00..1f, nonce=a0..ab, ts=1700000000000,
        // plaintext="cross-platform-vector". Pinned from a verified run; mirrored in the Windows test.
        const val VECTOR_HEX = "43533100a0a1a2a3a4a5a6a7a8a9aaab" +
            "9f5014a73574914bb5033d417bf218e3a61d2bd9f609696d305d0a2dc521cace583bf34bb942d6ddf60b5d52cd"
    }
}
