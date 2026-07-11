package com.joseph.clipboard.windows.network

import com.joseph.clipboard.windows.storage.DiagLog
import com.joseph.clipboard.windows.storage.SecurityVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import com.joseph.clipboard.windows.network.control.SessionCrypto

class FileTransferDaemon(
    private val onOfferReceived: (ip: String, sessionId: String, deviceName: String, manifest: List<Pair<String, Long>>) -> Unit,
    private val onAcceptReceived: (sessionId: String, fgcm: Boolean, useV2: Boolean, socket: Socket) -> Unit,
    private val onResumeReceived: (sessionId: String, fileIndex: Int, offset: Long, ackExpected: Boolean, fgcm: Boolean, useV2: Boolean, socket: Socket) -> Unit,
    private val onCancelReceived: (sessionId: String) -> Unit = {},
    /** An Android scanner dialed in for off-grid "send via QR"; the handler owns the socket. */
    private val onP2pHelloReceived: (socket: Socket, header: String) -> Unit = { s, _ -> try { s.close() } catch (_: Exception) {} },
    /** True iff the peer at [ip] advertised [TransferDaemon.OFFER_AUTH_CAP] in discovery. Discovery
     * lives in the separate [TransferDaemon]; the controller wires this to its discovered-node set so
     * the OFFER branch can decide whether to issue the AUTH2 admission challenge. Defaults to "no
     * AUTH2" so a caller that doesn't wire it keeps today's behavior. */
    private val supportsAuth2Provider: (ip: String) -> Boolean = { false },
    /** True iff [sessionId] names a live OUTGOING off-grid (p2p) session. An off-grid receiver resumes
     * from an ephemeral, never-paired IP, so a matching RESUME_REQUEST is exempt from the LAN trust
     * gate below (XP-05) — the sessionId was issued over the ECDH-authenticated P2P_WELCOME, so it is
     * an unguessable per-session token. Mirrors the Android FileTransferServer exemption. Defaults to
     * "no p2p session" so a caller that doesn't wire it keeps LAN-only behavior. */
    private val isOffGridResumeSession: (sessionId: String) -> Boolean = { false }
) {
    private val FILE_TRANSFER_PORT = Ports.FILE_TRANSFER
    @Volatile private var isRunning = false
    // Written by the accept loop, read/closed by stop() from another thread — @Volatile so stop()
    // observes the bound socket and can actually close it (else the listener can stay bound).
    @Volatile private var serverSocket: ServerSocket? = null

    // Surface the bind result so the controller/UI can tell a live listener from a silently dead one.
    // null = not started / stopped; true = bound & accepting; false = last start() failed to bind.
    private val _isBound = MutableStateFlow<Boolean?>(null)
    val isBound: StateFlow<Boolean?> = _isBound.asStateFlow()
    @Volatile var lastBindError: String? = null
        private set

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(isDaemon = true, name = "FileTransfer-Listener") {
            // Capture THIS thread's own ServerSocket so finally closes only what it bound. A delayed
            // old thread (after restartServices' stop→delay→start) must not close the NEW socket via
            // the shared field.
            val boundSocket: ServerSocket
            try {
                boundSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(FILE_TRANSFER_PORT))
                }
                serverSocket = boundSocket
                lastBindError = null
                _isBound.value = true
                println("FileTransferDaemon started on port $FILE_TRANSFER_PORT")
            } catch (e: Exception) {
                // Bind failed: don't run a dead listener silently — surface it so the UI can show it.
                isRunning = false
                lastBindError = e.message ?: e.toString()
                _isBound.value = false
                System.err.println("FileTransferDaemon failed to bind port $FILE_TRANSFER_PORT: ${e.message}")
                return@thread
            }

            try {
                while (isRunning) {
                    val clientSocket = boundSocket.accept()
                    handleIncomingConnection(clientSocket)
                }
            } catch (e: SocketException) {
                if (isRunning) e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { boundSocket.close() } catch (_: Exception) {}
                // Only clear the shared field / bound flag if it still points at OUR socket, so a
                // late-exiting old thread can't disturb a freshly restarted listener.
                if (serverSocket === boundSocket) {
                    serverSocket = null
                    _isBound.value = null
                }
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
        _isBound.value = null
    }

    private fun handleIncomingConnection(clientSocket: Socket) {
        thread(isDaemon = true, name = "FileTransfer-Handler-${clientSocket.inetAddress.hostAddress}") {
            try {
                // Bound the header read so a peer that connects but never sends a newline
                // can't leak this handler thread forever (only affects reads; the streaming
                // hand-off paths write, so this default is safe and can be overridden later).
                clientSocket.soTimeout = 30000
                val inputStream = clientSocket.getInputStream()
                val header = Net.readLineUtf8(inputStream)
                // Diagnostics: record every inbound control connection so a field log can tell
                // "the phone's OFFER never reached this PC" (firewall/dial) apart from "it reached but
                // was dropped by the admission gate below" (each drop path logs its own reason).
                DiagLog.w("inbound ${clientSocket.inetAddress?.hostAddress}: ${header.take(40)}")

                // Trust gate (parity with Android TransferDaemon.handleIncomingConnection): only
                // paired LAN peers may push files / drive session control. Off-grid P2P_HELLO
                // authenticates itself with the QR token + ECDH HMAC (stronger than IP pairing) and
                // arrives from an ephemeral IP that is never a paired node, so it is exempt. P2P_BUSY
                // is a receiver-side reply Windows already ignores, so it is exempt too.
                //
                // Off-grid resume exemption (XP-05, mirrors Android FileTransferServer): an off-grid
                // receiver whose Wi-Fi dropped mid-transfer reconnects from its ephemeral hotspot IP
                // (never paired) and sends RESUME_REQUEST. If the sessionId names a live outgoing p2p
                // session — a token issued over the ECDH-authenticated P2P_WELCOME — admit it; the
                // stream is plaintext by construction, so no pairing key is needed. LAN resumes are
                // unaffected (their session is transport="lan", so the provider returns false).
                val isOffGridResume = header.startsWith("RESUME_REQUEST|") &&
                    header.split("|").getOrNull(1)?.let(isOffGridResumeSession) == true
                if (!isOffGridResume && !header.startsWith("P2P_HELLO|") && !header.startsWith("P2P_BUSY")) {
                    val senderIp = clientSocket.inetAddress?.hostAddress
                    if (senderIp == null || SecurityVault.getAesKey(senderIp) == null) {
                        System.err.println("Blocked transfer header '${header.take(16)}' from $senderIp — not a paired LAN node")
                        DiagLog.w("DROP $senderIp: '${header.take(16)}' — no stored pairing key for this IP (not a paired LAN node). If this is the phone, the PC likely filed the key under a different IP than the phone now sends from.")
                        clientSocket.close()
                        return@thread
                    }
                }

                if (header.startsWith("OFFER|")) {
                    val parts = header.split("|")
                    if (parts.size >= 4) {
                        val sessionId = parts[1]
                        val deviceName = parts[2]
                        val manifestLine = Net.readLineUtf8(inputStream)
                        // Split each entry on the LAST ':' so a name containing ':' is preserved —
                        // byte-for-byte parity with Android (see ManifestCodec / TransferDaemon).
                        val manifest = ManifestCodec.parse(manifestLine).toMutableList()

                        val senderIp = clientSocket.inetAddress?.hostAddress

                        // AUTH2 admission proof: the plain trust gate above only checks that senderIp has
                        // a stored pairing key, so a host spoofing a paired peer's IP (without the key)
                        // could still raise the incoming-file popup. If the sender advertises AUTH2,
                        // require it to PROVE possession of the shared pairing key via an HMAC challenge-
                        // response BEFORE we ack the offer or show the popup. A sender that doesn't
                        // advertise AUTH2 (old build) skips this — byte-identical to before. Mirrors
                        // Android TransferDaemon's OFFER branch exactly (same nonce size, Base64, HMAC).
                        // Anti-downgrade (XP-DEF-01): supportsAuth2 rides the unauthenticated discovery,
                        // so a spoofed advert could strip it and re-open the IP-spoof hole. TOFU: once a
                        // peer proves AUTH2 we pin it, and a later strip is a downgrade — fail closed.
                        // Mirrors Android FileTransferServer (same DowngradePolicy decision + pin).
                        val auth2Decision = if (senderIp != null) DowngradePolicy.decide(
                            peerOffersAead = supportsAuth2Provider(senderIp),
                            pinned = SecurityVault.isAuth2Pinned(senderIp)
                        ) else DowngradePolicy.Decision.PLAINTEXT
                        if (auth2Decision == DowngradePolicy.Decision.BLOCK) {
                            System.err.println("Blocked OFFER from $senderIp — AUTH2 downgrade blocked")
                            DiagLog.w("DROP $senderIp: OFFER — AUTH2 downgrade blocked (peer was pinned AUTH2-capable but this OFFER's discovery advert did not offer it).")
                            clientSocket.close()
                            return@thread
                        }
                        if (auth2Decision == DowngradePolicy.Decision.SEAL && senderIp != null) {
                            val pairingKey = SecurityVault.getAesKey(senderIp)
                            if (pairingKey == null) {
                                // Trust gate said we hold a key for this IP; if it vanished, fail closed.
                                DiagLog.w("DROP $senderIp: OFFER — pairing key vanished between trust gate and AUTH2 challenge.")
                                clientSocket.close()
                                return@thread
                            }
                            val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                            clientSocket.getOutputStream().write("OFFER_CHALLENGE|${java.util.Base64.getEncoder().encodeToString(nonce)}\n".toByteArray(Charsets.UTF_8))
                            clientSocket.getOutputStream().flush()
                            val proofLine = Net.readLineUtf8(inputStream)
                            val proof = if (proofLine.startsWith("OFFER_PROOF|")) {
                                try { java.util.Base64.getDecoder().decode(proofLine.removePrefix("OFFER_PROOF|")) } catch (_: Exception) { null }
                            } else null
                            val expected = SessionCrypto.hmacSha256(pairingKey, nonce)
                            if (proof == null || !java.security.MessageDigest.isEqual(proof, expected)) {
                                // Missing/malformed/wrong proof ⇒ the sender can't prove it holds the key.
                                // Drop the offer silently: no OFFER_RECEIVED, no popup. (An IP-spoofer
                                // lands here.)
                                System.err.println("Blocked OFFER from $senderIp — AUTH2 proof failed")
                                DiagLog.w("DROP $senderIp: OFFER — AUTH2 proof failed (phone answered the challenge but the HMAC did not match this PC's stored key — key mismatch or wrong IP↔key mapping).")
                                clientSocket.close()
                                return@thread
                            }
                            // Proof valid → pin AUTH2 so a later strip on the discovery is caught above.
                            SecurityVault.pinAuth2(senderIp)
                        }

                        clientSocket.getOutputStream().write("OFFER_RECEIVED\n".toByteArray(Charsets.UTF_8))
                        clientSocket.getOutputStream().flush()

                        onOfferReceived(clientSocket.inetAddress.hostAddress, sessionId, deviceName, manifest)
                    }
                    clientSocket.close()
                } else if (header.startsWith("ACCEPT_OFFER|")) {
                    val parts = header.split("|")
                    val sessionId = parts[1]
                    // Receiver appended "fgcm"/"fgcm2" ⇒ it wants the bulk stream FileSeal-sealed;
                    // "fgcm2" additionally selects the v2 AEAD (per-session key + session/file-bound AAD).
                    val acceptFlags = parts.drop(2)
                    val fgcm2 = acceptFlags.contains("fgcm2")
                    val fgcm = acceptFlags.contains("fgcm") || fgcm2
                    // Hand off socket to app controller to stream files out
                    onAcceptReceived(sessionId, fgcm, fgcm2, clientSocket)
                } else if (header.startsWith("RESUME_REQUEST|")) {
                    val parts = header.split("|")
                    if (parts.size >= 4) {
                        val sessionId = parts[1]
                        val fileIndex = parts[2].toIntOrNull() ?: 0
                        val offset = parts[3].toLongOrNull() ?: 0L
                        // Receiver flags after the offset, order-independent: "ack" (COMPLETION_ACK)
                        // and/or "fgcm"/"fgcm2" (FileSeal-sealed bulk stream; "fgcm2" = v2 AEAD).
                        val flags = parts.drop(4)
                        val ackExpected = flags.contains("ack")
                        val fgcm2 = flags.contains("fgcm2")
                        val fgcm = flags.contains("fgcm") || fgcm2
                        // AEAD resume restarts the file (GCM records aren't byte-seekable), so the
                        // receiver always sends offset 0 with fgcm/fgcm2. A non-zero offset under AEAD
                        // is malformed — reject it here rather than seal from a wrong chunk index that
                        // only fails later at the receiver's tag check. Mirrors the Android guard.
                        if (fgcm && offset != 0L) { clientSocket.close(); return@thread }
                        onResumeReceived(sessionId, fileIndex, offset, ackExpected, fgcm, fgcm2, clientSocket)
                    } else {
                        clientSocket.close()
                    }
                } else if (header.startsWith("CANCEL_TRANSFER|")) {
                    val parts = header.split("|")
                    if (parts.size >= 2) onCancelReceived(parts[1])
                    clientSocket.close()
                } else if (header.startsWith("DECLINE_OFFER|")) {
                    val parts = header.split("|")
                    if (parts.size >= 2) onCancelReceived(parts[1])
                    clientSocket.close()
                } else if (header.startsWith("P2P_HELLO|")) {
                    // Off-grid "send via QR": an Android phone scanned our QR and
                    // dialed in. Hand the socket to the controller's QR host, which runs
                    // the handshake and streams the files — it owns the socket lifecycle.
                    onP2pHelloReceived(clientSocket, header)
                } else if (header.startsWith("P2P_BUSY")) {
                    // P2P_BUSY is a receiver-side reply; Windows is the sender, so ignore.
                    clientSocket.close()
                } else {
                    System.err.println("Unknown transfer protocol header: $header")
                    clientSocket.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                try { clientSocket.close() } catch (e: Exception) {}
            }
        }
    }

}
