package com.EntropicSoftwareLab.ClipboardShare.transport

import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    /**
     * The off-grid sealed stream is negotiated by a cap BIT and an ACCEPT_OFFER FLAG, neither of
     * which appears in any type signature — a renamed flag or a shifted bit would compile fine on
     * both platforms and simply fall back to plaintext forever, silently undoing the encryption.
     * Pin both. Bit 6 specifically: 0..5 are BLE_ADVERTISE, BLE_SCAN, BT_L2CAP, LOHS, AWARE and
     * COMPLETION_ACK, so reusing any of those would make an unrelated capability mean "seal".
     */
    @Test
    fun `off-grid seal wire constants match the pinned cross-platform vector`() {
        assertEquals(SEALED_STREAM_CAP_BIT, TransportCaps.SEALED_STREAM)
        assertEquals(1 shl 6, TransportCaps.SEALED_STREAM)
        assertEquals(ACCEPT_FLAG_SEAL_TOKEN, OffGridHandshake.ACCEPT_FLAG_SEAL)
        assertNotEquals(OffGridHandshake.ACCEPT_FLAG_SEAL, "ack")
    }

    /**
     * THE interop assertion for the sealed off-grid stream: this expected hex is copied verbatim
     * from the Windows Auth2VectorTest. Windows is always the QR sender and Android the scanner, so
     * if these two derivations ever diverge the phone unseals with the wrong key and every chunk
     * fails its GCM tag — a failure that only ever reproduces on real hardware, across two
     * codebases, at the worst moment. Same shared secret, same sessionId, same 32 bytes, offline.
     */
    @Test
    fun `off-grid seal sub-key matches the pinned cross-platform vector`() {
        val sharedKey = ByteArray(32) { it.toByte() }               // 00 01 .. 1f
        val subKey = FileSeal.deriveKeyV2(sharedKey, PINNED_SESSION_ID)
        assertEquals(OFFGRID_SEAL_SUBKEY_HEX, subKey.joinToString("") { "%02x".format(it) })
    }

    companion object {
        /** Off-grid sealed-stream cap bit — same constant as Windows CAP_SEALED_STREAM. */
        const val SEALED_STREAM_CAP_BIT = 1 shl 6
        /** ACCEPT_OFFER flag requesting the seal — same constant as Windows ACCEPT_FLAG_SEAL. */
        const val ACCEPT_FLAG_SEAL_TOKEN = "seal"
        /** Fixed sessionId for the derivation vector; the Windows test uses the same string. */
        const val PINNED_SESSION_ID = "offgrid-vector-session"
        /** HKDF-SHA256(sharedKey=00..1f, "file-aead-v2" || PINNED_SESSION_ID, 32). Byte-identical to
         *  the Windows Auth2VectorTest constant — that equality IS the interop guarantee. */
        const val OFFGRID_SEAL_SUBKEY_HEX =
            "41eb113eb09a2b46f69f6509c8e4895d2ef3ac0e7c1f60af47116b24f17a841c"
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
