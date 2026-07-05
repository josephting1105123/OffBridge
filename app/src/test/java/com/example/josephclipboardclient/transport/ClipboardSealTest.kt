package com.EntropicSoftwareLab.ClipboardShare.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural contract for the authenticated clipboard AEAD ([ClipboardSeal]) that replaces the
 * legacy unauthenticated AES/CBC path. These run on the host JVM (no Android APIs in the class
 * under test), so they execute in milliseconds with `./gradlew test`.
 */
class ClipboardSealTest {

    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }
    private val now = 1_700_000_000_000L

    @Test
    fun `seal then open round-trips the plaintext`() {
        val pt = "secret clipboard: hunter2".toByteArray()
        val blob = ClipboardSeal.seal(key, pt, now)
        assertTrue(ClipboardSeal.isSealed(blob))
        assertArrayEquals(pt, ClipboardSeal.open(key, blob, now))
    }

    @Test
    fun `wrong key fails authentication`() {
        val blob = ClipboardSeal.seal(key, "x".toByteArray(), now)
        assertNull(ClipboardSeal.open(otherKey, blob, now))
    }

    @Test
    fun `tampered ciphertext is rejected, not silently accepted`() {
        val blob = ClipboardSeal.seal(key, "transfer to account 123".toByteArray(), now)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte() // flip a ciphertext bit
        assertNull(ClipboardSeal.open(key, blob, now))
    }

    @Test
    fun `a fresh nonce makes two seals of the same text differ`() {
        val a = ClipboardSeal.seal(key, "same".toByteArray(), now)
        val b = ClipboardSeal.seal(key, "same".toByteArray(), now)
        assertFalse(a.contentEquals(b)) // no IV/nonce reuse
    }

    @Test
    fun `stale timestamp outside the freshness window is rejected (replay bound)`() {
        val blob = ClipboardSeal.seal(key, "old".toByteArray(), now)
        val tooLate = now + ClipboardSeal.FRESHNESS_WINDOW_MS + 1
        assertNull(ClipboardSeal.open(key, blob, tooLate))
        assertArrayEquals("old".toByteArray(), ClipboardSeal.open(key, blob, now + ClipboardSeal.FRESHNESS_WINDOW_MS - 1))
    }

    @Test
    fun `replayed blob is rejected even when first accepted at the early clock edge`() {
        // Models a receiver whose clock trails the sender's embedded timestamp by nearly the
        // whole window at first accept. The nonce cache must age by the blob's own timestamp
        // (not the acceptance clock), or the replay below slips through. Regression for the
        // XP-DEF-06 clock-model fix.
        val window = ClipboardSeal.FRESHNESS_WINDOW_MS
        val replayKey = ByteArray(32) { (0x40 + it).toByte() } // distinct key → isolated nonce cache
        val blob = ClipboardSeal.seal(replayKey, "cmd: wipe".toByteArray(), now)
        // First accept at the early edge (abs == window, still fresh): recorded.
        assertArrayEquals("cmd: wipe".toByteArray(), ClipboardSeal.open(replayKey, blob, now - window))
        // Replay of the identical blob later, still inside the timestamp's window → must be null.
        assertNull(ClipboardSeal.open(replayKey, blob, now + window - 1))
    }

    @Test
    fun `legacy CBC-style blob is not mistaken for the sealed format`() {
        val legacyIvPlusCt = ByteArray(48) { 0x11 } // looks like IV(16)+ciphertext, no magic prefix
        assertFalse(ClipboardSeal.isSealed(legacyIvPlusCt))
        assertNull(ClipboardSeal.open(key, legacyIvPlusCt, now))
    }
}
