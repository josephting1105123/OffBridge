package com.EntropicSoftwareLab.ClipboardShare.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import kotlin.concurrent.thread

/**
 * Golden vector for the off-grid resume proof. The SAME constants live in the Windows module's
 * OffGridResumeAuthVectorTest; Android receivers prove to Android and Windows senders, so drift on
 * either side breaks resume across platforms. Cross-checked against an independent Node HMAC.
 */
class OffGridResumeAuthVectorTest {

    private val key = ByteArray(32) { (0x10 + it).toByte() }     // 10..2f
    private val nonce = ByteArray(16) { (0xA0 + it).toByte() }   // a0..af
    private val sid = "0f1e2d3c-sess"

    @Test
    fun `proof matches the canonical vector`() {
        assertEquals(PROOF_HEX, OffGridResumeAuth.proof(key, nonce, sid).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `receiver answers a challenge with the exact proof line`() {
        val challenge = "P2P_RESUME_CHALLENGE|${Base64.getEncoder().encodeToString(nonce)}\n"
        val out = ByteArrayOutputStream()
        assertTrue(OffGridResumeAuth.answer(ByteArrayInputStream(challenge.toByteArray()), out, key, sid))
        val proofB64 = Base64.getEncoder().encodeToString(OffGridResumeAuth.proof(key, nonce, sid))
        assertEquals("P2P_RESUME_PROOF|$proofB64\n", out.toString("UTF-8"))
    }

    @Test
    fun `challenge round-trip accepts the right key and rejects a wrong one`() {
        assertTrue(roundTrip(key))
        assertFalse(roundTrip(ByteArray(32) { 0x01 }))
    }

    @Test
    fun `receiver refuses a line that is not a challenge`() {
        val out = ByteArrayOutputStream()
        assertFalse(OffGridResumeAuth.answer(ByteArrayInputStream("FILEDATA...\n".toByteArray()), out, key, sid))
        assertEquals(0, out.size())
    }

    /** Sender challenges with [key]; a receiver holding [receiverKey] answers over in-memory pipes. */
    private fun roundTrip(receiverKey: ByteArray): Boolean {
        val toReceiver = PipedOutputStream(); val receiverIn = PipedInputStream(toReceiver)
        val toSender = PipedOutputStream(); val senderIn = PipedInputStream(toSender)
        val rx = thread { runCatching { OffGridResumeAuth.answer(receiverIn, toSender, receiverKey, sid) } }
        val ok = OffGridResumeAuth.challenge(senderIn, toReceiver, key, sid)
        rx.join(5_000)
        return ok
    }

    companion object {
        const val PROOF_HEX = "4df97e2f8e500a719b279e78269a3c13dca1cd6d361ae0eff4252fb8eb3d0661"
    }
}
