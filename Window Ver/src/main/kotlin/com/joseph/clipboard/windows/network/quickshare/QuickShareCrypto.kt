package com.joseph.clipboard.windows.network.quickshare

import com.google.protobuf.ByteString
import com.joseph.clipboard.windows.network.quickshare.proto.DeviceToDeviceProto.DeviceToDeviceMessage
import com.joseph.clipboard.windows.network.quickshare.proto.SecureGcmProto
import com.joseph.clipboard.windows.network.quickshare.proto.SecureMessageProto
import java.io.IOException
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The peer broke the protocol or failed authentication; the connection must be dropped. */
class QuickShareProtocolException(message: String) : IOException(message)

/**
 * The primitives under Quick Share's transport: HKDF-SHA256, the UKEY2 and device-to-device key
 * schedule, and the 4-digit PIN both screens show. Pure functions, so they run in host unit tests.
 */
object QuickShareCrypto {
    /** SHA256("D2D"), the salt for the two device-to-device master keys. */
    private val D2D_SALT: ByteArray = sha256("D2D".toByteArray(Charsets.UTF_8))

    /** SHA256("SecureMessage"), the salt SecureMessage uses to split a master key by purpose. */
    private val SECURE_MESSAGE_SALT: ByteArray = sha256("SecureMessage".toByteArray(Charsets.UTF_8))

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /** RFC 5869 HKDF with SHA-256. [salt] must be non-empty (every caller here has one). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "bad HKDF length $length" }
        val prk = hmacSha256(salt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            block = hmacSha256(prk, block, info, byteArrayOf(counter.toByte()))
            val n = minOf(block.size, length - written)
            block.copyInto(out, written, 0, n)
            written += n
            counter++
        }
        return out
    }

    /** What the UKEY2 handshake yields. [authString] feeds the PIN, [nextSecret] the cipher keys. */
    class Ukey2Secrets(val authString: ByteArray, val nextSecret: ByteArray)

    /**
     * UKEY2 key derivation. [sharedX] is the raw 32-byte ECDH output; [clientInit] and
     * [serverInit] are the complete serialized Ukey2Message frames, without length prefixes.
     */
    fun deriveUkey2Secrets(sharedX: ByteArray, clientInit: ByteArray, serverInit: ByteArray): Ukey2Secrets {
        val dhs = sha256(sharedX)
        val info = clientInit + serverInit
        return Ukey2Secrets(
            authString = hkdf(dhs, "UKEY2 v1 auth".toByteArray(Charsets.UTF_8), info, 32),
            nextSecret = hkdf(dhs, "UKEY2 v1 next".toByteArray(Charsets.UTF_8), info, 32),
        )
    }

    /** One direction's AES-256 and HMAC-SHA256 keys. */
    class DirectionKeys(val encrypt: ByteArray, val sign: ByteArray)

    /** The two directions: [client] protects what the sender sends, [server] what we send. */
    class SessionKeys(val client: DirectionKeys, val server: DirectionKeys)

    fun secureMessageKey(masterKey: ByteArray, purpose: String): ByteArray =
        hkdf(masterKey, SECURE_MESSAGE_SALT, purpose.toByteArray(Charsets.UTF_8), 32)

    fun deriveSessionKeys(nextSecret: ByteArray): SessionKeys {
        fun direction(role: String): DirectionKeys {
            val master = hkdf(nextSecret, D2D_SALT, role.toByteArray(Charsets.UTF_8), 32)
            return DirectionKeys(secureMessageKey(master, "ENC:2"), secureMessageKey(master, "SIG:1"))
        }
        return SessionKeys(client = direction("client"), server = direction("server"))
    }

    /** The 4-digit PIN Quick Share shows on both devices, computed over the UKEY2 auth string. */
    fun pinFromAuthString(authString: ByteArray): String {
        var hash = 0
        var multiplier = 1
        for (byte in authString) {
            // Signed bytes and a sign-keeping remainder, matching the reference implementation.
            hash = (hash + byte.toInt() * multiplier) % 9973
            multiplier = (multiplier * 31) % 9973
        }
        return "%04d".format(java.util.Locale.ROOT, kotlin.math.abs(hash))
    }

    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray =
        aesCbc(Cipher.ENCRYPT_MODE, key, iv, plaintext)

    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray =
        aesCbc(Cipher.DECRYPT_MODE, key, iv, ciphertext)

    private fun aesCbc(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        // PKCS5Padding is the JCA name for PKCS#7 on a 16-byte block cipher.
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(input)
    }

    // --- P-256 -------------------------------------------------------------------------------

    fun generateP256KeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1"), random) }
            .generateKeyPair()

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun encodePublicKey(key: ECPublicKey): ByteArray =
        SecureMessageProto.GenericPublicKey.newBuilder()
            .setType(SecureMessageProto.PublicKeyType.EC_P256)
            .setEcP256PublicKey(
                SecureMessageProto.EcP256PublicKey.newBuilder()
                    // toByteArray() is big-endian two's complement, which is the wire encoding.
                    .setX(ByteString.copyFrom(key.w.affineX.toByteArray()))
                    .setY(ByteString.copyFrom(key.w.affineY.toByteArray()))
            )
            .build()
            .toByteArray()

    /** Parses a peer's GenericPublicKey and rejects any point that isn't on P-256. */
    fun decodePublicKey(encoded: ByteArray): ECPublicKey {
        val generic = SecureMessageProto.GenericPublicKey.parseFrom(encoded)
        if (generic.type != SecureMessageProto.PublicKeyType.EC_P256 || !generic.hasEcP256PublicKey()) {
            throw QuickShareProtocolException("peer key is not P-256")
        }
        val xBytes = generic.ecP256PublicKey.x.toByteArray()
        val yBytes = generic.ecP256PublicKey.y.toByteArray()
        // 32 bytes unsigned, or 33 as Java's signed form writes it, with a leading zero.
        fun badLength(bytes: ByteArray) =
            bytes.isEmpty() || bytes.size > 33 || (bytes.size == 33 && bytes[0] != 0.toByte())
        if (badLength(xBytes) || badLength(yBytes)) {
            throw QuickShareProtocolException("peer key has a bad coordinate length")
        }
        val x = BigInteger(1, xBytes)
        val y = BigInteger(1, yBytes)
        val curve = p256.curve
        val prime = (curve.field as ECFieldFp).p
        val inRange = x < prime && y < prime
        if (!inRange || y.multiply(y).mod(prime) != x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(prime)) {
            throw QuickShareProtocolException("peer key is not a point on P-256")
        }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256)) as ECPublicKey
    }

    fun ecdh(own: java.security.PrivateKey, peer: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(own)
            doPhase(peer, true)
            generateSecret()
        }
}

/**
 * The encrypted layer that carries every frame after the handshake: a SecureMessage
 * (AES-256-CBC, then HMAC-SHA256 over header and ciphertext) around a DeviceToDeviceMessage with
 * a per-direction sequence number starting at 1.
 *
 * [seal] and [open] are each synchronized. A caller that seals from several threads must also keep
 * the socket write in the same critical section, or sequence numbers can reach the wire out of order.
 */
class SecureChannel(
    private val sendKeys: QuickShareCrypto.DirectionKeys,
    private val receiveKeys: QuickShareCrypto.DirectionKeys,
    private val random: SecureRandom = SecureRandom(),
) {
    private val sendLock = Any()
    private val receiveLock = Any()
    private var sendSequence = 0
    private var receiveSequence = 0

    fun seal(offlineFrame: ByteArray): ByteArray = synchronized(sendLock) {
        sendSequence++
        val plaintext = DeviceToDeviceMessage.newBuilder()
            .setSequenceNumber(sendSequence)
            .setMessage(ByteString.copyFrom(offlineFrame))
            .build()
            .toByteArray()
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val header = SecureMessageProto.Header.newBuilder()
            .setSignatureScheme(SecureMessageProto.SigScheme.HMAC_SHA256)
            .setEncryptionScheme(SecureMessageProto.EncScheme.AES_256_CBC)
            .setIv(ByteString.copyFrom(iv))
            .setPublicMetadata(
                SecureGcmProto.GcmMetadata.newBuilder()
                    .setType(SecureGcmProto.Type.DEVICE_TO_DEVICE_MESSAGE)
                    .setVersion(1)
                    .build()
                    .toByteString()
            )
        val headerAndBody = SecureMessageProto.HeaderAndBody.newBuilder()
            .setHeader(header)
            .setBody(ByteString.copyFrom(QuickShareCrypto.aesCbcEncrypt(sendKeys.encrypt, iv, plaintext)))
            .build()
            .toByteArray()
        SecureMessageProto.SecureMessage.newBuilder()
            .setHeaderAndBody(ByteString.copyFrom(headerAndBody))
            .setSignature(ByteString.copyFrom(QuickShareCrypto.hmacSha256(sendKeys.sign, headerAndBody)))
            .build()
            .toByteArray()
    }

    /** Verifies, decrypts and sequence-checks one SecureMessage, returning the OfflineFrame bytes. */
    fun open(secureMessage: ByteArray): ByteArray = synchronized(receiveLock) {
        val message = SecureMessageProto.SecureMessage.parseFrom(secureMessage)
        val headerAndBodyBytes = message.headerAndBody.toByteArray()
        // MAC first: nothing inside is parsed or decrypted until it verifies.
        val expected = QuickShareCrypto.hmacSha256(receiveKeys.sign, headerAndBodyBytes)
        if (!MessageDigest.isEqual(expected, message.signature.toByteArray())) {
            throw QuickShareProtocolException("bad message signature")
        }
        val headerAndBody = SecureMessageProto.HeaderAndBody.parseFrom(headerAndBodyBytes)
        val header = headerAndBody.header
        if (header.signatureScheme != SecureMessageProto.SigScheme.HMAC_SHA256 ||
            header.encryptionScheme != SecureMessageProto.EncScheme.AES_256_CBC ||
            header.iv.size() != 16
        ) {
            throw QuickShareProtocolException("unsupported message scheme")
        }
        val plaintext = try {
            QuickShareCrypto.aesCbcDecrypt(receiveKeys.encrypt, header.iv.toByteArray(), headerAndBody.body.toByteArray())
        } catch (e: java.security.GeneralSecurityException) {
            throw QuickShareProtocolException("message did not decrypt")
        }
        val d2d = DeviceToDeviceMessage.parseFrom(plaintext)
        if (d2d.sequenceNumber != receiveSequence + 1) {
            throw QuickShareProtocolException("sequence ${d2d.sequenceNumber}, expected ${receiveSequence + 1}")
        }
        receiveSequence++
        d2d.message.toByteArray()
    }
}
