package com.joseph.clipboard.windows.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class EcdhSession {
    private val keyPair: KeyPair

    init {
        // Cross-platform standard curve matching Windows nistP256 and Android
        val kpg = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec("secp256r1")
        kpg.initialize(ecSpec)
        keyPair = kpg.generateKeyPair()
    }

    fun getPublicKey(): ByteArray {
        // Exports in standard X.509 format
        return keyPair.public.encoded
    }

    // [useV2Pin] selects how the out-of-band verification PIN is derived. The AES key is UNCHANGED
    // in both cases — SHA-256(sharedSecret). v1 (default) derives the PIN from the AES key's first
    // 4 bytes, which leaks ~20 bits of the key; v2 derives it from an independent HKDF label so the
    // displayed code no longer leaks key material. v2 is negotiated per-pairing via the "pv2" token
    // (see PairingDaemon / ClipboardAppController); the default stays false so QR/off-grid callers
    // are unaffected.
    fun deriveSecrets(peerPublicKeyBytes: ByteArray, useV2Pin: Boolean = false): Pair<ByteArray, String> {
        val kf = KeyFactory.getInstance("EC")
        val x509Spec = X509EncodedKeySpec(peerPublicKeyBytes)
        val peerPublicKey = kf.generatePublic(x509Spec)

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(keyPair.private)
        keyAgreement.doPhase(peerPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        val digest = MessageDigest.getInstance("SHA-256")
        val aesKey = digest.digest(sharedSecret)

        val visualPin = if (useV2Pin) {
            pinV2FromSecret(sharedSecret)
        } else {
            // v1: 6-digit PIN from the AES key's first 4 bytes, Little Endian (matches C#).
            val buffer = ByteBuffer.wrap(aesKey).order(ByteOrder.LITTLE_ENDIAN)
            val pinInt = buffer.int and 0x7FFFFFFF
            String.format("%06d", pinInt % 1000000)
        }

        return Pair(aesKey, visualPin)
    }

    companion object {
        // UTF-8 label for the v2 PIN HKDF — must be byte-identical to the Android client.
        private val PIN_V2_INFO = "clipshare-pair-pin-v2".toByteArray(Charsets.UTF_8)

        // v2 verification PIN: 6 digits from HKDF-SHA256(sharedSecret, "clipshare-pair-pin-v2", 4),
        // interpreted Little Endian to match the v1/C# convention. Independent of the AES key, so it
        // no longer leaks key bits. Exposed as an internal seam so the host test can pin the value.
        internal fun pinV2FromSecret(sharedSecret: ByteArray): String {
            val pinBytes = hkdfSha256(sharedSecret, PIN_V2_INFO, 4)
            val pinInt = ByteBuffer.wrap(pinBytes).order(ByteOrder.LITTLE_ENDIAN).int and 0x7FFFFFFF
            return String.format("%06d", pinInt % 1000000)
        }

        // RFC 5869 HKDF-SHA256, zero-length salt — byte-for-byte identical to Android
        // SessionCrypto.hkdfSha256 and the private FileSeal/ClipboardSeal helpers. Replicated here
        // (rather than reused) to keep FileSeal/ClipboardSeal untouched.
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
}

object CryptoEngine {
    private const val IV_SIZE = 16

    // Validate AES key length up front (mirrors Android CryptoEngine.requireValidAesKey, H-26). A
    // wrong-size key otherwise surfaces as an opaque InvalidKeyException deep inside cipher.init;
    // failing fast here gives a clear message instead. This app uses 32-byte keys.
    private fun requireValidAesKey(key: ByteArray) {
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "Invalid AES key length: ${key.size} bytes (expected 16, 24, or 32)"
        }
    }

    fun generateRandomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    // getEncryptingStream/getDecryptingStream (unauthenticated AES/CBC stream wrappers) were
    // removed in 3.5.1 (XP-DEF-07, mirroring the Android CryptoEngine): zero callers since
    // FileSeal became the only bulk cipher, and a live CBC stream primitive invited silent
    // reintroduction onto the transfer path.

    // Authenticated discrete payload (AES-256-GCM via ClipboardSeal) — the only clipboard cipher
    // since the CBC retirement in 3.5.0. Output is standard Base64 of the sealed blob, matching
    // Android's encryptStringAuthenticated (android.util.Base64.NO_WRAP).
    fun encryptStringAuthenticated(plainText: String, key: ByteArray): String {
        requireValidAesKey(key)
        val blob = ClipboardSeal.seal(key, plainText.toByteArray(Charsets.UTF_8), System.currentTimeMillis())
        return Base64.getEncoder().encodeToString(blob)
    }

    // RETIRED in 3.5.0: the unauthenticated AES/CBC clipboard path is no longer sent or accepted.
    // Kept only so the primitive isn't silently reintroduced; ERROR so any new call site won't compile.
    @Deprecated(
        "Unauthenticated CBC clipboard was retired in 3.5.0 — use encryptStringAuthenticated.",
        level = DeprecationLevel.ERROR
    )
    fun encryptString(plainText: String, key: ByteArray): String {
        requireValidAesKey(key)
        val iv = generateRandomBytes(IV_SIZE)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKeySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)

        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    // Receive side of the 3.5.0 CBC retirement: only sealed (AES-GCM) payloads are accepted; any
    // non-sealed payload is refused outright — no pin check, because there is no legacy branch left
    // to fall back to. [senderIp] only names the peer in the rejection error.
    fun decryptString(cipherTextBase64: String, key: ByteArray, senderIp: String? = null): String {
        requireValidAesKey(key)
        val cleanBase64 = cipherTextBase64.replace(Regex("\\s+"), "")
        // Validate before slicing: a hostile/truncated payload would otherwise throw an
        // opaque NegativeArraySizeException (combined.size - IV_SIZE < 0) or an
        // IllegalArgumentException from the Base64 decoder. Fail fast with a clear message.
        val combined = try {
            Base64.getDecoder().decode(cleanBase64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed Base64 clipboard payload", e)
        }

        // Authenticated GCM format (new peers): a self-describing magic marks it. If present, the
        // payload MUST verify here — never silently fall through to the unauthenticated CBC path.
        if (ClipboardSeal.isSealed(combined)) {
            val plain = ClipboardSeal.open(key, combined, System.currentTimeMillis())
                ?: throw IllegalArgumentException("Authenticated clipboard payload failed verification")
            return String(plain, Charsets.UTF_8)
        }

        // The unauthenticated AES/CBC clipboard path was retired in 3.5.0. Any non-sealed payload is a
        // pre-GCM peer (which must update) or a tamper/downgrade attempt — reject it, never decrypt CBC.
        throw IllegalArgumentException("Unauthenticated (non-GCM) clipboard from ${senderIp ?: "peer"} rejected — CBC path retired in 3.5.0")
    }
}
