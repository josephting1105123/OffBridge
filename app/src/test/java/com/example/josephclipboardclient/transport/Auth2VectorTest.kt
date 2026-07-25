package com.EntropicSoftwareLab.ClipboardShare.transport

import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-platform golden vector for the AUTH2 admission proof (XP-DEF-05) — both directions.
 *
 * OFFER direction: receiver sends `OFFER_CHALLENGE|base64(nonce)`, sender answers
 * `OFFER_PROOF|base64(HMAC-SHA256(pairingKey, nonce))` before the incoming-file popup.
 *
 * ACCEPT/RESUME direction (AUTH2R): sender sends `ACCEPT_CHALLENGE|base64(nonce)`, the peer
 * accepting/resuming the transfer answers `ACCEPT_PROOF|base64(HMAC-SHA256(pairingKey, nonce))`
 * before any file bytes are streamed — see FileTransferServer.performAcceptAuth2Challenge /
 * DownloadEngine.answerAcceptChallengeIfExpected. Same HMAC primitive as OFFER, so the proof
 * bytes are the same golden vector; what's pinned separately here is the FRAME PREFIX strings and
 * the ACCEPT_AUTH_CAP capability token, since a drift there (not the HMAC math) is what would
 * silently desync the two platforms' parsers.
 *
 * All constants are pinned here and in the Windows module's Auth2VectorTest with IDENTICAL
 * values — if either port's SessionCrypto.hmacSha256 or these wire strings drift, the assertions
 * fail and the interop break is caught offline. Keep both files in sync.
 */
class Auth2VectorTest {

    @Test
    fun `auth2 proof matches the pinned cross-platform vector`() {
        val pairingKey = ByteArray(32) { it.toByte() }              // 00 01 .. 1f
        val nonce = ByteArray(16) { (0xA0 + it).toByte() }          // a0 a1 .. af
        val proof = SessionCrypto.hmacSha256(pairingKey, nonce)
        assertEquals(AUTH2_PROOF_HEX, proof.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `auth2r wire strings match the pinned cross-platform vector`() {
        assertEquals(ACCEPT_AUTH_CAP_TOKEN, TransferDaemon.ACCEPT_AUTH_CAP)
        assertEquals(ACCEPT_CHALLENGE_PREFIX, "ACCEPT_CHALLENGE|")
        assertEquals(ACCEPT_PROOF_PREFIX, "ACCEPT_PROOF|")
    }

    companion object {
        /** HMAC-SHA256(key=00..1f, msg=a0..af) — same constant in the Windows Auth2VectorTest. */
        const val AUTH2_PROOF_HEX =
            "b1489727b2602a0e047cb237580a4c795aaf9023c5bc6b0b865c7fa17e0a2367"
        /** Accept/resume-direction discovery capability token — same constant in the Windows
         *  Auth2VectorTest and TransferDaemon.ACCEPT_AUTH_CAP on both platforms. */
        const val ACCEPT_AUTH_CAP_TOKEN = "AUTH2R"
        const val ACCEPT_CHALLENGE_PREFIX = "ACCEPT_CHALLENGE|"
        const val ACCEPT_PROOF_PREFIX = "ACCEPT_PROOF|"
    }
}
