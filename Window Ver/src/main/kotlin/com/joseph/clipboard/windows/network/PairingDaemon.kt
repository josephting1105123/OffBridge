package com.joseph.clipboard.windows.network

import com.joseph.clipboard.windows.storage.CryptoEngine
import com.joseph.clipboard.windows.storage.EcdhSession
import com.joseph.clipboard.windows.storage.PairSeal
import com.joseph.clipboard.windows.storage.SecurityVault
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PairingDaemon(
    private val onIncomingPairingRequest: (senderIp: String, senderName: String, visualPin: String, accept: () -> Unit, decline: () -> Unit) -> Unit,
    private val onUnpaired: ((senderIp: String) -> Unit)? = null,
    /** Fired after a QR pairing completes, so the screen showing the code can report the result
     *  instead of waiting for the next discovery sweep. */
    private val onQrPaired: ((senderIp: String, senderName: String) -> Unit)? = null
) {
    companion object {
        /** How long the daemon's parked socket/thread waits for the user to answer a pairing dialog
         *  before giving up and sending PAIR_DECLINE on its own. Exposed so the UI layer can compute
         *  the same expiry and refuse a stale Accept click instead of silently completing pairing on
         *  only this side — the daemon thread already sent DECLINE and moved on by then, so an Accept
         *  after this window would save a key the other device never confirmed (mirrors Android's
         *  AND-36 fix in PairingConfirmationActivity.completePairing). */
        const val PAIR_CONFIRM_WINDOW_MS = 120_000L
    }
    private val PAIRING_PORT = Ports.PAIRING
    private var isRunning = false
    private var serverSocket: ServerSocket? = null

    // Rate-limit incoming pairing handlers so a LAN peer can't spam threads and clobber the
    // dialog. Cap total pending handlers and throttle repeat connects from the same source IP.
    private val MAX_PENDING_HANDLERS = 8
    private val PER_IP_MIN_INTERVAL_MS = 2000L
    private val pendingHandlers = AtomicInteger(0)
    private val lastAcceptedAt = ConcurrentHashMap<String, Long>()

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(isDaemon = true, name = "Pairing-Listener") {
            // Capture the bound socket into a local so this thread's finally closes only the
            // instance it created. A quick stop()/start() otherwise races: the old listener's
            // finally would close/null the field the new listener just published.
            var server: ServerSocket? = null
            try {
                server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PAIRING_PORT))
                }
                serverSocket = server
                println("PairingDaemon started on port $PAIRING_PORT")

                while (isRunning) {
                    val clientSocket = server.accept()
                    handleIncomingPairing(clientSocket)
                }
            } catch (e: SocketException) {
                if (isRunning) e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // If bind() failed this instance never listened; clear the running flag so a
                // later start() can rebind. Close only the local instance, not the field, so we
                // never close a socket a concurrent start() has since published.
                if (server == null) isRunning = false
                try { server?.close() } catch (e: Exception) {}
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
    }

    /**
     * Host half of QR pairing. The scanner's token claims the open window ([QrPairHost]); both sides
     * then derive the same AES-256 key from ECDH and prove it with a [PairSeal] blob.
     *
     * Trust is stored last, only once the scanner's FIN opens under that key — so a peer that dialed
     * in with a stolen-but-valid token, or one that can't complete the exchange, leaves nothing
     * behind. On any failure the window is restored, letting the user's genuine second attempt work
     * without regenerating the code.
     *
     * Byte-identical counterpart: Android `PairingDaemon.handleQrPairing`.
     */
    private fun handleQrPairing(
        reader: BufferedReader,
        writer: PrintWriter,
        senderIp: String,
        request: String
    ) {
        var claimed: QrPairHost.Session? = null
        try {
            val req = QrPairWire.parseReq(request)
            if (req == null) {
                writer.println(QrPairWire.deny(QrPairWire.DENY_BAD_REQUEST))
                return
            }

            // Screen the peer's key BEFORE claiming: a malformed or off-curve key can then never
            // burn the one-shot window, and the agreement below never sees a point off P-256.
            if (!PairQrPayload.isP256PublicKey(req.publicKey)) {
                writer.println(QrPairWire.deny(QrPairWire.DENY_BAD_REQUEST))
                return
            }

            val session = QrPairHost.claim(req.token, System.currentTimeMillis())
            if (session == null) {
                // Expired, already claimed, or never opened — one answer for all three so a prober
                // on the LAN can't learn which.
                writer.println(QrPairWire.deny(QrPairWire.DENY_NO_SESSION))
                return
            }
            claimed = session

            val aesKey = try {
                session.ecdh.deriveSecrets(req.publicKey).first
            } catch (e: Exception) {
                // A malformed or off-curve key from the scanner surfaces here as a
                // GeneralSecurityException out of KeyFactory/KeyAgreement.
                writer.println(QrPairWire.deny(QrPairWire.DENY_BAD_REQUEST))
                return
            }

            val tokenHex = session.tokenHex()
            writer.println(
                QrPairWire.ack(
                    SecurityVault.localDeviceId(),
                    PairSeal.seal(aesKey, PairSeal.hostConfirmation(tokenHex), System.currentTimeMillis())
                )
            )
            // PrintWriter(autoflush) swallows IOExceptions; checkError() flushes and reports them.
            // If the ACK never left, the scanner can't have stored anything — bail before we do.
            if (writer.checkError()) return

            val finLine = reader.readLine() ?: return
            val fin = QrPairWire.parseFin(finLine)
            if (fin == null || !PairSeal.verify(aesKey, fin, PairSeal.peerConfirmation(tokenHex), System.currentTimeMillis())) {
                writer.println(QrPairWire.deny(QrPairWire.DENY_BAD_REQUEST))
                return
            }

            SecurityVault.saveTrustedNode(senderIp, aesKey, req.deviceId, req.deviceName)
            claimed = null // paired — the window is spent, don't hand it back
            println("QR pairing completed with ${req.deviceName} ($senderIp)")
            onQrPaired?.invoke(senderIp, req.deviceName)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            claimed?.let { QrPairHost.restore(it) }
        }
    }

    private fun handleIncomingPairing(clientSocket: Socket) {
        val senderIp = clientSocket.inetAddress.hostAddress

        // Per-source-IP throttle: drop repeat connects that arrive faster than the min interval,
        // so one peer can't spam the accept loop and clobber the pairing dialog.
        val now = System.currentTimeMillis()
        val prev = lastAcceptedAt.put(senderIp, now)
        if (prev != null && now - prev < PER_IP_MIN_INTERVAL_MS) {
            try { clientSocket.close() } catch (e: Exception) {}
            return
        }

        // Concurrent cap: bound how many pending handlers can park on the 2-minute latch at once.
        if (pendingHandlers.incrementAndGet() > MAX_PENDING_HANDLERS) {
            pendingHandlers.decrementAndGet()
            try { clientSocket.close() } catch (e: Exception) {}
            println("Rejected pairing from $senderIp: too many pending handlers")
            return
        }

        thread(isDaemon = true, name = "Pairing-Handler-$senderIp") {
            try {
                // Bound the handshake read so a peer that connects but never sends a request
                // can't leak this handler thread forever. Only affects reads, so the later
                // PAIR_ACK/ACCEPT writes and the 2-minute dialog latch are unaffected.
                clientSocket.soTimeout = 20000
                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                val writer = PrintWriter(clientSocket.getOutputStream(), true)

                val request = reader.readLine() ?: return@thread
                
                if (request.startsWith("UNPAIR_REQ|")) {
                    // Authenticated unpair: require the sender to prove possession of the per-node key
                    // (HMAC over a fresh nonce) before removing it. Blocks unauthenticated unpair.
                    val key = SecurityVault.getAesKey(senderIp)
                    if (key == null) { clientSocket.close(); return@thread }
                    val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    writer.println("UNPAIR_CHALLENGE|${Base64.getEncoder().encodeToString(nonce)}")
                    val proofLine = reader.readLine()
                    if (proofLine != null && proofLine.startsWith("UNPAIR_PROOF|")) {
                        val proof = try {
                            Base64.getDecoder().decode(proofLine.removePrefix("UNPAIR_PROOF|"))
                        } catch (e: Exception) { null }
                        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                        val expected = mac.doFinal(nonce)
                        if (proof != null && java.security.MessageDigest.isEqual(proof, expected)) {
                            println("Authenticated unpair from $senderIp — removing trusted node")
                            SecurityVault.removeTrustedNode(senderIp)
                            onUnpaired?.invoke(senderIp)
                        }
                    }
                    clientSocket.close()
                    return@thread
                }

                if (request.startsWith("${QrPairWire.VERB_REQ}|")) {
                    // QR pairing: no dialog, no Accept click. The scanner proved physical access to
                    // this screen by presenting the one-shot token, and both sides prove the derived
                    // key to each other, so there is nothing left for a human to check.
                    //
                    // Deliberately NOT gated on the discoverable toggle the way the PIN path is.
                    // That toggle is a standing "don't let devices find me"; putting a code on
                    // screen is a stronger, explicit, per-session consent that supersedes it — and
                    // gating this would make a user who turned discovery off watch their own
                    // displayed code fail for no stated reason. A window only exists while that
                    // dialog is open, which is the real gate.
                    handleQrPairing(reader, writer, senderIp, request)
                    return@thread
                }

                if (request.startsWith("PAIR_REQ|")) {
                    val parts = request.split("|")
                    if (parts.size >= 3) {
                        val senderName = parts[1]
                        val peerPubKey = Base64.getDecoder().decode(parts[2])
                        // v2 PIN only when the initiator advertised pv2. Fields after index 2 are
                        // tolerated (older initiators send none) — absence => v1 fallback.
                        // Unordered token tail: pv2 (PIN v2) and the initiator's stable device id,
                        // both absent on older peers. The id lets the save below retire this
                        // device's record at a previous address.
                        val tail = parts.drop(3)
                        val initiatorV2 = tail.contains("pv2")
                        val senderDeviceId = WireFrames.parseDeviceId(tail)

                        val ecdh = EcdhSession()
                        val myPubKey = ecdh.getPublicKey()
                        val (aesKey, visualPin) = ecdh.deriveSecrets(peerPubKey, initiatorV2)

                        // This build always advertises pv2; the PIN it displays is v2 only if the
                        // initiator also asked for it (initiatorV2), so both sides stay in agreement.
                        writer.println(
                            WireFrames.pairAck(
                                Base64.getEncoder().encodeToString(myPubKey),
                                SecurityVault.localDeviceId(),
                            )
                        )

                        val latch = CountDownLatch(1)
                        var isAccepted = false

                        onIncomingPairingRequest(senderIp, senderName, visualPin, {
                            isAccepted = true
                            latch.countDown()
                        }, {
                            isAccepted = false
                            latch.countDown()
                        })

                        // A never-answered pairing dialog must not pin this thread+socket forever.
                        if (!latch.await(PAIR_CONFIRM_WINDOW_MS, TimeUnit.MILLISECONDS)) {
                            isAccepted = false
                        }

                        if (isAccepted) {
                            writer.println("PAIR_ACCEPT")
                            // PrintWriter(autoflush) swallows IOExceptions; checkError() flushes and
                            // reports them. If the phone never received PAIR_ACCEPT, don't persist a
                            // peer it never confirmed — abort the save instead of storing a ghost node.
                            if (writer.checkError()) {
                                println("Pairing aborted with $senderName ($senderIp): PAIR_ACCEPT write failed")
                            } else {
                                SecurityVault.saveTrustedNode(senderIp, aesKey, senderDeviceId, senderName)
                                println("Pairing accepted with $senderName ($senderIp)")
                            }
                        } else {
                            writer.println("PAIR_DECLINE")
                            println("Pairing declined with $senderName ($senderIp)")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingHandlers.decrementAndGet()
                try {
                    clientSocket.close()
                } catch (e: Exception) {}
            }
        }
    }
}
