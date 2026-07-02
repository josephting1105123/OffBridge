package com.EntropicSoftwareLab.ClipboardShare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SAS (short authentication string) is the whole MITM defence: two honestly-paired devices must
 * derive the SAME 6-digit code, and an interceptor sitting between them must derive a DIFFERENT code
 * on each side. [EcdhSession.deriveSecrets] is pure JVM (JCA EC), so this runs on the host.
 */
class PairingPinTest {

    @Test
    fun `pin is six numeric digits`() {
        val a = EcdhSession(); val b = EcdhSession()
        val (_, pin) = a.deriveSecrets(b.getPublicKey())
        assertEquals(6, pin.length)
        assertTrue(pin.all { it.isDigit() })
    }

    @Test
    fun `honest pairing derives an identical key and pin on both sides`() {
        val alice = EcdhSession(); val bob = EcdhSession()
        val (aKey, aPin) = alice.deriveSecrets(bob.getPublicKey())
        val (bKey, bPin) = bob.deriveSecrets(alice.getPublicKey())
        assertArrayEqualsMsg(aKey, bKey, "ECDH shared key must match")
        assertEquals("SAS must match for an honest pair", aPin, bPin)
    }

    @Test
    fun `honest v2 pairing derives identical key and v2 pin on both sides`() {
        val alice = EcdhSession(); val bob = EcdhSession()
        val (aKey, aPin) = alice.deriveSecrets(bob.getPublicKey(), useV2Pin = true)
        val (bKey, bPin) = bob.deriveSecrets(alice.getPublicKey(), useV2Pin = true)
        assertArrayEqualsMsg(aKey, bKey, "ECDH shared key must match (v2)")
        assertEquals("v2 SAS must match for an honest pair", aPin, bPin)
        assertEquals(6, aPin.length)
        assertTrue(aPin.all { it.isDigit() })
    }

    /**
     * Cross-platform pin: this exact expected value is hard-coded identically in the Windows test
     * (CryptoEnginePinV2Test) so the two HKDF derivations can never silently drift. If this fails,
     * the Android and Windows v2 PIN derivations no longer agree.
     */
    @Test
    fun `v2 pin from fixed shared secret matches the pinned cross-platform vector`() {
        val secret = ByteArray(32) { it.toByte() } // 0x00..0x1f
        assertEquals("912594", EcdhSession.pinV2FromSecret(secret))
    }

    @Test
    fun `man-in-the-middle yields divergent keys (and codes) on each side`() {
        val alice = EcdhSession(); val bob = EcdhSession(); val mallory = EcdhSession()
        // Mallory terminates each side separately: alice<->mallory and mallory<->bob.
        val (aKey, aPin) = alice.deriveSecrets(mallory.getPublicKey())
        val (bKey, bPin) = bob.deriveSecrets(mallory.getPublicKey())
        // Keys differ with certainty (distinct shared secrets) — this is the security-relevant fact.
        assertFalse("MITM must not produce a shared key", aKey.contentEquals(bKey))
        // Therefore the user-visible codes almost certainly differ, exposing the attack.
        assertFalse("SAS should diverge under MITM", aPin == bPin)
    }

    private fun assertArrayEqualsMsg(a: ByteArray, b: ByteArray, msg: String) =
        assertTrue(msg, a.contentEquals(b))
}
