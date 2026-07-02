package com.EntropicSoftwareLab.ClipboardShare.transport

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated bulk-file-stream crypto for the LAN file path — the AEAD that the (dead) AES/CBC
 * [com.EntropicSoftwareLab.ClipboardShare.CryptoEngine] stream helpers never actually provided.
 * Each file is sealed as a sequence of independent AES-256-GCM records:
 *
 *   record = [u32be ctLen][12B nonce][ct]              ct = GCM(chunk) incl the 16-byte tag
 *   chunk  = up to [CHUNK_SIZE] plaintext bytes, indexed 0,1,2,… per file
 *   key    = HKDF-SHA256(pairKey, info "file-aead-v1") (separated from clipboard + control keys)
 *   AAD    = u64be(chunkIndex)                         (binds position → truncation/reorder rejected)
 *
 * The 12-byte nonce is random per record (carried in the record), so there is no IV-reuse concern.
 * The reader knows each file's plaintext length from the manifest and stops once it has that many
 * bytes, so the stream needs no end marker. Pure JVM (no Android APIs) → host-unit-testable, and the
 * framing is pinned by a cross-platform golden vector ([FileSealVectorTest]) so the Android and
 * Windows ports stay byte-identical. Only used between paired peers that both advertise the FGCM1
 * capability; legacy peers keep the plaintext stream for back-compat.
 */
object FileSeal {

    /** 64 KiB plaintext per record — matches the existing TRANSFER_BUFFER_SIZE cadence. */
    const val CHUNK_SIZE = 64 * 1024
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val LEN_PREFIX = 4
    private val INFO = "file-aead-v1".toByteArray(Charsets.UTF_8)
    private val INFO_V2 = "file-aead-v2".toByteArray(Charsets.UTF_8)

    /** Per-file/stream sub-key derived once from the shared pairing key. */
    fun deriveKey(pairKey: ByteArray): ByteArray = SessionCrypto.hkdfSha256(pairKey, INFO, 32)

    /**
     * v2 per-session sub-key: HKDF-SHA256(pairKey, "file-aead-v2" || sessionId, 32). Distinct per
     * transfer session, so a record authentic under one session's key can't be replayed into another.
     */
    fun deriveKeyV2(pairKey: ByteArray, sessionId: String): ByteArray =
        SessionCrypto.hkdfSha256(pairKey, INFO_V2 + sessionId.toByteArray(Charsets.UTF_8), 32)

    /**
     * v2 AAD prefix binding session + file identity: sessionIdBytes || u64be(fileIndex). Combined with
     * the per-chunk suffix the full v2 AAD is sessionIdBytes || u64be(fileIndex) || u64be(chunkIndex),
     * so a record can no longer be spliced across files or transfers.
     */
    fun v2AadPrefix(sessionId: String, fileIndex: Long): ByteArray =
        sessionId.toByteArray(Charsets.UTF_8) + java.nio.ByteBuffer.allocate(8).putLong(fileIndex).array()

    /**
     * Seals exactly [plainLen] bytes pulled from [input] into [output] as GCM records. [subKey] is
     * the derived key from [deriveKey]; [startChunkIndex] is 0 for a fresh file (the only value the
     * current resume policy uses, since AEAD resume restarts the in-progress file).
     */
    fun sealStream(subKey: ByteArray, input: InputStream, plainLen: Long, output: OutputStream, startChunkIndex: Long = 0L, aadPrefix: ByteArray = ByteArray(0)) {
        val keySpec = SecretKeySpec(subKey, "AES")
        val buf = ByteArray(CHUNK_SIZE)
        var remaining = plainLen
        var chunkIndex = startChunkIndex
        while (remaining > 0) {
            val want = if (remaining > CHUNK_SIZE) CHUNK_SIZE else remaining.toInt()
            var filled = 0
            while (filled < want) {
                val r = input.read(buf, filled, want - filled)
                if (r == -1) throw java.io.IOException("Source ended early: wanted $want at chunk $chunkIndex")
                filled += r
            }
            output.write(sealChunk(keySpec, buf, filled, chunkIndex, aadPrefix = aadPrefix))
            remaining -= filled
            chunkIndex++
        }
        output.flush()
    }

    /**
     * Reads and decrypts [plainLen] bytes worth of GCM records from [input], writing the recovered
     * plaintext to [output]. Throws on authentication failure, truncation, or a malformed frame.
     */
    fun openStream(subKey: ByteArray, input: InputStream, plainLen: Long, output: OutputStream, startChunkIndex: Long = 0L, aadPrefix: ByteArray = ByteArray(0)) {
        val keySpec = SecretKeySpec(subKey, "AES")
        var remaining = plainLen
        var chunkIndex = startChunkIndex
        while (remaining > 0) {
            val pt = openChunk(keySpec, input, chunkIndex, remaining, aadPrefix)
            output.write(pt)
            remaining -= pt.size
            chunkIndex++
        }
        output.flush()
    }

    /**
     * Reads and decrypts ONE record from [input] for [chunkIndex], returning its plaintext. Used by
     * the receiver's per-chunk download loop so it can update progress and check cancellation between
     * chunks. [maxPlain] is the remaining expected plaintext (rejects an over-long chunk). Throws on
     * authentication failure / truncation / a malformed frame.
     */
    internal fun openChunk(keySpec: SecretKeySpec, input: InputStream, chunkIndex: Long, maxPlain: Long, aadPrefix: ByteArray = ByteArray(0)): ByteArray {
        val ctLen = readU32(input)
        // A valid record is one tag plus 1..CHUNK_SIZE plaintext bytes; reject anything else before
        // allocating, so a hostile/garbled length can't drive a huge allocation.
        if (ctLen <= TAG_BYTES || ctLen > CHUNK_SIZE + TAG_BYTES) throw java.io.IOException("Bad record length $ctLen")
        val nonce = readFully(input, NONCE_LEN)
        val ct = readFully(input, ctLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(chunkIndex, aadPrefix))
        val pt = cipher.doFinal(ct) // throws AEADBadTagException on tamper / wrong key / wrong index
        if (pt.isEmpty() || pt.size.toLong() > maxPlain) throw java.io.IOException("Chunk plaintext overflow at $chunkIndex")
        return pt
    }

    /** SecretKeySpec wrapper so callers outside this class need not import javax.crypto. */
    internal fun keySpec(subKey: ByteArray): SecretKeySpec = SecretKeySpec(subKey, "AES")

    /** Seals one chunk into a complete record. Deterministic when [nonce] is supplied (golden test). */
    internal fun sealChunk(
        keySpec: SecretKeySpec,
        plain: ByteArray,
        len: Int,
        chunkIndex: Long,
        nonce: ByteArray = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) },
        aadPrefix: ByteArray = ByteArray(0),
    ): ByteArray {
        require(nonce.size == NONCE_LEN) { "nonce must be $NONCE_LEN bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(chunkIndex, aadPrefix))
        val ct = cipher.doFinal(plain, 0, len)
        return ByteBuffer.allocate(LEN_PREFIX + NONCE_LEN + ct.size)
            .putInt(ct.size).put(nonce).put(ct).array()
    }

    // aadPrefix defaults to empty ⇒ v1 AAD is exactly u64be(chunkIndex), byte-identical to the
    // original scheme. v2 supplies a non-empty prefix (sessionId || u64be(fileIndex)) via v2AadPrefix.
    private fun aad(chunkIndex: Long, aadPrefix: ByteArray): ByteArray =
        aadPrefix + ByteBuffer.allocate(8).putLong(chunkIndex).array()

    private fun readU32(input: InputStream): Int {
        val b = readFully(input, LEN_PREFIX)
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r == -1) throw java.io.IOException("Stream ended: wanted $n, got $off")
            off += r
        }
        return out
    }
}
