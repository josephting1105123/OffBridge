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

    // ---- v2 AEAD scheme: per-session key + session/file-bound AAD ------------------------------

    @Test
    fun `deriveKeyV2 is the canonical cross-platform per-session key`() {
        val keyV2 = FileSeal.deriveKeyV2(pairKey, V2_SESSION_ID)
        assertEquals(V2_SUBKEY_HEX, keyV2.toHex())
    }

    @Test
    fun `v2 sealChunk produces the canonical cross-platform record`() {
        val nonce = ByteArray(12) { it.toByte() }                 // 00 01 .. 0b
        val plaintext = "file-aead-vector".toByteArray(Charsets.UTF_8)
        val keyV2 = FileSeal.deriveKeyV2(pairKey, V2_SESSION_ID)
        val record = FileSeal.sealChunk(
            SecretKeySpec(keyV2, "AES"), plaintext, plaintext.size, 0L, nonce,
            aadPrefix = FileSeal.v2AadPrefix(V2_SESSION_ID, 0L),
        )
        assertEquals(V2_VECTOR_HEX, record.toHex())
    }

    @Test
    fun `v2 seal then open round-trips with the session and file binding`() {
        val keyV2 = FileSeal.deriveKeyV2(pairKey, V2_SESSION_ID)
        val aadPrefix = FileSeal.v2AadPrefix(V2_SESSION_ID, 0L)
        val plain = ByteArray(FileSeal.CHUNK_SIZE + 77) { ((it * 17 + 3) and 0xFF).toByte() }
        val sealed = ByteArrayOutputStream()
        FileSeal.sealStream(keyV2, ByteArrayInputStream(plain), plain.size.toLong(), sealed, aadPrefix = aadPrefix)
        val opened = ByteArrayOutputStream()
        FileSeal.openStream(keyV2, ByteArrayInputStream(sealed.toByteArray()), plain.size.toLong(), opened, aadPrefix = aadPrefix)
        assertArrayEquals(plain, opened.toByteArray())
    }

    @Test
    fun `v2 open rejects a record opened with the wrong file index (splice protection)`() {
        val nonce = ByteArray(12) { it.toByte() }
        val plaintext = "file-aead-vector".toByteArray(Charsets.UTF_8)
        val keyV2 = FileSeal.deriveKeyV2(pairKey, V2_SESSION_ID)
        // Sealed as file 0, but the reader binds file 1 → AAD mismatch → AEAD auth failure.
        val record = FileSeal.sealChunk(
            SecretKeySpec(keyV2, "AES"), plaintext, plaintext.size, 0L, nonce,
            aadPrefix = FileSeal.v2AadPrefix(V2_SESSION_ID, 0L),
        )
        assertThrows(Exception::class.java) {
            FileSeal.openStream(
                keyV2, ByteArrayInputStream(record), plaintext.size.toLong(), ByteArrayOutputStream(),
                aadPrefix = FileSeal.v2AadPrefix(V2_SESSION_ID, 1L),
            )
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

        // ---- v2 golden vectors (also mirrored byte-for-byte in the Windows FileSealVectorTest) ----
        // pairKey = 00..1f ; sessionId = "vector-session-1" ; fileIndex = 0 ; chunkIndex = 0
        const val V2_SESSION_ID = "vector-session-1"

        // deriveKeyV2 = HKDF-SHA256(pairKey, "file-aead-v2" || "vector-session-1", 32)
        const val V2_SUBKEY_HEX = "9fa6401f891b4b2093782d3165c08704fbe3bfcf9b901774bf205185aca30f67"

        // record for keyV2 above, nonce = 00..0b, chunkIndex = 0, plaintext = "file-aead-vector",
        // aadPrefix = v2AadPrefix("vector-session-1", 0) = "vector-session-1" || u64be(0).
        // full AAD = sessionIdBytes || u64be(fileIndex=0) || u64be(chunkIndex=0).
        // layout: 00000020 (ctLen=32) | 00..0b (nonce) | 16B ct | 16B GCM tag
        const val V2_VECTOR_HEX = "00000020000102030405060708090a0b" +
            "2306ba5827b3a453f76db360ef673ed32b54145ec354549960f968a235819b95"
    }
}
