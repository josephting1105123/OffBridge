package com.EntropicSoftwareLab.ClipboardShare.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Pins the AEAD control-plane guarantees of [SessionCrypto]: confidentiality + integrity per frame,
 * direction-separated nonces, and monotonic-sequence replay rejection. Pure JVM — host-runnable.
 */
class SessionCryptoTest {

    private val sharedKey = ByteArray(32) { (it * 7).toByte() }

    /** Split a sealed wire frame (type|seq|len|ct) back into the (type, seq, body) that open() wants. */
    private fun parse(frame: ByteArray): Triple<Int, Long, ByteArray> {
        val type = frame[0].toInt() and 0xFF
        val seq = (ByteBuffer.wrap(frame, 1, 4).int.toLong() and 0xFFFFFFFFL)
        val body = frame.copyOfRange(7, frame.size)
        return Triple(type, seq, body)
    }

    @Test
    fun `sender seals and receiver opens across the direction boundary`() {
        val sender = SessionCrypto(sharedKey, isSender = true)
        val receiver = SessionCrypto(sharedKey, isSender = false)
        val payload = "control: START_TRANSFER".toByteArray()

        val (type, seq, body) = parse(sender.seal(0x10, payload))
        assertArrayEquals(payload, receiver.open(type, seq, body))
    }

    @Test
    fun `replayed frame is dropped (non-monotonic sequence)`() {
        val sender = SessionCrypto(sharedKey, isSender = true)
        val receiver = SessionCrypto(sharedKey, isSender = false)

        val (t, s, b) = parse(sender.seal(0x10, "once".toByteArray()))
        assertArrayEquals("once".toByteArray(), receiver.open(t, s, b))
        assertNull(receiver.open(t, s, b)) // exact replay
    }

    @Test
    fun `tampered frame body fails the GCM tag`() {
        val sender = SessionCrypto(sharedKey, isSender = true)
        val receiver = SessionCrypto(sharedKey, isSender = false)

        val (t, s, b) = parse(sender.seal(0x10, "integrity".toByteArray()))
        b[0] = (b[0].toInt() xor 0x01).toByte()
        assertNull(receiver.open(t, s, b))
    }

    @Test
    fun `a different shared key cannot open the frame`() {
        val sender = SessionCrypto(sharedKey, isSender = true)
        val attacker = SessionCrypto(ByteArray(32) { 0x01 }, isSender = false)

        val (t, s, b) = parse(sender.seal(0x10, "secret".toByteArray()))
        assertNull(attacker.open(t, s, b))
    }
}
