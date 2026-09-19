package com.joseph.clipboard.windows.network.control

import com.joseph.clipboard.windows.network.OffGridQrSender
import com.joseph.clipboard.windows.network.TransferDaemon
import com.joseph.clipboard.windows.storage.FileSeal
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
 * before any file bytes are streamed — see FileTransferDaemon.performAcceptAuth2Challenge /
 * ClipboardAppController.answerAcceptChallengeIfExpected. Same HMAC primitive as OFFER, so the
 * proof bytes are the same golden vector; what's pinned separately here is the FRAME PREFIX
 * strings and the ACCEPT_AUTH_CAP capability token, since a drift there (not the HMAC math) is
 * what would silently desync the two platforms' parsers.
 *
 * All constants are pinned here and in the Android module's Auth2VectorTest with IDENTICAL
 * values — if either port's SessionCrypto.hmacSha256 or these wire strings drift, the assertions
 * fail and the interop break is caught offline. Keep both files in sync.
 */
class Auth2VectorTest {

    @Test
    fun auth2ProofMatchesThePinnedCrossPlatformVector() {
        val pairingKey = ByteArray(32) { it.toByte() }              // 00 01 .. 1f
        val nonce = ByteArray(16) { (0xA0 + it).toByte() }          // a0 a1 .. af
        val proof = SessionCrypto.hmacSha256(pairingKey, nonce)
        assertEquals(AUTH2_PROOF_HEX, proof.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun auth2rWireStringsMatchThePinnedCrossPlatformVector() {
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
    fun offGridSealWireConstantsMatchThePinnedCrossPlatformVector() {
        assertEquals(SEALED_STREAM_CAP_BIT, OffGridQrSender.CAP_SEALED_STREAM)
        assertEquals(1 shl 6, OffGridQrSender.CAP_SEALED_STREAM)
        assertEquals(ACCEPT_FLAG_SEAL_TOKEN, OffGridQrSender.ACCEPT_FLAG_SEAL)
        // Must not collide with the delivery-ack flag that shares the same field.
        assertNotEquals(OffGridQrSender.ACCEPT_FLAG_SEAL, "ack")
    }

    /**
     * The sealed off-grid key derivation must be exactly the LAN path's v2 derivation, keyed by the
     * ECDH secret. If either side ever pre-derived before handing the key to the byte-pump (which
     * derives internally) the two ends would derive a different number of times and every chunk
     * would fail its tag check — this pins the single-derivation contract to a fixed vector.
     */
    @Test
    fun offGridSealSubKeyMatchesThePinnedCrossPlatformVector() {
        val sharedKey = ByteArray(32) { it.toByte() }               // 00 01 .. 1f
        val subKey = FileSeal.deriveKeyV2(sharedKey, PINNED_SESSION_ID)
        assertEquals(OFFGRID_SEAL_SUBKEY_HEX, subKey.joinToString("") { "%02x".format(it) })
    }

    companion object {
        /** Off-grid sealed-stream cap bit — same constant as Android TransportCaps.SEALED_STREAM. */
        const val SEALED_STREAM_CAP_BIT = 1 shl 6
        /** ACCEPT_OFFER flag requesting the seal — same constant as Android
         *  OffGridHandshake.ACCEPT_FLAG_SEAL. */
        const val ACCEPT_FLAG_SEAL_TOKEN = "seal"
        /** Fixed sessionId for the derivation vector; the Android test uses the same string. */
        const val PINNED_SESSION_ID = "offgrid-vector-session"
        /** HKDF-SHA256(sharedKey=00..1f, "file-aead-v2" || PINNED_SESSION_ID, 32). Filled from the
         *  first green run and asserted identically on Android — see that test's twin constant. */
        const val OFFGRID_SEAL_SUBKEY_HEX =
            "41eb113eb09a2b46f69f6509c8e4895d2ef3ac0e7c1f60af47116b24f17a841c"
        /** HMAC-SHA256(key=00..1f, msg=a0..af) — same constant in the Android Auth2VectorTest. */
        const val AUTH2_PROOF_HEX =
            "b1489727b2602a0e047cb237580a4c795aaf9023c5bc6b0b865c7fa17e0a2367"
        /** Accept/resume-direction discovery capability token — same constant in the Android
         *  Auth2VectorTest and TransferDaemon.ACCEPT_AUTH_CAP on both platforms. */
        const val ACCEPT_AUTH_CAP_TOKEN = "AUTH2R"
        const val ACCEPT_CHALLENGE_PREFIX = "ACCEPT_CHALLENGE|"
        const val ACCEPT_PROOF_PREFIX = "ACCEPT_PROOF|"
    }
}
