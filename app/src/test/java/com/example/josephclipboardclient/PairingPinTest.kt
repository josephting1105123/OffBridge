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
