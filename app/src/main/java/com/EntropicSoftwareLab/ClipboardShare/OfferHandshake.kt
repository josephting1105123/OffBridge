package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.util.Base64
import com.EntropicSoftwareLab.ClipboardShare.transport.SessionCrypto
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The sender's half of the offer handshake: connect, announce, prove, disconnect.
 *
 * ```
 * ->  OFFER|<sessionId>|<deviceName>|<count>
 * ->  MANIFEST|name:size|name:size|
 * <-  OFFER_CHALLENGE|<nonceB64>      (only from an AUTH2-capable receiver)
 * ->  OFFER_PROOF|<hmacB64>
 * <-  OFFER_RECEIVED
 * ```
 *
 * Then the socket closes. The receiver dials back with ACCEPT_OFFER or DECLINE_OFFER, which is why
 * nothing here waits for a user decision.
 *
 * Extracted from the send-screen loop so the screenshot path can raise a real offer without a second
 * hand-written copy of the AUTH2 exchange. A duplicated challenge/proof sequence is the kind of thing
 * that drifts silently: the copy keeps working against old peers and fails against new ones months
 * later, with no test able to tell you which copy you are looking at.
 */
object OfferHandshake {

    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 10000

    /**
     * Runs the handshake against [targetIp], or throws.
     *
     * Throwing rather than returning a Boolean is deliberate — every caller wants the reason. The
     * one that matters in practice is "No pairing key to answer OFFER_CHALLENGE", which is the
     * difference between a network problem and an unpaired device and used to be invisible.
     */
    @Throws(IOException::class, Exception::class)
    fun sendOffer(
        context: Context,
        targetIp: String,
        sessionId: String,
        deviceName: String,
        manifest: List<Pair<String, Long>>,
    ) {
        // connect() runs INSIDE use so a timeout still closes the socket rather than leaking the fd.
        Socket().use { socket ->
            socket.connect(InetSocketAddress(targetIp, Ports.FILE_TRANSFER), CONNECT_TIMEOUT_MS)
            val out = socket.getOutputStream()

            val header = "OFFER|$sessionId|$deviceName|${manifest.size}\n"
            val manifestLine = buildString {
                append("MANIFEST|")
                for ((name, size) in manifest) append("$name:$size|")
                append("\n")
            }
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(manifestLine.toByteArray(Charsets.UTF_8))
            out.flush()

            socket.soTimeout = READ_TIMEOUT_MS
            val input = socket.getInputStream()
            var response = TransferUtils.readUntilNewline(input)

            // A newer receiver challenges us to prove we hold the shared pairing key before it shows
            // anything to its user. An older one replies OFFER_RECEIVED directly. Tolerate both
            // rather than branching on the peer's advertised caps, which can be stale.
            if (response.startsWith("OFFER_CHALLENGE|")) {
                val nonce = Base64.decode(response.removePrefix("OFFER_CHALLENGE|"), Base64.NO_WRAP)
                val pairingKey = SecurityVault.getKeyForNode(context, targetIp)
                    ?: throw IllegalStateException("No pairing key to answer OFFER_CHALLENGE for $targetIp")
                val proof = SessionCrypto.hmacSha256(pairingKey, nonce)
                out.write("OFFER_PROOF|${Base64.encodeToString(proof, Base64.NO_WRAP)}\n".toByteArray(Charsets.UTF_8))
                out.flush()
                response = TransferUtils.readUntilNewline(input)
            }

            if (response != "OFFER_RECEIVED") {
                throw IOException("Receiver did not acknowledge offer (got \"$response\")")
            }
        }
    }
}
