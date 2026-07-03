package com.EntropicSoftwareLab.ClipboardShare.transport

import android.util.Base64
import com.EntropicSoftwareLab.ClipboardShare.EcdhSession
import com.EntropicSoftwareLab.ClipboardShare.Net
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import com.EntropicSoftwareLab.ClipboardShare.TransferSession
import com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridPayload
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Extracted off-grid handshake logic for the receiving side (scanner).
 * Symmetric counterpart to QrHostCoordinator/P2pHelloHandler on the sending side.
 */
object OffGridHandshake {

    const val MAX_LINE_BYTES = 65536
    const val P2P_HELLO = "P2P_HELLO"
    const val P2P_WELCOME = "P2P_WELCOME"
    const val P2P_BUSY = "P2P_BUSY"
    const val OFFER = "OFFER"
    const val MANIFEST = "MANIFEST"
    const val ACCEPT_OFFER = "ACCEPT_OFFER"

    enum class HandshakeOutcome { STARTED, BUSY, EXPIRED, FAILED }

    interface Listener {
        /**
         * Called when the handshake has successfully completed and we have received the MANIFEST.
         * The caller should create the history record and hand off to the download stream engine.
         */
        fun onTransferStarted(sessionId: String, connection: TransportConnection, session: TransferSession)
    }

    /**
     * Connects to the sender using the given route and payload, negotiates the keys, receives the manifest,
     * accepts the offer, and returns the outcome.
     */
    fun connectAndReceive(
        payload: OffGridPayload,
        route: TransportRoute,
        ecdh: EcdhSession?,
        isActive: () -> Boolean,
        exactSenderIp: String?,
        exactSenderPort: Int?,
        listener: Listener
    ): HandshakeOutcome {
        var connection: TransportConnection? = null
        var handedOff = false
        try {
            val conn = connectToSender(route, payload, isActive, exactSenderIp, exactSenderPort)
            if (conn == null) {
                TransportDiag.log("handshake: couldn't open TCP to sender (route up but unreachable) → FAILED")
                return HandshakeOutcome.FAILED
            }
            connection = conn
            connection.setReadTimeout(10000)
            TransportDiag.log("handshake: TCP connected ✓ to ${conn.remoteIdentifier} — sending P2P_HELLO")

            val finalEcdh = ecdh ?: EcdhSession()
            val sharedKey = finalEcdh.deriveSecrets(payload.senderPublicKey).first

            val tokenB64 = Base64.encodeToString(payload.token, Base64.NO_WRAP)
            val pubB64 = Base64.encodeToString(finalEcdh.getPublicKey(), Base64.NO_WRAP)
            val hmacB64 = Base64.encodeToString(
                SessionCrypto.hmacSha256(sharedKey, payload.token), Base64.NO_WRAP
            )
            val hello = "$P2P_HELLO|$tokenB64|$pubB64|${TransferDaemon.customDeviceName}|$hmacB64\n"

            val outStream = connection.output
            val inStream = connection.input
            outStream.write(hello.toByteArray(Charsets.UTF_8))
            outStream.flush()

            val welcome = readLine(inStream)
            if (welcome == null) {
                TransportDiag.log("handshake: connected but no P2P_WELCOME (sender closed / token mismatch) → retry")
                return HandshakeOutcome.EXPIRED
            }
            if (welcome == P2P_BUSY) { TransportDiag.log("handshake: sender BUSY"); return HandshakeOutcome.BUSY }
            if (!welcome.startsWith("$P2P_WELCOME|")) {
                TransportDiag.log("handshake: unexpected reply '${welcome.take(24)}' → retry")
                return HandshakeOutcome.EXPIRED
            }

            val welcomeParts = welcome.split('|')
            if (welcomeParts.size < 3) { TransportDiag.log("handshake: malformed WELCOME → FAILED"); return HandshakeOutcome.FAILED }
            val sessionId = welcomeParts[1]
            val ackBytes = try {
                Base64.decode(welcomeParts[2], Base64.NO_WRAP)
            } catch (_: Exception) { TransportDiag.log("handshake: bad ack b64 → FAILED"); return HandshakeOutcome.FAILED }

            val expectedAck = SessionCrypto.hmacSha256(sharedKey, payload.token + "ack".toByteArray(Charsets.UTF_8))
            if (!MessageDigest.isEqual(ackBytes, expectedAck)) {
                TransportDiag.log("handshake: ack HMAC mismatch (wrong key/token) → retry")
                return HandshakeOutcome.EXPIRED
            }
            TransportDiag.log("handshake: WELCOME + ack OK — receiving OFFER/MANIFEST")

            val offerLine = readLine(inStream) ?: return HandshakeOutcome.FAILED
            if (!offerLine.startsWith("$OFFER|")) return HandshakeOutcome.FAILED
            val offerParts = offerLine.split('|')
            if (offerParts.size < 4) return HandshakeOutcome.FAILED
            val senderName = offerParts[2]
            val totalFiles = offerParts[3].toIntOrNull() ?: return HandshakeOutcome.FAILED

            val manifestLine = readLine(inStream) ?: return HandshakeOutcome.FAILED
            if (!manifestLine.startsWith("$MANIFEST|")) return HandshakeOutcome.FAILED
            val manifest = mutableListOf<Pair<String, Long>>()
            for (item in manifestLine.removePrefix("$MANIFEST|").split('|')) {
                val lastColonIndex = item.lastIndexOf(':')
                if (lastColonIndex > 0) {
                    val fname = item.substring(0, lastColonIndex)
                    val fsize = item.substring(lastColonIndex + 1).toLongOrNull() ?: continue
                    manifest.add(Pair(fname, fsize))
                }
            }
            if (manifest.isEmpty()) return HandshakeOutcome.FAILED

            val ackSupported = (payload.cap and TransportCaps.COMPLETION_ACK) != 0
            val acceptCmd = if (ackSupported) "$ACCEPT_OFFER|$sessionId|ack\n" else "$ACCEPT_OFFER|$sessionId\n"
            outStream.write(acceptCmd.toByteArray(Charsets.UTF_8))
            outStream.flush()

            val session = TransferSession(
                sessionId = sessionId,
                senderName = senderName,
                totalFiles = totalFiles,
                manifest = manifest,
                targetIp = payload.ip,
                isIncoming = true,
                transport = "p2p",
                completionAckRequired = ackSupported
            )
            
            listener.onTransferStarted(sessionId, connection, session)
            handedOff = true
            return HandshakeOutcome.STARTED
        } catch (e: Exception) {
            e.printStackTrace()
            return HandshakeOutcome.FAILED
        } finally {
            if (!handedOff) {
                try { connection?.close() } catch (_: Exception) {}
            }
        }
    }

    fun connectToSender(
        route: TransportRoute,
        payload: OffGridPayload,
        isActive: () -> Boolean,
        exactSenderIp: String?,
        exactSenderPort: Int?
    ): TransportConnection? {
        val port = exactSenderPort ?: payload.port
        val candidates = linkedSetOf<String>()
        exactSenderIp?.let { candidates.add(it) }
        candidates.addAll(route.senderCandidates(payload))
        val selfIps = Net.localIpv4Addresses()
        val dialable = candidates.filterNot { it in selfIps }
        if (dialable.isEmpty()) {
            TransportDiag.log("handshake: no reachable sender address (all candidates were self) — route failed")
            return null
        }
        TransportDiag.log("handshake: dialing sender at ${dialable.joinToString(",")}:$port")
        repeat(5) { attempt ->
            for (ip in dialable) {
                try {
                    return route.openConnection(ip, port)
                } catch (_: Exception) {
                }
            }
            if (attempt < 4) {
                Thread.sleep(1000L + attempt * 700L)
                if (!isActive()) return null
            }
        }
        return null
    }

    private fun readLine(inputStream: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var b = inputStream.read()
        while (b != -1 && b != '\n'.code) {
            baos.write(b)
            if (baos.size() > MAX_LINE_BYTES) {
                throw IOException("control line exceeded $MAX_LINE_BYTES bytes")
            }
            b = inputStream.read()
        }
        if (b == -1 && baos.size() == 0) return null
        return baos.toString("UTF-8")
    }
}
