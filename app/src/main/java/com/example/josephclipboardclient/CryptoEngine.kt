package com.EntropicSoftwareLab.ClipboardShare

import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// 1. The Elliptic-Curve Session Manager
class EcdhSession {
    private val keyPair: KeyPair

    init {
        // Cross-platform standard curve matching Windows nistP256
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
    // (see PairingDaemon); the default stays false so QR/off-grid callers are unaffected.
    fun deriveSecrets(peerPublicKeyBytes: ByteArray, useV2Pin: Boolean = false): Pair<ByteArray, String> {
        val kf = KeyFactory.getInstance("EC")

        // M-09: A malformed or off-curve peer key makes generatePublic/doPhase throw raw
        // InvalidKeySpecException / InvalidKeyException, crashing the pairing flow. Wrap the
        // peer-key parse + key agreement and rethrow a clear IllegalArgumentException (still a
        // RuntimeException, so existing callers can catch it) so a bad peer key is a handled failure.
        val sharedSecret = try {
            val x509Spec = X509EncodedKeySpec(peerPublicKeyBytes)
            val peerPublicKey = kf.generatePublic(x509Spec)

            val keyAgreement = KeyAgreement.getInstance("ECDH")
            keyAgreement.init(keyPair.private)
            keyAgreement.doPhase(peerPublicKey, true)
            keyAgreement.generateSecret()
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("Invalid or off-curve peer public key", e)
        }

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
        // UTF-8 label for the v2 PIN HKDF — must be byte-identical to the Windows client.
        private val PIN_V2_INFO = "clipshare-pair-pin-v2".toByteArray(Charsets.UTF_8)

        // v2 verification PIN: 6 digits from HKDF-SHA256(sharedSecret, "clipshare-pair-pin-v2", 4),
        // interpreted Little Endian to match the v1/C# convention. Independent of the AES key, so it
        // no longer leaks key bits. Exposed as an internal seam so the host test can pin the value.
        internal fun pinV2FromSecret(sharedSecret: ByteArray): String {
            val pinBytes = com.EntropicSoftwareLab.ClipboardShare.transport.SessionCrypto
                .hkdfSha256(sharedSecret, PIN_V2_INFO, 4)
            val pinInt = ByteBuffer.wrap(pinBytes).order(ByteOrder.LITTLE_ENDIAN).int and 0x7FFFFFFF
            return String.format("%06d", pinInt % 1000000)
        }
    }
}

object CryptoEngine {
    private const val IV_SIZE = 16

    // H-26: Validate AES key length up front. A wrong-size key (anything other than
    // 16/24/32 bytes) otherwise produces an opaque InvalidKeyException deep inside
    // cipher.init; failing fast here gives a clear message. This app uses 32-byte keys.
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

    // 2. O(1) Stream Wrapper for Outbound File Transfers
    fun getEncryptingStream(outStream: OutputStream, key: ByteArray, iv: ByteArray): CipherOutputStream {
        requireValidAesKey(key) // H-26: fail fast on bad key size before SecretKeySpec/cipher.init
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKeySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)

        return CipherOutputStream(outStream, cipher)
    }

    // 3. O(1) Stream Wrapper for Inbound File Transfers
    fun getDecryptingStream(inStream: InputStream, key: ByteArray, iv: ByteArray): CipherInputStream {
        requireValidAesKey(key) // H-26: fail fast on bad key size before SecretKeySpec/cipher.init
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKeySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)

        return CipherInputStream(inStream, cipher)
    }

    // 4a. Authenticated discrete payload (AES-256-GCM via ClipboardSeal). Preferred for clipboard
    // text/commands with peers that advertise the GCM capability; gives integrity + replay bounding
    // that the legacy AES/CBC encryptString below cannot. Output is Base64 of the sealed blob.
    fun encryptStringAuthenticated(plainText: String, key: ByteArray): String {
        requireValidAesKey(key)
        val blob = com.EntropicSoftwareLab.ClipboardShare.transport.ClipboardSeal.seal(
            key, plainText.toByteArray(Charsets.UTF_8), System.currentTimeMillis()
        )
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    // 4. Discrete Payload Encryption (For Clipboard Text) — RETIRED in 3.5.0.
    // The unauthenticated AES/CBC clipboard path is no longer sent or accepted; kept only so the
    // primitive isn't silently reintroduced. Marked ERROR so any new call site fails to compile.
    @Deprecated(
        "Unauthenticated CBC clipboard was retired in 3.5.0 — use encryptStringAuthenticated.",
        level = DeprecationLevel.ERROR
    )
    fun encryptString(plainText: String, key: ByteArray): String {
        requireValidAesKey(key) // H-26: fail fast on bad key size before SecretKeySpec/cipher.init
        val iv = generateRandomBytes(IV_SIZE)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKeySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)

        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptString(cipherTextBase64: String, key: ByteArray): String {
        requireValidAesKey(key) // H-26: fail fast on bad key size before SecretKeySpec/cipher.init
        // Validate before slicing: a hostile/truncated payload would otherwise throw an
        // opaque NegativeArraySizeException (combined.size - IV_SIZE < 0) or an
        // IllegalArgumentException from Base64.decode. Fail fast with a clear message so
        // the caller's catch can drop the frame instead of risking an unhandled crash.
        val combined = try {
            Base64.decode(cipherTextBase64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed Base64 clipboard payload", e)
        }

        // Authenticated GCM format (new peers): a self-describing magic marks it. If present, the
        // payload MUST verify here — never silently fall through to the unauthenticated CBC path,
        // which would defeat the integrity guarantee for a tampered/stale sealed blob.
        if (com.EntropicSoftwareLab.ClipboardShare.transport.ClipboardSeal.isSealed(combined)) {
            val plain = com.EntropicSoftwareLab.ClipboardShare.transport.ClipboardSeal.open(
                key, combined, System.currentTimeMillis()
            ) ?: throw IllegalArgumentException("Authenticated clipboard payload failed verification")
            return String(plain, Charsets.UTF_8)
        }

        // The unauthenticated AES/CBC clipboard path was retired in 3.5.0. A non-sealed payload is a
        // pre-GCM peer (which must update) or a tamper/downgrade attempt — reject it, never decrypt CBC.
        throw IllegalArgumentException("Unauthenticated (non-GCM) clipboard payload rejected — CBC path retired in 3.5.0")
    }
}