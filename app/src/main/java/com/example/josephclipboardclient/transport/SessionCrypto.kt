package com.EntropicSoftwareLab.ClipboardShare.transport

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Control-plane frame crypto (§4 of the V4 plan).
 *
 *   frame = type(1) | seq(4, big-endian) | len(2, big-endian) | AES-256-GCM(payload)
 *   key   = HKDF-SHA256(ecdh_secret, info = "ctrl-v1")
 *   nonce = 4-byte direction salt ‖ 8-byte seq
 *
 * The BLE link itself is unauthenticated; this layer carries the same
 * ECDH-derived trust as the TCP handshake. Sequence numbers are strictly
 * monotonic per direction — replayed or reordered frames fail [open].
 *
 * HELLO frames (types 0x01/0x02) are the bootstrap and travel as plaintext
 * bodies in the same framing; everything after them is sealed here.
 */
class SessionCrypto(ecdhSharedKey: ByteArray, isSender: Boolean) {

    private val key: SecretKeySpec
    private val mySalt: ByteArray
    private val peerSalt: ByteArray

    private var txSeq: Long = 1L // 0 is reserved for plaintext HELLO frames
    private var rxHighestSeq: Long = 0L

    init {
        key = SecretKeySpec(hkdfSha256(ecdhSharedKey, INFO_CTRL, KEY_LEN), "AES")
        mySalt = if (isSender) SALT_TX else SALT_RX
        peerSalt = if (isSender) SALT_RX else SALT_TX
    }

    /** Encrypts [payload] into a complete wire frame. Not thread-safe — callers serialize. */
    fun seal(type: Int, payload: ByteArray): ByteArray {
        val seq = txSeq++
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(mySalt, seq)))
        val ct = cipher.doFinal(payload)
        return header(type, seq, ct.size) + ct
    }

    /**
     * Decrypts a sealed frame body. Returns null on GCM failure or a
     * non-monotonic sequence (replay) — the caller drops the frame.
     */
    fun open(type: Int, seq: Long, body: ByteArray): ByteArray? {
        if (seq <= rxHighestSeq) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(peerSalt, seq)))
            val pt = cipher.doFinal(body)
            rxHighestSeq = seq
            pt
        } catch (_: Exception) {
            null
        }
    }

    private fun nonce(salt: ByteArray, seq: Long): ByteArray =
        ByteBuffer.allocate(12).put(salt).putLong(seq).array()

    companion object {
        private const val TAG_BITS = 128
        private const val KEY_LEN = 32
        private val INFO_CTRL = "ctrl-v1".toByteArray(Charsets.UTF_8)

        /** Direction salts: sender→receiver and receiver→sender never share a nonce space. */
        private val SALT_TX = byteArrayOf(0x74, 0x78, 0x00, 0x01) // "tx",0,1
        private val SALT_RX = byteArrayOf(0x72, 0x78, 0x00, 0x01) // "rx",0,1

        /** Plaintext-frame header builder, shared with the HELLO path. */
        fun header(type: Int, seq: Long, len: Int): ByteArray =
            ByteBuffer.allocate(7)
                .put(type.toByte())
                .putInt((seq and 0xFFFFFFFFL).toInt())
                .putShort(len.toShort())
                .array()

        /**
         * RFC 5869 HKDF-SHA256, zero-length salt. O(L) in output length;
         * one extract + ⌈L/32⌉ expand rounds.
         */
        fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
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

        fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(message)
        }
    }
}
