package com.joseph.clipboard.windows.network.quickshare

import com.google.protobuf.ByteString
import com.joseph.clipboard.windows.network.quickshare.proto.SecureMessageProto
import com.joseph.clipboard.windows.network.quickshare.proto.UkeyProto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPublicKey

class QuickShareCryptoTest {

    // --- published vectors -------------------------------------------------------------------

    /** RFC 5869, test case 1. */
    @Test
    fun hkdfMatchesRfc5869() {
        val okm = QuickShareCrypto.hkdf(
            ikm = ByteArray(22) { 0x0b },
            salt = ByteArray(13) { it.toByte() },
            info = ByteArray(10) { (0xf0 + it).toByte() },
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.hex(),
        )
    }

    /** google/ukey2, src/securemessage/test/securemessage/crypto_ops_test.cc: the "SIG:1" key split. */
    @Test
    fun signingKeyMatchesUkey2HmacVector() {
        val key = QuickShareCrypto.secureMessageKey(ByteArray(20) { 0x0b }, "SIG:1")
        assertEquals(
            "3b147b0fe66a0047a2604cf26429ad075d868b01db11ef6f4ec22d8bdb66f18c",
            QuickShareCrypto.hmacSha256(key, "Hi There".toByteArray()).hex(),
        )
    }

    /**
     * google/ukey2, SecureMessageSimpleTestVectorTest TEST_VECTOR_HMAC_AND_AES_SAME_KEYS: a message
     * made by Google's SecureMessage library, which our proto definitions, "ENC:2"/"SIG:1" key
     * split, HMAC coverage and AES-CBC must all open.
     */
    @Test
    fun opensGooglesSecureMessageVector() {
        val masterKey = bytes(
            -89, 105, 62, -41, -75, 78, 70, 110, -62, -58, -80, -81, -99, -62, 39, 38, 37, -7, -112, -83, 81, 23,
            125, -72, -100, 103, -34, -23, -68, 21, -46, -104,
        )
        val vector = bytes(
            10, 91, 10, 55, 8, 1, 16, 2, 26, 3, 0, 0, 1, 34, 3, -1, -1, 0, 42, 16, -110, 48, 67, 67, -31, 24,
            -42, 13, -44, -109, 6, 113, 34, -70, 121, 6, 50, 19, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 56, 5, 18, 32, -44, -102, -16, 123, 113, -75, 88, -33, 118, 25,
            60, -65, 109, 26, -70, -123, 58, -114, 126, 8, 106, -28, 65, -38, -4, 68, -78, -91, 49, -13, 22,
            -122, 18, 32, 20, -120, -113, -76, 85, -35, -53, 37, -18, 66, -38, 32, 10, 30, 89, 112, -39, -27,
            24, 93, -36, -100, -127, -79, 94, -7, -19, -41, -47, -29, 1, 12,
        )
        val message = SecureMessageProto.SecureMessage.parseFrom(vector)
        val headerAndBody = SecureMessageProto.HeaderAndBody.parseFrom(message.headerAndBody)
        val associatedData = bytes(11, 22, 33, 44, 55)

        assertEquals(SecureMessageProto.SigScheme.HMAC_SHA256, headerAndBody.header.signatureScheme)
        assertEquals(SecureMessageProto.EncScheme.AES_256_CBC, headerAndBody.header.encryptionScheme)
        assertArrayEquals(
            message.signature.toByteArray(),
            QuickShareCrypto.hmacSha256(
                QuickShareCrypto.secureMessageKey(masterKey, "SIG:1"),
                message.headerAndBody.toByteArray(),
                associatedData,
            ),
        )
        assertArrayEquals(
            bytes(0, 99, 1, 98, 2, 97, 3, 96, 4, 95, 5, 94, 6, 93, 7, 92, 8, 91, 9, 90),
            QuickShareCrypto.aesCbcDecrypt(
                QuickShareCrypto.secureMessageKey(masterKey, "ENC:2"),
                headerAndBody.header.iv.toByteArray(),
                headerAndBody.body.toByteArray(),
            ),
        )
    }

    /**
     * No full UKEY2 handshake vector is published, so these were computed separately with Node's
     * crypto module from the key schedule in the google/ukey2 README: shared x = 00..1f and the two
     * handshake frames replaced by fixed strings.
     */
    @Test
    fun keyScheduleMatchesIndependentComputation() {
        val secrets = QuickShareCrypto.deriveUkey2Secrets(
            ByteArray(32) { it.toByte() },
            "client-init-frame".toByteArray(),
            "server-init-frame".toByteArray(),
        )
        assertEquals("cfea1734d57c8c0440b4079307c807ee62df6ab9b4c2a59c84c29875feb2c0fd", secrets.authString.hex())
        assertEquals("fd0f70673064ad1e9f08199e8347890c860f24c3729b7fa1836e69c4fcf5ccfe", secrets.nextSecret.hex())

        val keys = QuickShareCrypto.deriveSessionKeys(secrets.nextSecret)
        assertEquals("5ad75f999bcd1bd31358c893d24321f0ecdc55932c62b879d6fab7d584650a84", keys.client.encrypt.hex())
        assertEquals("4834d9bb495466256cb397270021424dd5d125d6d1b838a55a1e0b8961307dce", keys.client.sign.hex())
        assertEquals("f3be95c6ad129a962adb744365449edc148bee6ec52ea7c867bae6e6f8ba7387", keys.server.encrypt.hex())
        assertEquals("32a5e51eb1f35ad3e3e871a22733f3c951ad0bce8a77a38ee07aca420071ac20", keys.server.sign.hex())
        assertEquals("6813", QuickShareCrypto.pinFromAuthString(secrets.authString))
    }

    @Test
    fun pinTreatsBytesAsSignedAndPadsToFourDigits() {
        assertEquals("5095", QuickShareCrypto.pinFromAuthString(ByteArray(32) { it.toByte() }))
        assertEquals("6509", QuickShareCrypto.pinFromAuthString(ByteArray(32) { 0xff.toByte() }))
        assertEquals("0007", QuickShareCrypto.pinFromAuthString(byteArrayOf(7)))
    }

    // --- P-256 keys --------------------------------------------------------------------------

    @Test
    fun publicKeysRoundTripAndAgreeOnASecret() {
        repeat(20) { // coordinates with and without the leading sign byte both occur
            val a = QuickShareCrypto.generateP256KeyPair()
            val b = QuickShareCrypto.generateP256KeyPair()
            val aPublic = QuickShareCrypto.decodePublicKey(QuickShareCrypto.encodePublicKey(a.public as ECPublicKey))
            val bPublic = QuickShareCrypto.decodePublicKey(QuickShareCrypto.encodePublicKey(b.public as ECPublicKey))
            val shared = QuickShareCrypto.ecdh(a.private, bPublic)
            assertEquals(32, shared.size)
            assertArrayEquals(shared, QuickShareCrypto.ecdh(b.private, aPublic))
        }
    }

    @Test
    fun rejectsAPointThatIsNotOnTheCurve() {
        val offCurve = SecureMessageProto.GenericPublicKey.newBuilder()
            .setType(SecureMessageProto.PublicKeyType.EC_P256)
            .setEcP256PublicKey(
                SecureMessageProto.EcP256PublicKey.newBuilder()
                    .setX(ByteString.copyFrom(byteArrayOf(1)))
                    .setY(ByteString.copyFrom(byteArrayOf(1)))
            )
            .build()
            .toByteArray()
        assertThrows(QuickShareProtocolException::class.java) { QuickShareCrypto.decodePublicKey(offCurve) }
    }

    // --- UKEY2 server ------------------------------------------------------------------------

    @Test
    fun ukey2RejectsAFinishThatDoesNotMatchTheCommitment() {
        val server = Ukey2Server()
        server.onClientInit(clientInit(commitment = QuickShareCrypto.sha512(clientFinished())))
        val rejected = assertThrows(Ukey2Server.HandshakeRejected::class.java) {
            server.onClientFinished(clientFinished()) // a different key from the committed one
        }
        val alert = UkeyProto.Ukey2Message.parseFrom(rejected.alert)
        assertEquals(UkeyProto.Ukey2Message.Type.ALERT, alert.messageType)
    }

    @Test
    fun ukey2RejectsBadClientInit() {
        val commitment = ByteArray(64)
        listOf(
            clientInit(commitment, version = 2),
            clientInit(commitment, randomSize = 31),
            clientInit(commitment, cipher = UkeyProto.Ukey2HandshakeCipher.CURVE25519_SHA512),
            clientInit(commitment, nextProtocol = "AES_128_GCM"),
            byteArrayOf(1, 2, 3),
        ).forEach { frame ->
            assertThrows(Ukey2Server.HandshakeRejected::class.java) { Ukey2Server().onClientInit(frame) }
        }
    }

    // --- SecureChannel -----------------------------------------------------------------------

    private val keys = QuickShareCrypto.deriveSessionKeys(ByteArray(32) { 9 })
    private fun phone() = SecureChannel(sendKeys = keys.client, receiveKeys = keys.server)
    private fun pc() = SecureChannel(sendKeys = keys.server, receiveKeys = keys.client)

    @Test
    fun secureChannelRoundTripsBothDirectionsInOrder() {
        val phone = phone()
        val pc = pc()
        for (size in listOf(0, 1, 15, 16, 17, 100_000)) {
            val up = ByteArray(size) { it.toByte() }
            assertArrayEquals(up, pc.open(phone.seal(up)))
            val down = ByteArray(size) { (it * 7).toByte() }
            assertArrayEquals(down, phone.open(pc.seal(down)))
        }
    }

    @Test
    fun secureChannelRejectsTampering() {
        val sealed = phone().seal("hello".toByteArray())
        val message = SecureMessageProto.SecureMessage.parseFrom(sealed)
        fun flipLast(data: ByteString) = data.toByteArray().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }

        val badBody = message.toBuilder().setHeaderAndBody(ByteString.copyFrom(flipLast(message.headerAndBody))).build()
        val badTag = message.toBuilder().setSignature(ByteString.copyFrom(flipLast(message.signature))).build()
        assertThrows(QuickShareProtocolException::class.java) { pc().open(badBody.toByteArray()) }
        assertThrows(QuickShareProtocolException::class.java) { pc().open(badTag.toByteArray()) }
    }

    @Test
    fun secureChannelRejectsReplayGapsAndReflection() {
        val phone = phone()
        val first = phone.seal(byteArrayOf(1))
        val second = phone.seal(byteArrayOf(2))
        val third = phone.seal(byteArrayOf(3))
        val pc = pc()
        pc.open(first)
        assertThrows(QuickShareProtocolException::class.java) { pc.open(first) }
        assertThrows(QuickShareProtocolException::class.java) { pc.open(third) }
        assertArrayEquals(byteArrayOf(2), pc.open(second))
        // Our own message played back at us fails: each direction has its own keys.
        assertThrows(QuickShareProtocolException::class.java) { pc.open(pc().seal(byteArrayOf(4))) }
    }

    @Test
    fun secureChannelNeverRepeatsAnIv() {
        val phone = phone()
        val ivs = (1..50).map {
            val message = SecureMessageProto.SecureMessage.parseFrom(phone.seal(byteArrayOf(0)))
            SecureMessageProto.HeaderAndBody.parseFrom(message.headerAndBody).header.iv
        }
        assertTrue(ivs.toSet().size == ivs.size)
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun clientInit(
        commitment: ByteArray,
        version: Int = 1,
        randomSize: Int = 32,
        cipher: UkeyProto.Ukey2HandshakeCipher = UkeyProto.Ukey2HandshakeCipher.P256_SHA512,
        nextProtocol: String = Ukey2Server.NEXT_PROTOCOL,
    ): ByteArray = UkeyProto.Ukey2Message.newBuilder()
        .setMessageType(UkeyProto.Ukey2Message.Type.CLIENT_INIT)
        .setMessageData(
            UkeyProto.Ukey2ClientInit.newBuilder()
                .setVersion(version)
                .setRandom(ByteString.copyFrom(ByteArray(randomSize)))
                .addCipherCommitments(
                    UkeyProto.Ukey2ClientInit.CipherCommitment.newBuilder()
                        .setHandshakeCipher(cipher)
                        .setCommitment(ByteString.copyFrom(commitment))
                )
                .setNextProtocol(nextProtocol)
                .build()
                .toByteString()
        )
        .build()
        .toByteArray()

    private fun clientFinished(): ByteArray = UkeyProto.Ukey2Message.newBuilder()
        .setMessageType(UkeyProto.Ukey2Message.Type.CLIENT_FINISH)
        .setMessageData(
            UkeyProto.Ukey2ClientFinished.newBuilder()
                .setPublicKey(
                    ByteString.copyFrom(
                        QuickShareCrypto.encodePublicKey(QuickShareCrypto.generateP256KeyPair().public as ECPublicKey)
                    )
                )
                .build()
                .toByteString()
        )
        .build()
        .toByteArray()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
