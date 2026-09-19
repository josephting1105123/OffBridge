package com.joseph.clipboard.windows.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Receive-gate contract for [CryptoEngine.decryptString] — the Windows clipboard receive path
 * after the 3.5.0 CBC retirement. This is the ONLY host-testable copy of the gate (the Android
 * twin pulls in android.util.Base64), so its negative paths are pinned here: authenticated
 * payloads round-trip, and every non-sealed / malformed / tampered payload is rejected rather
 * than decrypted. Surfaced by the audit-testable skill's coverage sweep.
 */
class CryptoEngineDecryptTest {

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun sealedPayloadRoundTrips() {
        val enc = CryptoEngine.encryptStringAuthenticated("clipboard: hunter2", key)
        assertEquals("clipboard: hunter2", CryptoEngine.decryptString(enc, key))
    }

    @Test
    fun sealedPayloadToleratesSurroundingWhitespace() {
        val enc = CryptoEngine.encryptStringAuthenticated("x", key)
        assertEquals("x", CryptoEngine.decryptString("  \n$enc\t ", key))
    }

    @Test
    fun malformedBase64IsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CryptoEngine.decryptString("!!!not base64!!!", key)
        }
    }

    @Test
    fun nonSealedPayloadIsRejectedNeverDecryptedAsCbc() {
        // A legacy CBC-shaped blob (16-byte IV + ciphertext, no CS1 magic) must be refused,
        // not silently decrypted down the retired path.
        val legacyLooking = Base64.getEncoder().encodeToString(ByteArray(48) { 0x11 })
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CryptoEngine.decryptString(legacyLooking, key, senderIp = "10.0.0.5")
        }
        assertTrue("rejection should name the retired path", ex.message!!.contains("CBC path retired"))
    }

    @Test
    fun tamperedSealedPayloadFailsVerification() {
        val enc = CryptoEngine.encryptStringAuthenticated("transfer to account 123", key)
        val bytes = Base64.getDecoder().decode(enc)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte() // flip a tag bit
        val tampered = Base64.getEncoder().encodeToString(bytes)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CryptoEngine.decryptString(tampered, key)
        }
        assertTrue("should fail authentication, not decode garbage", ex.message!!.contains("failed verification"))
    }

    @Test
    fun wrongKeyFailsVerification() {
        val enc = CryptoEngine.encryptStringAuthenticated("secret", key)
        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            CryptoEngine.decryptString(enc, wrongKey)
        }
    }
}
