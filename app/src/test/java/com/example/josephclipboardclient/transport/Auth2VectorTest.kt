package com.EntropicSoftwareLab.ClipboardShare.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-platform golden vector for the AUTH2 admission proof (XP-DEF-05).
 *
 * Exchange: receiver sends `OFFER_CHALLENGE|base64(nonce)`, sender answers
 * `OFFER_PROOF|base64(HMAC-SHA256(pairingKey, nonce))` before the incoming-file popup.
 * The proof bytes are pinned here and in the Windows module's Auth2VectorTest with the
 * IDENTICAL constant — if either port's SessionCrypto.hmacSha256 drifts, the hex stops
 * matching and the interop break is caught offline. Keep the constants in sync.
 */
class Auth2VectorTest {

    @Test
    fun `auth2 proof matches the pinned cross-platform vector`() {
        val pairingKey = ByteArray(32) { it.toByte() }              // 00 01 .. 1f
        val nonce = ByteArray(16) { (0xA0 + it).toByte() }          // a0 a1 .. af
        val proof = SessionCrypto.hmacSha256(pairingKey, nonce)
        assertEquals(AUTH2_PROOF_HEX, proof.joinToString("") { "%02x".format(it) })
    }

    companion object {
        /** HMAC-SHA256(key=00..1f, msg=a0..af) — same constant in the Windows Auth2VectorTest. */
        const val AUTH2_PROOF_HEX =
            "b1489727b2602a0e047cb237580a4c795aaf9023c5bc6b0b865c7fa17e0a2367"
    }
}
