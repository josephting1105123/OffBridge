package com.joseph.clipboard.windows.storage

import java.nio.ByteBuffer
import java.security.MessageDigest
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
 * Since the CBC retirement in 3.5.0 this is the only clipboard format sent or accepted — even peers
 * that never advertised the GCM capability receive it, and must update to open it.
 */
object ClipboardSeal {
    private val MAGIC = byteArrayOf(0x43, 0x53, 0x31, 0x00) // "CS1 "
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val TS_LEN = 8
    private val INFO = "clip-aead-v1".toByteArray(Charsets.UTF_8)

    /** Replay/staleness bound; generous to tolerate device clock skew. The per-peer nonce cache below tightens it. */
    const val FRESHNESS_WINDOW_MS = 600_000L // 10 minutes

    /** Cap on remembered nonces per peer — bounds memory for a hostile/chatty sender. */
    private const val MAX_NONCES_PER_PEER = 512

    /**
     * Receiver-local replay guard: nonces of blobs already accepted from a given peer, with the time
     * they were seen. A random 12-byte nonce is effectively unique per legitimate message, so a repeat
     * within the freshness window is a replay and is rejected. This is purely local state — it does not
     * touch the wire format or the meaning of [FRESHNESS_WINDOW_MS]. Keyed by a fingerprint of the pair
     * key so raw key bytes are not held as map keys. Guarded by [seenLock]; entries older than the
     * freshness window are pruned lazily since they can no longer be replayed anyway. Mirrors the
     * Android ClipboardSeal nonce cache.
     */
    private val seenNonces = HashMap<String, LinkedHashMap<String, Long>>()
    private val seenLock = Any()

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
            // Authenticated + fresh: reject if this exact nonce was already accepted from this peer
            // inside the window (a straight replay of a still-fresh, still-valid blob).
            if (!recordNonce(peerKey(pairKey), nonce, nowMs, windowMs)) return null
            body.copyOfRange(TS_LEN, body.size)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Records [nonce] as seen for [peer] at [nowMs]. Returns false if it was already seen within
     * [windowMs] (a replay). Prunes stale entries and bounds the per-peer set to [MAX_NONCES_PER_PEER].
     */
    private fun recordNonce(peer: String, nonce: ByteArray, nowMs: Long, windowMs: Long): Boolean {
        val key = nonce.joinToString("") { "%02x".format(it) }
        synchronized(seenLock) {
            val forPeer = seenNonces.getOrPut(peer) {
                object : LinkedHashMap<String, Long>(64, 0.75f, false) {
                    override fun removeEldestEntry(eldest: Map.Entry<String, Long>): Boolean =
                        size > MAX_NONCES_PER_PEER
                }
            }
            // Drop entries that have aged out of the window; they can no longer be replayed.
            forPeer.entries.iterator().let { it ->
                while (it.hasNext()) {
                    if (abs(nowMs - it.next().value) > windowMs) it.remove()
                }
            }
            val prev = forPeer[key]
            if (prev != null && abs(nowMs - prev) <= windowMs) return false
            forPeer[key] = nowMs
            return true
        }
    }

    /** Stable, non-reversible handle for a peer's pair key, used only as a local cache key. */
    private fun peerKey(pairKey: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(pairKey)
        return h.copyOfRange(0, 16).joinToString("") { "%02x".format(it) }
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
