package com.joseph.clipboard.windows.storage

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Windows port of the Android `transport.FileSeal` — authenticated (AES-256-GCM) bulk-file-stream
 * crypto for the LAN file path. MUST stay byte-identical to the Android version (same HKDF info,
 * record framing, AAD = chunkIndex, nonce layout); the shared golden vector in `FileSealVectorTest`
 * fails if either side drifts.
 *
 *   record = [u32be ctLen][12B nonce][ct]              ct = GCM(chunk) incl the 16-byte tag
 *   chunk  = up to [CHUNK_SIZE] plaintext bytes, indexed 0,1,2,… per file
 *   key    = HKDF-SHA256(pairKey, info "file-aead-v1")
 *   AAD    = u64be(chunkIndex)                         (binds position → truncation/reorder rejected)
 *
 * Only used between paired peers that both advertise the FGCM1 capability; legacy peers keep the
 * plaintext stream for back-compat.
 */
object FileSeal {

    const val CHUNK_SIZE = 64 * 1024
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val LEN_PREFIX = 4
    private val INFO = "file-aead-v1".toByteArray(Charsets.UTF_8)

    fun deriveKey(pairKey: ByteArray): ByteArray = hkdfSha256(pairKey, INFO, 32)

    fun sealStream(subKey: ByteArray, input: InputStream, plainLen: Long, output: OutputStream, startChunkIndex: Long = 0L) {
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
            output.write(sealChunk(keySpec, buf, filled, chunkIndex))
            remaining -= filled
            chunkIndex++
        }
        output.flush()
    }

    fun openStream(subKey: ByteArray, input: InputStream, plainLen: Long, output: OutputStream, startChunkIndex: Long = 0L) {
        val keySpec = SecretKeySpec(subKey, "AES")
        var remaining = plainLen
        var chunkIndex = startChunkIndex
        while (remaining > 0) {
            val pt = openChunk(keySpec, input, chunkIndex, remaining)
            output.write(pt)
            remaining -= pt.size
            chunkIndex++
        }
        output.flush()
    }

    /** Reads + decrypts ONE record for [chunkIndex]; lets the caller update progress per chunk. */
    fun openChunk(keySpec: SecretKeySpec, input: InputStream, chunkIndex: Long, maxPlain: Long): ByteArray {
        val ctLen = readU32(input)
        if (ctLen <= TAG_BYTES || ctLen > CHUNK_SIZE + TAG_BYTES) throw java.io.IOException("Bad record length $ctLen")
        val nonce = readFully(input, NONCE_LEN)
        val ct = readFully(input, ctLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(chunkIndex))
        val pt = cipher.doFinal(ct) // throws on tamper / wrong key / wrong index
        if (pt.isEmpty() || pt.size.toLong() > maxPlain) throw java.io.IOException("Chunk plaintext overflow at $chunkIndex")
        return pt
    }

    fun keySpec(subKey: ByteArray): SecretKeySpec = SecretKeySpec(subKey, "AES")

    fun sealChunk(
        keySpec: SecretKeySpec,
        plain: ByteArray,
        len: Int,
        chunkIndex: Long,
        nonce: ByteArray = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) },
    ): ByteArray {
        require(nonce.size == NONCE_LEN) { "nonce must be $NONCE_LEN bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(chunkIndex))
        val ct = cipher.doFinal(plain, 0, len)
        return ByteBuffer.allocate(LEN_PREFIX + NONCE_LEN + ct.size)
            .putInt(ct.size).put(nonce).put(ct).array()
    }

    private fun aad(chunkIndex: Long): ByteArray = ByteBuffer.allocate(8).putLong(chunkIndex).array()

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
