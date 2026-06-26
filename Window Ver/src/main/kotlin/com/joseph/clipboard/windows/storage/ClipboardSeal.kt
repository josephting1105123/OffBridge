package com.joseph.clipboard.windows.storage

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Windows port of the Android `transport.ClipboardSeal` — authenticated (AES-256-GCM) replacement
 * for the legacy unauthenticated AES/CBC clipboard blobs. This MUST stay byte-identical to the
 * Android version (same MAGIC, HKDF info, nonce layout, AAD, timestamp encoding); the shared golden
 * vector in `ClipboardSealVectorTest` fails if either side drifts.
 *
 *   wire = MAGIC(4) | nonce(12) | AES-256-GCM(ts(8, big-endian millis) | plaintext)
 *   key  = HKDF-SHA256(pair_key, info = "clip-aead-v1")
 *   AAD  = MAGIC
 *
 * Only sent to peers that advertise the GCM capability in discovery, so legacy Android and older
 * Windows builds (which speak AES/CBC) are never handed this format.
 */
object ClipboardSeal {
    private val MAGIC = byteArrayOf(0x43, 0x53, 0x31, 0x00) // "CS1 "
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val TS_LEN = 8
    private val INFO = "clip-aead-v1".toByteArray(Charsets.UTF_8)

    /** Replay/staleness bound; generous to tolerate device clock skew. */
    const val FRESHNESS_WINDOW_MS = 600_000L // 10 minutes

    fun isSealed(blob: ByteArray): Boolean =
        blob.size >= MAGIC.size && MAGIC.indices.all { blob[it] == MAGIC[it] }

    fun seal(pairKey: ByteArray, plaintext: ByteArray, nowMs: Long): ByteArray =
        sealWithNonce(pairKey, plaintext, nowMs, ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) })

    /** Deterministic seam (fixed nonce) for the cross-platform golden-vector test only. */
    internal fun sealWithNonce(pairKey: ByteArray, plaintext: ByteArray, nowMs: Long, nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_LEN) { "nonce must be $NONCE_LEN bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, subKey(pairKey), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC)
        val body = ByteBuffer.allocate(TS_LEN + plaintext.size).putLong(nowMs).put(plaintext).array()
        val ct = cipher.doFinal(body)
        return MAGIC + nonce + ct
    }

    fun open(pairKey: ByteArray, blob: ByteArray, nowMs: Long, windowMs: Long = FRESHNESS_WINDOW_MS): ByteArray? {
        if (!isSealed(blob) || blob.size < MAGIC.size + NONCE_LEN) return null
        return try {
            val nonce = blob.copyOfRange(MAGIC.size, MAGIC.size + NONCE_LEN)
            val ct = blob.copyOfRange(MAGIC.size + NONCE_LEN, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, subKey(pairKey), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(MAGIC)
            val body = cipher.doFinal(ct) // throws on auth failure
            if (body.size < TS_LEN) return null
            val ts = ByteBuffer.wrap(body, 0, TS_LEN).long
            if (abs(nowMs - ts) > windowMs) return null // stale / replayed
            body.copyOfRange(TS_LEN, body.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun subKey(pairKey: ByteArray): SecretKeySpec =
        SecretKeySpec(hkdfSha256(pairKey, INFO, 32), "AES")

    /** RFC 5869 HKDF-SHA256, zero-length salt — identical to Android SessionCrypto.hkdfSha256. */
    private fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256")) // salt = zeros
        val prk = mac.doFinal(ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var copied = 0
        var counter = 1
        while (copied < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - copied)
            System.arraycopy(t, 0, out, copied, n)
            copied += n
            counter++
        }
        return out
    }
}
