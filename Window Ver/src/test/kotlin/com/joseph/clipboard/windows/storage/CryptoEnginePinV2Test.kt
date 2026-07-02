package com.joseph.clipboard.windows.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the v2 pairing PIN so the Windows and Android HKDF derivations can never silently drift.
 *
 * The AES key is still SHA-256(sharedSecret); v2 changes only the out-of-band verification PIN to
 * HKDF-SHA256(sharedSecret, "clipshare-pair-pin-v2", 4) read Little Endian. The [EXPECTED] value
 * is hard-coded identically in the Android module's PairingPinTest. If either side changes the HKDF
 * label, length, endianness, or salt, this vector stops reproducing and the two clients would show
 * different codes for the same honest pairing.
 */
class CryptoEnginePinV2Test {

    // Fixed shared secret 0x00..0x1f, matching the Android cross-platform vector.
    private val secret = ByteArray(32) { it.toByte() }
    private val expected = "912594"

    @Test
    fun `v2 pin from fixed shared secret matches the pinned cross-platform vector`() {
        assertEquals(expected, EcdhSession.pinV2FromSecret(secret))
    }

    @Test
    fun `v2 pin is six numeric digits`() {
        val pin = EcdhSession.pinV2FromSecret(secret)
        assertEquals(6, pin.length)
        assertTrue(pin.all { it.isDigit() })
    }
}
