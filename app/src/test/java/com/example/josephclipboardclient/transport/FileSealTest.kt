package com.EntropicSoftwareLab.ClipboardShare.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

/**
 * Unit + cross-platform golden vector for [FileSeal]. The pinned hex constants are mirrored in the
 * Windows module's FileSealVectorTest; if either port drifts (HKDF info, record framing, AAD =
 * chunkIndex, nonce layout) the hex stops matching and the interop break is caught offline before it
 * can corrupt an Android<->Windows encrypted file transfer. Keep the constants identical in both files.
 */
class FileSealTest {

    private val pairKey = ByteArray(32) { it.toByte() }            // 00 01 .. 1f
    private val subKey = FileSeal.deriveKey(pairKey)

    @Test
    fun `hkdf sub-key is the canonical cross-platform value`() {
        assertEquals(SUBKEY_HEX, subKey.toHex())
    }

    @Test
    fun `sealChunk produces the canonical cross-platform record`() {
        val nonce = ByteArray(12) { (0xB0 + it).toByte() }        // b0 b1 .. bb
        val plaintext = "file-aead-vector".toByteArray(Charsets.UTF_8)
        val record = FileSeal.sealChunk(SecretKeySpec(subKey, "AES"), plaintext, plaintext.size, 0L, nonce)
        assertEquals(VECTOR_HEX, record.toHex())
    }

    @Test
    fun `seal then open round-trips a multi-chunk payload`() {
        // 2.5 chunks so the loop, a full-chunk boundary, and a partial final chunk are all exercised.
        val plain = ByteArray(FileSeal.CHUNK_SIZE * 2 + 123) { ((it * 31 + 7) and 0xFF).toByte() }
        val sealed = ByteArrayOutputStream()
        FileSeal.sealStream(subKey, ByteArrayInputStream(plain), plain.size.toLong(), sealed)
        val opened = ByteArrayOutputStream()
        FileSeal.openStream(subKey, ByteArrayInputStream(sealed.toByteArray()), plain.size.toLong(), opened)
        assertArrayEquals(plain, opened.toByteArray())
        assertTrue("sealed must be larger than plaintext (framing + tags)", sealed.size() > plain.size)
    }

    @Test
    fun `open rejects a tampered ciphertext byte`() {
        val plain = "the quick brown fox".toByteArray(Charsets.UTF_8)
        val sealed = ByteArrayOutputStream()
        FileSeal.sealStream(subKey, ByteArrayInputStream(plain), plain.size.toLong(), sealed)
        val bytes = sealed.toByteArray()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte() // flip a tag bit
        assertThrows(Exception::class.java) {
            FileSeal.openStream(subKey, ByteArrayInputStream(bytes), plain.size.toLong(), ByteArrayOutputStream())
        }
    }

    @Test
    fun `open rejects a record decrypted at the wrong chunk index (AAD binding)`() {
        val plain = "position-bound".toByteArray(Charsets.UTF_8)
        val record = FileSeal.sealChunk(SecretKeySpec(subKey, "AES"), plain, plain.size, 0L)
        // Same bytes, but the reader expects them at chunk index 5 → AAD mismatch → reject.
        assertThrows(Exception::class.java) {
            FileSeal.openStream(subKey, ByteArrayInputStream(record), plain.size.toLong(), ByteArrayOutputStream(), startChunkIndex = 5L)
        }
    }

    @Test
    fun `open rejects the wrong key`() {
        val plain = "secret".toByteArray(Charsets.UTF_8)
        val sealed = ByteArrayOutputStream()
        FileSeal.sealStream(subKey, ByteArrayInputStream(plain), plain.size.toLong(), sealed)
        val wrongKey = FileSeal.deriveKey(ByteArray(32) { (it + 1).toByte() })
        assertThrows(Exception::class.java) {
            FileSeal.openStream(wrongKey, ByteArrayInputStream(sealed.toByteArray()), plain.size.toLong(), ByteArrayOutputStream())
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        // Pinned from a verified run; mirrored byte-for-byte in the Windows FileSealVectorTest.
        // pairKey = 00..1f ; subKey = HKDF-SHA256(pairKey, "file-aead-v1", 32)
        const val SUBKEY_HEX = "f1c436827054334b26c7b2dae0077575f17f9d5346f22ea03b8531b71a51ee8b"

        // record for subKey above, nonce = b0..bb, chunkIndex = 0, plaintext = "file-aead-vector"
        // layout: 00000020 (ctLen=32) | b0..bb (nonce) | 16B ct | 16B GCM tag
        const val VECTOR_HEX = "00000020b0b1b2b3b4b5b6b7b8b9babb" +
            "fbc594fdfab6164ac568b012e4f864554877c50568c8fe77c545aa74a83ef318"
    }
}
