package com.joseph.clipboard.windows.storage

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
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyAgreement
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

    fun deriveSecrets(peerPublicKeyBytes: ByteArray): Pair<ByteArray, String> {
        val kf = KeyFactory.getInstance("EC")
        val x509Spec = X509EncodedKeySpec(peerPublicKeyBytes)
        val peerPublicKey = kf.generatePublic(x509Spec)

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(keyPair.private)
        keyAgreement.doPhase(peerPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        val digest = MessageDigest.getInstance("SHA-256")
        val aesKey = digest.digest(sharedSecret)

        // Extract the exact same 6-digit visual PIN, matching C#'s Little Endian format
        val buffer = ByteBuffer.wrap(aesKey).order(ByteOrder.LITTLE_ENDIAN)
        val pinInt = buffer.int and 0x7FFFFFFF
        val visualPin = String.format("%06d", pinInt % 1000000)

        return Pair(aesKey, visualPin)
    }
}

object CryptoEngine {
    private const val IV_SIZE = 16

    fun generateRandomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    fun getEncryptingStream(outStream: OutputStream, key: ByteArray, iv: ByteArray): CipherOutputStream {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKeySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)

        return CipherOutputStream(outStream, cipher)
    }

    fun getDecryptingStream(inStream: InputStream, key: ByteArray, iv: ByteArray): CipherInputStream {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKeySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)

        return CipherInputStream(inStream, cipher)
    }

    // Authenticated discrete payload (AES-256-GCM via ClipboardSeal) — preferred for clipboard text
    // with peers that advertise the GCM capability. Output is standard Base64 of the sealed blob,
    // matching Android's encryptStringAuthenticated (android.util.Base64.NO_WRAP).
    fun encryptStringAuthenticated(plainText: String, key: ByteArray): String {
        val blob = ClipboardSeal.seal(key, plainText.toByteArray(Charsets.UTF_8), System.currentTimeMillis())
        return Base64.getEncoder().encodeToString(blob)
    }

    fun encryptString(plainText: String, key: ByteArray): String {
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

    fun decryptString(cipherTextBase64: String, key: ByteArray): String {
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

        // Legacy unauthenticated AES/CBC format (older Android + Windows builds).
        require(combined.size > IV_SIZE) { "Ciphertext too short: ${combined.size} bytes" }

        val iv = ByteArray(IV_SIZE)
        val encryptedBytes = ByteArray(combined.size - IV_SIZE)

        System.arraycopy(combined, 0, iv, 0, IV_SIZE)
        System.arraycopy(combined, IV_SIZE, encryptedBytes, 0, encryptedBytes.size)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKeySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)

        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
