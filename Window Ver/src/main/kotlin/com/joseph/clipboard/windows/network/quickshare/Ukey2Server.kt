package com.joseph.clipboard.windows.network.quickshare

import com.google.protobuf.ByteString
import com.joseph.clipboard.windows.network.quickshare.proto.UkeyProto
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey

/**
 * The responder half of UKEY2 (github.com/google/ukey2): [onClientInit] answers the sender's
 * ClientInit with a ServerInit, then [onClientFinished] checks the commitment made in ClientInit
 * and derives the secrets. One instance per connection.
 */
class Ukey2Server(private val random: SecureRandom = SecureRandom()) {

    /** A rejected handshake. [alert] is the Ukey2Alert frame to send back before closing. */
    class HandshakeRejected(message: String, val alert: ByteArray) :
        java.io.IOException("UKEY2: $message")

    private var keyPair: KeyPair? = null
    private var commitment: ByteArray? = null
    private var clientInitFrame: ByteArray? = null
    private var serverInitFrame: ByteArray? = null

    /** Takes the raw ClientInit frame and returns the raw ServerInit frame to send. */
    fun onClientInit(frame: ByteArray): ByteArray {
        val init = parseMessage(frame, UkeyProto.Ukey2Message.Type.CLIENT_INIT) {
            UkeyProto.Ukey2ClientInit.parseFrom(it)
        }
        if (init.version != 1) reject("bad version", UkeyProto.Ukey2Alert.AlertType.BAD_VERSION)
        if (init.random.size() != 32) reject("bad random", UkeyProto.Ukey2Alert.AlertType.BAD_RANDOM)
        val p256 = init.cipherCommitmentsList.firstOrNull {
            it.handshakeCipher == UkeyProto.Ukey2HandshakeCipher.P256_SHA512
        } ?: reject("no P256_SHA512 commitment", UkeyProto.Ukey2Alert.AlertType.BAD_HANDSHAKE_CIPHER)
        if (init.nextProtocol != NEXT_PROTOCOL) {
            reject("unsupported next protocol", UkeyProto.Ukey2Alert.AlertType.BAD_NEXT_PROTOCOL)
        }

        val pair = QuickShareCrypto.generateP256KeyPair(random)
        val serverInit = UkeyProto.Ukey2ServerInit.newBuilder()
            .setVersion(1)
            .setRandom(ByteString.copyFrom(ByteArray(32).also { random.nextBytes(it) }))
            .setHandshakeCipher(UkeyProto.Ukey2HandshakeCipher.P256_SHA512)
            .setPublicKey(ByteString.copyFrom(QuickShareCrypto.encodePublicKey(pair.public as ECPublicKey)))
            .build()
        val out = wrap(UkeyProto.Ukey2Message.Type.SERVER_INIT, serverInit.toByteString())

        keyPair = pair
        commitment = p256.commitment.toByteArray()
        clientInitFrame = frame
        serverInitFrame = out
        return out
    }

    /** Takes the raw ClientFinished frame and returns the handshake secrets. */
    fun onClientFinished(frame: ByteArray): QuickShareCrypto.Ukey2Secrets {
        val pair = checkNotNull(keyPair) { "ClientFinished before ClientInit" }
        val finished = parseMessage(frame, UkeyProto.Ukey2Message.Type.CLIENT_FINISH) {
            UkeyProto.Ukey2ClientFinished.parseFrom(it)
        }
        // The commitment binds the sender's key to what it sent before seeing ours; without this
        // check a man in the middle could pick a key to steer the PIN.
        if (!MessageDigest.isEqual(commitment, QuickShareCrypto.sha512(frame))) {
            reject("commitment mismatch", UkeyProto.Ukey2Alert.AlertType.BAD_MESSAGE_DATA)
        }
        val peer = try {
            QuickShareCrypto.decodePublicKey(finished.publicKey.toByteArray())
        } catch (e: java.io.IOException) {
            reject("bad public key", UkeyProto.Ukey2Alert.AlertType.BAD_PUBLIC_KEY)
        }
        keyPair = null // single use
        return QuickShareCrypto.deriveUkey2Secrets(
            QuickShareCrypto.ecdh(pair.private, peer),
            clientInitFrame!!,
            serverInitFrame!!,
        )
    }

    private fun <T> parseMessage(frame: ByteArray, expected: UkeyProto.Ukey2Message.Type, parse: (ByteString) -> T): T {
        val message = try {
            UkeyProto.Ukey2Message.parseFrom(frame)
        } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
            reject("unreadable message", UkeyProto.Ukey2Alert.AlertType.BAD_MESSAGE)
        }
        if (message.messageType != expected) {
            reject("expected $expected", UkeyProto.Ukey2Alert.AlertType.BAD_MESSAGE_TYPE)
        }
        return try {
            parse(message.messageData)
        } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
            reject("unreadable message data", UkeyProto.Ukey2Alert.AlertType.BAD_MESSAGE_DATA)
        }
    }

    private fun reject(reason: String, type: UkeyProto.Ukey2Alert.AlertType): Nothing {
        val alert = UkeyProto.Ukey2Alert.newBuilder().setType(type).build().toByteString()
        throw HandshakeRejected(reason, wrap(UkeyProto.Ukey2Message.Type.ALERT, alert))
    }

    private fun wrap(type: UkeyProto.Ukey2Message.Type, data: ByteString): ByteArray =
        UkeyProto.Ukey2Message.newBuilder().setMessageType(type).setMessageData(data).build().toByteArray()

    companion object {
        const val NEXT_PROTOCOL = "AES_256_CBC-HMAC_SHA256"
    }
}
