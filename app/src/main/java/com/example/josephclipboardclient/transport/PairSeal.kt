package com.EntropicSoftwareLab.ClipboardShare.transport

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key-confirmation crypto for QR pairing.
 *
 *   wire = MAGIC(4) | nonce(12) | AES-256-GCM(body)      (GCM tag is inside the ciphertext)
 *   body = ts(8, big-endian millis) | plaintext
 *   key  = HKDF-SHA256(pair_key, info = "pair-confirm-v1", 32)
 *   AAD  = MAGIC
 *
 * Both sides of a QR pairing derive the same AES-256 key from ECDH, then prove it by exchanging a
 * blob only the holder of that key could produce. Neither side records trust until the peer's blob
 * opens — that check is what replaces the 6-digit PIN the LAN flow makes a human compare.
 *
 * Its own HKDF label keeps this subkey separate from [ClipboardSeal] ("clip-aead-v1") and
 * [SessionCrypto] ("ctrl-v1"), so a confirmation blob can never be replayed into either of those
 * paths. There is no nonce cache here: the pairing token is one-shot and the exchange happens
 * inside a single live socket, so a replay has nothing to attach itself to.
 *
 * Pure JVM (no Android APIs) so the golden vector runs on the host and pins the Windows port.
 * Byte-identical counterpart: Windows `storage/PairSeal.kt`.
 */
object PairSeal {

    /** 4-byte self-describing marker — "PS1\0". */
    private val MAGIC = byteArrayOf(0x50, 0x53, 0x31, 0x00)
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val TS_LEN = 8
    private val INFO = "pair-confirm-v1".toByteArray(Charsets.UTF_8)

    /**
     * How far a confirmation's embedded timestamp may sit from the receiver's clock. Generous on
     * purpose: this is not the security bound — the one-shot token and its own 3-minute expiry are —
     * so a phone and a PC whose clocks disagree by a couple of minutes must still be able to pair
     * rather than fail with an unexplainable "couldn't prove the key".
     */
    const val FRESHNESS_WINDOW_MS = 600_000L

    /**
     * Confirmation plaintexts. Each side seals a different one so a blob captured in one direction
     * can't be reflected back to satisfy the other — the host's ACK would otherwise be a valid FIN.
     */
    fun hostConfirmation(tokenHex: String): ByteArray = "host|$tokenHex".toByteArray(Charsets.UTF_8)

    fun peerConfirmation(tokenHex: String): ByteArray = "peer|$tokenHex".toByteArray(Charsets.UTF_8)

    fun seal(pairKey: ByteArray, plaintext: ByteArray, nowMs: Long): ByteArray =
        sealWithNonce(pairKey, plaintext, nowMs, ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) })

    /**
     * Deterministic seam (fixed nonce) for the cross-platform golden vector. Production always uses
     * [seal] and its fresh random nonce.
     */
    internal fun sealWithNonce(pairKey: ByteArray, plaintext: ByteArray, nowMs: Long, nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_LEN) { "nonce must be $NONCE_LEN bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, subKey(pairKey), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC)
        val body = ByteBuffer.allocate(TS_LEN + plaintext.size).putLong(nowMs).put(plaintext).array()
        return MAGIC + nonce + cipher.doFinal(body)
    }

    /**
     * Opens [blob], returning its plaintext, or null when it isn't our format, fails authentication
     * (wrong key or tampering), or falls outside [windowMs]. Callers treat null as "the peer does
     * not hold this key" and abandon the pairing without storing anything.
     */
    fun open(pairKey: ByteArray, blob: ByteArray, nowMs: Long, windowMs: Long = FRESHNESS_WINDOW_MS): ByteArray? {
        if (blob.size < MAGIC.size + NONCE_LEN) return null
        if (!MAGIC.indices.all { blob[it] == MAGIC[it] }) return null
        return try {
            val nonce = blob.copyOfRange(MAGIC.size, MAGIC.size + NONCE_LEN)
            val ct = blob.copyOfRange(MAGIC.size + NONCE_LEN, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, subKey(pairKey), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(MAGIC)
            val body = cipher.doFinal(ct) // throws on auth failure
            if (body.size < TS_LEN) return null
            val ts = ByteBuffer.wrap(body, 0, TS_LEN).long
            if (kotlin.math.abs(nowMs - ts) > windowMs) return null
            body.copyOfRange(TS_LEN, body.size)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Opens [blob] and checks it carries exactly [expected]. Constant-time compare so a wrong
     * confirmation can't be probed byte by byte.
     */
    fun verify(pairKey: ByteArray, blob: ByteArray, expected: ByteArray, nowMs: Long): Boolean {
        val plain = open(pairKey, blob, nowMs) ?: return false
        return java.security.MessageDigest.isEqual(plain, expected)
    }

    private fun subKey(pairKey: ByteArray): SecretKeySpec =
        SecretKeySpec(SessionCrypto.hkdfSha256(pairKey, INFO, 32), "AES")
}
