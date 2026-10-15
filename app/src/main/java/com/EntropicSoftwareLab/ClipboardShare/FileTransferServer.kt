package com.EntropicSoftwareLab.ClipboardShare

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.EntropicSoftwareLab.ClipboardShare.transport.DowngradePolicy
import com.EntropicSoftwareLab.ClipboardShare.transport.OffGridResumeAuth
import com.EntropicSoftwareLab.ClipboardShare.transport.SessionCrypto
import com.EntropicSoftwareLab.ClipboardShare.transport.SocketConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import android.util.Base64
import android.widget.Toast

/**
 * The LAN TCP file-transfer server: binds 0.0.0.0:[Ports.FILE_TRANSFER], accepts connections, and
 * dispatches each control frame (RESUME_REQUEST / ACCEPT_OFFER / DECLINE_OFFER / CANCEL_TRANSFER /
 * OFFER) after the paired-peer LAN trust gate and the AUTH2 admission proof. Off-grid
 * P2P_HELLO is recognised first and handed to [P2pHelloHandler] (its ECDH+HMAC admission is stronger
 * than IP pairing and it arrives from an ephemeral, never-paired IP), so it bypasses the gate.
 *
 * Extracted verbatim from TransferDaemon; session/discovery state is reached through TransferDaemon's
 * accessors and the streaming/download work is delegated to [UploadEngine] / [DownloadEngine]. The
 * wire format is unchanged and stays byte-identical to the Windows client.
 */
object FileTransferServer {

    /** Bind the listener and run the accept loop on [daemonScope]; each connection is handled on its own coroutine. */
    fun start(context: Context, daemonScope: CoroutineScope) {
        daemonScope.launch {
            var serverSocket: ServerSocket? = null
            try {
                val server = ServerSocket()
                serverSocket = server
                server.reuseAddress = true
                // Advertise a large TCP window in the handshake so accepted sockets inherit it.
                runCatching { server.receiveBufferSize = SOCKET_BUFFER_BYTES }
                server.bind(InetSocketAddress("0.0.0.0", Ports.FILE_TRANSFER))

                while (isActive) {
                    try {
                        val clientSocket = server.accept()
                        clientSocket.tuneForBulkTransfer()
                        // Bound the connection so a peer that connects but never sends a
                        // newline can't pin a handler forever; 30s of total silence on a
                        // live transfer means a dead link. Handle off the accept loop so a
                        // slow/hostile client never blocks new connections.
                        clientSocket.soTimeout = 30000
                        daemonScope.launch { handleIncomingConnection(context, clientSocket) }
                    } catch (e: Exception) {
                        // A transient accept() error (e.g. EMFILE) must not permanently kill
                        // the listener; only stop when the socket itself is closed.
                        if (!isActive || server.isClosed) break
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleIncomingConnection(context: Context, socket: Socket) {
        try {
            val inputStream = socket.getInputStream()
            val batchHeader = TransferUtils.readUntilNewline(inputStream)

            // Off-grid handshake authenticates itself with the QR token + ECDH HMAC (inside
            // handleP2pHello, which is stronger than IP pairing) and arrives from an ephemeral
            // Wi-Fi Direct IP that is never a paired node. It MUST be dispatched BEFORE the LAN
            // trust gate below — this is the "separate P2P/QR-NFC path" the gate was always meant
            // to leave unaffected. Gating it ahead of this read closed the socket before the HELLO
            // was even parsed, which silently broke every off-grid Wi-Fi Direct transfer
            // (TCP connected, then HANDSHAKE_FAILED with no P2P_WELCOME).
            if (batchHeader.startsWith("P2P_HELLO|")) {
                P2pHelloHandler.handleP2pHello(context, SocketConnection(socket), batchHeader)
                return
            }

            // Off-grid resume exemption (XP-05): an off-grid (Wi-Fi Direct) transfer is admitted by the
            // ECDH + QR-token P2P_HELLO handshake, not by IP pairing, and on a mid-transfer drop its
            // receiver reconnects to resume from an ephemeral, never-paired IP — the same reason
            // P2P_HELLO above bypasses this gate. A RESUME_REQUEST whose sessionId matches a live
            // *outgoing off-grid* session may be that peer resuming, so let it past the IP gate. It is
            // NOT trusted yet: the sessionId travels in the clear in P2P_WELCOME, so the RESUME branch
            // below makes it prove the session's ECDH secret ([OffGridResumeAuth]) before any byte
            // flows. Scoped to transport=="p2p": LAN resumes are unaffected — their peer IP is paired
            // and still runs the full gate. Without this, off-grid resume always failed.
            val isOffGridResume = batchHeader.startsWith("RESUME_REQUEST|") && run {
                val sid = batchHeader.split('|').getOrNull(1)
                val s = sid?.let { TransferDaemon.activeSessions[it] }
                s != null && !s.isIncoming && s.transport == "p2p"
            }

            // Trust gate: only paired devices may push files / drive session control over the LAN
            // — same check ClipboardService uses for clipboard pushes. Closes the "any host on the
            // network can push files" hole. (Off-grid P2P_HELLO and off-grid resume are exempt above.)
            val senderIp = (socket.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress ?: ""
            if (!isOffGridResume && (senderIp.isEmpty() || SecurityVault.getKeyForNode(context, senderIp) == null)) {
                // If the trace shows this during an off-grid send, the receiver's HELLO arrived
                // garbled/partial (didn't match "P2P_HELLO|") and got caught by the LAN trust gate.
                TransportDiag.log("TX: incoming '${batchHeader.take(16)}' from $senderIp blocked — not a paired LAN node")
                socket.close()
                return
            }

            if (batchHeader.startsWith("RESUME_REQUEST|")) {
                val parts = batchHeader.split('|')
                if (parts.size >= 4) {
                    val sessionId = parts[1]
                    // Network-supplied tokens: parse defensively so malformed input aborts
                    // this connection instead of throwing NumberFormatException.
                    val fileIndex = parts[2].toIntOrNull()
                    val offset = parts[3].toLongOrNull()
                    if (fileIndex == null || offset == null) { socket.close(); return }
                    // Receiver flags after the offset, order-independent: "ack" → wait for the delivery
                    // confirmation before declaring complete; "fgcm" → the bulk stream is FileSeal-sealed.
                    val flags = parts.drop(4)
                    if (isOffGridResume) {
                        if (!performOffGridResumeChallenge(socket, inputStream, sessionId, flags)) { socket.close(); return }
                    } else if (!performAcceptAuth2Challenge(context, socket, inputStream, senderIp)) {
                        socket.close(); return
                    }
                    val ackExpected = flags.contains("ack")
                    // Both "fgcm" and "fgcm2" request an authenticated stream; "fgcm2" additionally
                    // selects the v2 FileSeal (per-session key + session/file-bound AAD).
                    val resumeUseV2: Boolean
                    val resumeFileKey = try {
                        val key = TransferKeys.senderFileKey(context, senderIp, flags.contains("fgcm") || flags.contains("fgcm2"))
                        // Gate v2 by the same TOFU pin the receiver enforces: a v2-pinned peer whose
                        // RESUME stripped "fgcm2"→"fgcm" is a downgrade → throw, not seal v1 silently.
                        resumeUseV2 = TransferKeys.senderUseFileV2(context, senderIp, flags.contains("fgcm2"))
                        key
                    } catch (e: DowngradeBlockedException) {
                        TransportDiag.log("TX: $e — refusing plaintext resume")
                        socket.close(); return
                    }
                    val resumeAead = flags.contains("fgcm") || flags.contains("fgcm2")

                    val session = TransferDaemon.activeSessions[sessionId]
                    if (session != null && !session.isIncoming) {
                        // Bounds-check the network-supplied resume position before trusting it, so a
                        // bad (fileIndex, offset) can't make the sender skip past or re-send file data.
                        // AEAD resume restarts the file (records aren't byte-seekable): the receiver
                        // always requests offset 0 with fgcm/fgcm2, so a non-zero offset under AEAD is
                        // malformed — reject it up front instead of computing a wrong chunk index that
                        // only fails later at the GCM tag.
                        if (fileIndex < 0 || fileIndex >= session.fileUris.size || offset < 0L ||
                            (resumeAead && offset != 0L)) {
                            socket.close(); return
                        }
                        session.isPaused = false
                        session.currentFileIndex = fileIndex
                        session.currentOffset = offset
                        // Reflect the resume on the sender immediately (mirrors ACCEPT_OFFER) so the
                        // history detail flips Paused→Transferring now, not only on completion.
                        TransferHistoryManager.updateStatus(context, sessionId, "transferring")
                        val myGen = session.sendGeneration.incrementAndGet()

                        TransferDaemon.daemonScope.launch(Dispatchers.IO) {
                            try {
                                val outStream = socket.getOutputStream()

                                val ctx = context
                                val senderIntent = Intent(ctx, MainActivity::class.java).apply {
                                    action = Intent.ACTION_SEND
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }
                                val pendingSenderIntent = PendingIntent.getActivity(ctx, 2, senderIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                val notifMgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                val senderNotif = NotificationCompat.Builder(ctx, NotificationChannels.TRANSFER_REQUEST)
                                    .setSmallIcon(R.mipmap.ic_launcher)
                                    .setContentTitle(ctx.getString(R.string.transfer_sending_resumed_title))
                                    .setContentText(ctx.getString(R.string.transfer_sending_resumed_text))
                                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                                    .setContentIntent(pendingSenderIntent)
                                    .setAutoCancel(true)
                                    .build()
                                notifMgr.notify(65435, senderNotif)

                                // Expose the live socket so a CANCEL_TRANSFER can close it and unblock
                                // a write stuck on a dead link (rather than waiting out the TCP timeout).
                                session.activeSendSocket = socket
                                UploadEngine.streamSessionFiles(context, session, outStream, resumeFileKey, myGen, resumeUseV2)

                                if (ackExpected && !UploadEngine.awaitCompletionAck(socket, sessionId)) {
                                    // Resent bytes never confirmed — pause and keep the session so
                                    // the receiver can resume again rather than hit a dead session.
                                    session.isPaused = true
                                    session.lastUpdated = System.currentTimeMillis()
                                    TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, ctx.getString(R.string.transfer_resumed_complete), Toast.LENGTH_LONG).show()
                                    }
                                    TransferHistoryManager.updateStatus(context, sessionId, "completed", session.fileUris.size)
                                    // Clean up sender session on success
                                    TransferDaemon.removeActiveSession(sessionId)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                if (session.isCancelled) {
                                    TransferHistoryManager.updateStatus(context, sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
                                } else if (session.sendGeneration.get() == myGen) {
                                    // A newer resume may have superseded this stale send — only the
                                    // current generation may mark the session paused.
                                    session.isPaused = true
                                    session.lastUpdated = System.currentTimeMillis()
                                    TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                                }
                            } finally {
                                if (session.activeSendSocket === socket) session.activeSendSocket = null
                                try { socket.close() } catch(e:Exception){}
                            }
                        }
                    }
                }
                return
            }

            if (batchHeader.startsWith("ACCEPT_OFFER|")) {
                val parts = batchHeader.split('|')
                if (parts.size >= 2) {
                    val sessionId = parts[1]
                    val session = TransferDaemon.activeSessions[sessionId]
                    if (session != null && !session.isIncoming) {
                        if (!performAcceptAuth2Challenge(context, socket, inputStream, senderIp)) { socket.close(); return }
                        session.isPaused = false
                        // Receiver appended "fgcm" ⇒ seal the bulk stream with FileSeal under the shared
                        // pairing key (the trust gate above guarantees we hold it). Absent ⇒ plaintext,
                        // unless we'd previously sealed for this peer (pin) → refuse the downgrade.
                        // Both "fgcm" and "fgcm2" request an authenticated stream; "fgcm2" additionally
                        // selects the v2 FileSeal (per-session key + session/file-bound AAD).
                        val acceptFlags = parts.drop(2)
                        val useV2: Boolean
                        val fileKey = try {
                            val key = TransferKeys.senderFileKey(context, senderIp, acceptFlags.contains("fgcm") || acceptFlags.contains("fgcm2"))
                            // Gate v2 by the same TOFU pin the receiver enforces: a v2-pinned peer whose
                            // ACCEPT stripped "fgcm2"→"fgcm" is a downgrade → throw, not seal v1 silently.
                            useV2 = TransferKeys.senderUseFileV2(context, senderIp, acceptFlags.contains("fgcm2"))
                            key
                        } catch (e: DowngradeBlockedException) {
                            TransportDiag.log("TX: $e — refusing plaintext stream")
                            socket.close(); return
                        }
                        val myGen = session.sendGeneration.incrementAndGet()
                        TransferHistoryManager.updateStatus(context, sessionId, "transferring")

                        TransferDaemon.daemonScope.launch(Dispatchers.IO) {
                            try {
                                val outStream = socket.getOutputStream()
                                // Expose the live socket so a CANCEL_TRANSFER can close it and unblock a
                                // write stuck on a dead link (rather than waiting out the TCP timeout).
                                session.activeSendSocket = socket
                                UploadEngine.streamSessionFiles(context, session, outStream, fileKey, myGen, useV2)
                                TransferDaemon.removeActiveSession(sessionId)
                                TransferHistoryManager.updateStatus(context, sessionId, "completed", session.fileUris.size)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                if (session.isCancelled) {
                                    TransferHistoryManager.updateStatus(context, sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
                                } else if (session.sendGeneration.get() == myGen) {
                                    session.isPaused = true
                                    TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                                }
                            } finally {
                                if (session.activeSendSocket === socket) session.activeSendSocket = null
                                try { socket.close() } catch(e:Exception){}
                            }
                        }
                    }
                }
                return
            }

            if (batchHeader.startsWith("DECLINE_OFFER|")) {
                val parts = batchHeader.split('|')
                if (parts.size >= 2) {
                    val sessionId = parts[1]
                    val session = TransferDaemon.removeActiveSession(sessionId)
                    if (session != null && !session.isIncoming) {
                        session.isCancelled = true
                        // A decline can also arrive mid-stream (older receivers send it for a live
                        // cancel) — close the streaming socket like CANCEL_TRANSFER does, so a
                        // blocked write aborts now instead of after the ~20 s TCP write timeout.
                        session.activeSendSocket?.let { s -> runCatching { s.close() } }
                        TransferHistoryManager.updateStatus(context, sessionId, "declined")
                    }
                }
                socket.close()
                return
            }

            if (batchHeader.startsWith("CANCEL_TRANSFER|")) {
                val parts = batchHeader.split('|')
                if (parts.size >= 2) {
                    val sessionId = parts[1]
                    val session = TransferDaemon.removeActiveSession(sessionId)
                    if (session != null) {
                        session.isCancelled = true
                        // Close the live streaming socket (if any) so a send blocked on a dead-link
                        // write is interrupted now instead of hanging until the ~20 s TCP write timeout.
                        session.activeSendSocket?.let { s -> runCatching { s.close() } }
                        TransferHistoryManager.updateStatus(context, sessionId, "cancelled")
                    }
                }
                socket.close()
                return
            }

            // OFFER is the only admission frame. The legacy BATCH header (no sender emits it any more)
            // streamed straight to disk with no AUTH2 proof and no accept prompt, so it is refused here.
            if (!batchHeader.startsWith("OFFER|")) {
                socket.close()
                return
            }

            // OFFER|sessionId|senderName|totalFiles. The sender needs its own sessionId back for
            // ACCEPT/RESUME, so an offer without one can never complete; reject it outright.
            val parts = batchHeader.split('|')
            if (parts.size < 4) { socket.close(); return }
            val sessionId = parts[1]
            val senderName = parts[2]
            // Network-supplied count: bail out of this connection on malformed input
            // rather than throwing NumberFormatException.
            val totalFiles = parts[3].toIntOrNull() ?: run { socket.close(); return }

            val manifestHeader = TransferUtils.readUntilNewline(inputStream)
            // Shared parser (skips empty-name ":size" entries via lastColonIndex > 0) so a
            // degenerate/hostile manifest yields the SAME file list here, on the off-grid path,
            // and on Windows. An earlier local copy accepted ":size" that Windows dropped, which
            // desynced the positional byte stream across platforms.
            val fileList = TransferUtils.parseManifestEntries(manifestHeader).toMutableList()

            val newSession = TransferSession(
                sessionId = sessionId,
                senderName = senderName,
                totalFiles = totalFiles,
                manifest = fileList,
                targetIp = socket.inetAddress.hostAddress ?: "",
                isIncoming = true
            )

            val outStream = socket.getOutputStream()

            // AUTH2 admission proof: the plain trust gate above only checks that senderIp has a
            // stored pairing key, so a host spoofing a paired peer's IP (without the key) could still
            // raise this popup. If the sender advertises AUTH2, require it to PROVE possession of the
            // shared pairing key via an HMAC challenge-response BEFORE we ack the offer or show the
            // popup. A sender that doesn't advertise AUTH2 (old build) skips this — byte-identical to
            // before. senderIp is the same source address the trust gate validated above.
            //
            // Anti-downgrade (XP-DEF-01): supportsAuth2 rides the unauthenticated discovery, so a
            // spoofed advert could strip it and re-open the IP-spoof hole. TOFU: once a peer has
            // proven AUTH2 we pin it (below), and a later strip is a downgrade — fail closed here.
            val auth2Decision = DowngradePolicy.decide(
                peerOffersAead = TransferDaemon.discoveredNodes[senderIp]?.supportsAuth2 == true,
                pinned = SecurityVault.isAuth2Pinned(context, senderIp)
            )
            if (auth2Decision == DowngradePolicy.Decision.BLOCK) {
                TransportDiag.log("TX: OFFER from $senderIp dropped — AUTH2 downgrade blocked")
                socket.close()
                return
            }
            if (auth2Decision == DowngradePolicy.Decision.SEAL) {
                val pairingKey = SecurityVault.getKeyForNode(context, senderIp)
                if (pairingKey == null) {
                    // Trust gate said we hold a key for this IP; if it vanished, fail closed.
                    socket.close()
                    return
                }
                val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                outStream.write("OFFER_CHALLENGE|${android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)}\n".toByteArray(Charsets.UTF_8))
                outStream.flush()
                val proofLine = TransferUtils.readUntilNewline(inputStream)
                val proof = if (proofLine.startsWith("OFFER_PROOF|")) {
                    try { Base64.decode(proofLine.removePrefix("OFFER_PROOF|"), Base64.NO_WRAP) } catch (_: Exception) { null }
                } else null
                val expected = SessionCrypto.hmacSha256(pairingKey, nonce)
                if (proof == null || !java.security.MessageDigest.isEqual(proof, expected)) {
                    // Missing/malformed/wrong proof ⇒ the sender can't prove it holds the key. Drop
                    // the offer silently: no OFFER_RECEIVED, no popup. (An IP-spoofer lands here.)
                    TransportDiag.log("TX: OFFER from $senderIp dropped — AUTH2 proof failed")
                    socket.close()
                    return
                }
                // Proof valid → pin AUTH2 so a later strip on the unauthenticated discovery is caught.
                SecurityVault.pinAuth2(context, senderIp)
            }
            // DowngradePolicy.Decision.PLAINTEXT → legacy peer that never advertised AUTH2: skip the
            // challenge, exactly as before.

            // Register only after AUTH2 passed: the sessionId is sender-chosen, so registering earlier let an
            // unproven offer overwrite (and, on a failed proof, remove) a live session with the same id.
            TransferDaemon.putActiveSession(sessionId, newSession)

            outStream.write("OFFER_RECEIVED\n".toByteArray(Charsets.UTF_8))
            outStream.flush()
            socket.close()

            val requestIntent = Intent(context, TransferRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("SESSION_ID", sessionId)
                putExtra("SENDER_NAME", senderName)
                putExtra("TOTAL_FILES", totalFiles)
            }

            // App open → pop the accept/decline screen right away instead of burying the request
            // in a notification. App backgrounded → raise a high-priority notification (with a
            // full-screen intent so it can still surface on the lock screen). Background activity
            // starts are restricted on API 29+, so only start directly when we are genuinely in
            // the foreground; on any refusal, fall through to the notification.
            if (AppForeground.isForeground) {
                try {
                    context.startActivity(requestIntent)
                    return
                } catch (_: Exception) {
                    // fall through to the notification path below
                }
            }

            val pendingRequest = PendingIntent.getActivity(context, sessionId.hashCode(), requestIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(context, NotificationChannels.TRANSFER_REQUEST)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.transfer_incoming_title))
                .setContentText(context.getString(R.string.transfer_incoming_text, senderName, totalFiles))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pendingRequest)
                .setFullScreenIntent(pendingRequest, true)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(sessionId.hashCode(), notification)
        } catch (e: Exception) {
            // close() can itself throw (already-reset socket); swallow so the handler exits cleanly.
            runCatching { socket.close() }
        }
    }

    /**
     * Accept/resume-direction AUTH2R challenge: the mirror of the OFFER-direction AUTH2 challenge
     * above, symmetric in the opposite role. The plain trust gate in [handleIncomingConnection] only
     * checks that [senderIp] has a stored pairing key — a host spoofing a paired peer's IP (without
     * the key) could otherwise still receive an outbound file stream by sending ACCEPT_OFFER/
     * RESUME_REQUEST. If [senderIp] advertises [TransferDaemon.ACCEPT_AUTH_CAP], require it to PROVE
     * possession of the shared pairing key via an HMAC challenge-response BEFORE any file bytes are
     * sent. A peer that doesn't advertise it (old build, or a legacy pin) skips this — byte-identical
     * to before. TOFU-pinned via [SecurityVault.pinAuth2Accept] so a later capability-strip is a
     * downgrade, not a silent skip.
     *
     * [inputStream] must be the SAME stream [handleIncomingConnection] has already been reading the
     * batch header from (not a fresh `socket.getInputStream()`), so the proof-line read continues
     * from exactly where header parsing left off. Returns false ⇒ caller must close the socket and
     * stop; no popup, no bytes, no state mutated on this session.
     */
    /**
     * Off-grid RESUME_REQUEST admission: the receiver must prove the ECDH secret from this session's
     * P2P_HELLO (see [OffGridResumeAuth]). A receiver that didn't send the flag is too old to answer,
     * so it is refused outright rather than sent a challenge line it would write into its .part file.
     */
    private fun performOffGridResumeChallenge(socket: Socket, inputStream: java.io.InputStream, sessionId: String, flags: List<String>): Boolean {
        val key = TransferDaemon.activeSessions[sessionId]?.p2pAuthKey
        if (key == null) {
            TransportDiag.log("TX: off-grid RESUME for $sessionId dropped — session holds no handshake key")
            return false
        }
        if (OffGridResumeAuth.FLAG !in flags) {
            TransportDiag.log("TX: off-grid RESUME for $sessionId dropped — receiver can't prove the session key (update it, or re-scan)")
            return false
        }
        val ok = try {
            OffGridResumeAuth.challenge(inputStream, socket.getOutputStream(), key, sessionId)
        } catch (_: Exception) { false }
        if (!ok) TransportDiag.log("TX: off-grid RESUME for $sessionId dropped — resume proof failed")
        return ok
    }

    private fun performAcceptAuth2Challenge(context: Context, socket: Socket, inputStream: java.io.InputStream, senderIp: String): Boolean {
        val decision = DowngradePolicy.decide(
            peerOffersAead = TransferDaemon.discoveredNodes[senderIp]?.supportsAuth2Accept == true,
            pinned = SecurityVault.isAuth2AcceptPinned(context, senderIp)
        )
        if (decision == DowngradePolicy.Decision.BLOCK) {
            TransportDiag.log("TX: ACCEPT/RESUME from $senderIp dropped — AUTH2R downgrade blocked")
            return false
        }
        if (decision == DowngradePolicy.Decision.SEAL) {
            val pairingKey = SecurityVault.getKeyForNode(context, senderIp) ?: return false
            val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val outStream = socket.getOutputStream()
            outStream.write("ACCEPT_CHALLENGE|${Base64.encodeToString(nonce, Base64.NO_WRAP)}\n".toByteArray(Charsets.UTF_8))
            outStream.flush()
            val proofLine = TransferUtils.readUntilNewline(inputStream)
            val proof = if (proofLine.startsWith("ACCEPT_PROOF|")) {
                try { Base64.decode(proofLine.removePrefix("ACCEPT_PROOF|"), Base64.NO_WRAP) } catch (_: Exception) { null }
            } else null
            val expected = SessionCrypto.hmacSha256(pairingKey, nonce)
            if (proof == null || !java.security.MessageDigest.isEqual(proof, expected)) {
                TransportDiag.log("TX: ACCEPT/RESUME from $senderIp dropped — AUTH2R proof failed")
                return false
            }
            SecurityVault.pinAuth2Accept(context, senderIp)
        }
        // DowngradePolicy.Decision.PLAINTEXT → legacy peer that never advertised AUTH2R: skip the
        // challenge, exactly as before.
        return true
    }
}
