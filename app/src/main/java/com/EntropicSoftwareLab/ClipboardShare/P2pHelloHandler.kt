package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.SocketConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.OffGridHandshake
import com.EntropicSoftwareLab.ClipboardShare.transport.SessionCrypto
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag



import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import android.util.Base64

object P2pHelloHandler {
    internal fun handleP2pHello(context: Context, connection: TransportConnection, header: String) {
        TransportDiag.log("TX: P2P_HELLO received from ${connection.remoteIdentifier} — validating")
        val ctx = TransferDaemon.p2pContext
        if (ctx == null) {
            // A receiver hosting as GO can dial its own GO IP and reach THIS server (no send context
            // here). Name that case so it isn't mis-triaged as "sender closed / token mismatch".
            val remote = connection.remoteIdentifier
            if (remote.isNotEmpty() && remote in Net.localIpv4Addresses()) {
                TransportDiag.log("TX: P2P_HELLO dropped — self-connection (dialed our own GO IP $remote)")
            } else {
                TransportDiag.log("TX: P2P_HELLO dropped — no active send context (TransferDaemon.p2pContext=null)")
            }
            try { connection.close() } catch (_: Exception) {}
            return
        }

        val parts = header.split('|')
        if (parts.size < 5) {
            TransportDiag.log("TX: P2P_HELLO dropped — malformed (${parts.size} fields)")
            try { connection.close() } catch (_: Exception) {}
            return
        }

        if (ctx.busy) {
            replyBusy(connection)
            return
        }

        val tokenBytes: ByteArray
        val receiverPubKey: ByteArray
        val helloHmac: ByteArray
        try {
            tokenBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            receiverPubKey = Base64.decode(parts[2], Base64.NO_WRAP)
            helloHmac = Base64.decode(parts[4], Base64.NO_WRAP)
        } catch (_: Exception) {
            TransportDiag.log("TX: P2P_HELLO dropped — base64 decode failed")
            try { connection.close() } catch (_: Exception) {}
            return
        }
        val receiverName = parts[3].ifEmpty { "Unknown Device" }

        // Token comparison must be constant-time (stale/expired tokens are
        // silently ignored — edge case 8: receiver times out client-side).
        if (!java.security.MessageDigest.isEqual(tokenBytes, ctx.token)) {
            TransportDiag.log("TX: P2P_HELLO dropped — TOKEN mismatch (stale QR / wrong session)")
            try { connection.close() } catch (_: Exception) {}
            return
        }

        // K = SHA-256(ECDH(senderPriv, receiverPub)) — same derivation as pairing.
        val sharedKey = try {
            ctx.ecdh.deriveSecrets(receiverPubKey).first
        } catch (_: Exception) {
            TransportDiag.log("TX: P2P_HELLO dropped — ECDH key derivation failed")
            try { connection.close() } catch (_: Exception) {}
            return
        }

        val expectedHmac = SessionCrypto.hmacSha256(sharedKey, tokenBytes)
        if (!java.security.MessageDigest.isEqual(helloHmac, expectedHmac)) {
            TransportDiag.log("TX: P2P_HELLO dropped — HMAC mismatch (key disagreement)")
            try { connection.close() } catch (_: Exception) {}
            return
        }
        TransportDiag.log("TX: P2P_HELLO OK from '$receiverName' → sending P2P_WELCOME + OFFER")

        // Claim atomically: the check above is a fast path, but two dials validated in parallel (a
        // receiver retrying while its first attempt is still in flight) both passed it, and each
        // then ran a session — one of which waited out its 30 s ACCEPT_OFFER read and reported an
        // error to the sender's screen while the real transfer was running.
        val claimed = synchronized(ctx) { if (ctx.busy) false else { ctx.busy = true; true } }
        if (!claimed) {
            replyBusy(connection)
            return
        }
        val sessionId = UUID.randomUUID().toString()
        // Set once the session is registered; lets the outer catch unregister a session that never
        // got as far as streaming.
        var registered: TransferSession? = null

        TransferDaemon.daemonScope.launch(Dispatchers.IO) {
            try {
                connection.setReadTimeout(30000)
                val outStream = connection.output

                val ack = SessionCrypto.hmacSha256(sharedKey, tokenBytes + "ack".toByteArray(Charsets.UTF_8))
                val welcome = "P2P_WELCOME|$sessionId|${android.util.Base64.encodeToString(ack, android.util.Base64.NO_WRAP)}\n"
                outStream.write(welcome.toByteArray(Charsets.UTF_8))

                // Existing single-socket sequence: OFFER, MANIFEST.
                // Sanitize the device name so a '|'/newline in it can't desync the positional parse.
                val offerHeader = "OFFER|$sessionId|${TransferDaemon.customDeviceName.replace('|', ' ').replace('\n', ' ').replace('\r', ' ')}|${ctx.totalFiles}\n"
                var manifestStr = "MANIFEST|"
                for (item in ctx.manifest) {
                    manifestStr += "${item.first}:${item.second}|"
                }
                manifestStr += "\n"
                outStream.write(offerHeader.toByteArray(Charsets.UTF_8))
                outStream.write(manifestStr.toByteArray(Charsets.UTF_8))
                outStream.flush()
                TransportDiag.log("TX: P2P_WELCOME+OFFER+MANIFEST flushed — awaiting ACCEPT_OFFER (30s)")

                val session = TransferSession(
                    sessionId = sessionId,
                    senderName = TransferDaemon.customDeviceName,
                    totalFiles = ctx.totalFiles,
                    manifest = ctx.manifest,
                    fileUris = ctx.fileUris,
                    targetIp = connection.remoteIdentifier,
                    isIncoming = false,
                    transport = "p2p"
                )
                TransferDaemon.putActiveSession(sessionId, session)
                registered = session

                TransferHistoryManager.addRecord(context, TransferRecord(
                    sessionId = sessionId,
                    deviceName = receiverName,
                    direction = "outgoing",
                    fileNames = ctx.manifest.map { TransferUtils.manifestDisplayLeaf(it.first) },
                    totalBytes = ctx.manifest.sumOf { it.second },
                    timestamp = System.currentTimeMillis(),
                    status = "pending",
                    filesCompleted = 0,
                    totalFiles = ctx.totalFiles,
                    fileSizes = ctx.manifest.map { it.second },
                    currentFileOffset = 0L,
                    transport = "p2p"
                ))

                ctx.onPeerConnected(receiverName)

                // Receiver replies ACCEPT_OFFER on the same socket. A COMPLETION_ACK-capable
                // receiver appends "|ack" — it will send TRANSFER_COMPLETE once everything is
                // on disk, so we hold off declaring completion until then.
                val reply = TransferUtils.readUntilNewline(connection.input)
                val replyParts = reply.split('|')
                if (replyParts.getOrNull(0) != "ACCEPT_OFFER" || replyParts.getOrNull(1) != sessionId) {
                    TransportDiag.log("TX: expected ACCEPT_OFFER, got '${reply.take(24)}' → aborting")
                    TransferDaemon.removeActiveSession(sessionId)
                    TransferHistoryManager.updateStatus(context, sessionId, "declined")
                    ctx.onTransferError(false)
                    return@launch
                }
                // Trailing flags are order-independent ("ack" and/or "seal"), so match by membership
                // rather than by position — positional indexing broke the moment a second optional
                // flag existed. "seal" is only ever sent by a receiver that saw our SEALED_STREAM
                // cap, so an old receiver silently keeps the pre-3.7.7 plaintext stream.
                val replyFlags = replyParts.drop(2)
                val ackExpected = replyFlags.contains("ack")
                val sealKey = if (replyFlags.contains(OffGridHandshake.ACCEPT_FLAG_SEAL)) sharedKey else null
                // Kept for an off-grid RESUME_REQUEST, which arrives from a never-paired IP and so
                // has no pairing key to seal with — FileTransferServer resumes with this instead.
                session.p2pSealKey = sealKey
                session.fileV2Negotiated = sealKey != null

                // See OffGridHandshake's twin line: a plaintext fallback is externally
                // indistinguishable from a sealed transfer, so the outcome must be stated.
                TransportDiag.log(
                    if (sealKey != null) "TX: ACCEPT_OFFER received → streaming files, off-grid stream SEALED (AES-256-GCM)"
                    else "TX: ACCEPT_OFFER received → streaming files, off-grid stream PLAINTEXT — receiver did not request the seal; update the receiving device to encrypt off-grid transfers"
                )
                TransferHistoryManager.updateStatus(context, sessionId, "transferring")
                val myGen = session.sendGeneration.incrementAndGet()

                try {
                    // Raw ECDH secret as fileKey — streamSessionFiles applies deriveKeyV2 itself, so
                    // pre-deriving would derive twice and the receiver's tag check would fail.
                    UploadEngine.streamSessionFiles(
                        context, session, outStream,
                        fileKey = sealKey, sendGen = myGen, useFileV2 = sealKey != null,
                    )
                    if (ackExpected && !UploadEngine.awaitCompletionAck(connection, sessionId)) {
                        // Bytes were written but the receiver never confirmed delivery (late drop).
                        // Keep the session paused so the receiver's resume can reconnect to it.
                        session.isPaused = true
                        session.lastUpdated = System.currentTimeMillis()
                        TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                        ctx.onTransferError(true)
                    } else {
                        TransferDaemon.removeActiveSession(sessionId)
                        TransferHistoryManager.updateStatus(context, sessionId, "completed", session.fileUris.size)
                        ctx.onTransferComplete(sessionId)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (session.isCancelled) {
                        TransferHistoryManager.updateStatus(context, sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
                        ctx.onTransferError(false)
                    } else if (session.sendGeneration.get() == myGen) {
                        // Only the current send generation may pause the session — a stale write here
                        // (this send timed out ~20 s after a drop) must not clobber a fresh resume.
                        session.isPaused = true
                        session.lastUpdated = System.currentTimeMillis()
                        TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                        ctx.onTransferError(true)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                TransportDiag.log("TX: P2P handshake error after WELCOME: ${e.javaClass.simpleName} ${e.message ?: ""}")
                // Reached only when the socket died before ACCEPT_OFFER (streaming errors are caught
                // inside). The session was registered and recorded as pending but can never run:
                // left behind, it lingered in activeSessions — where the next transfer's hand-off
                // could pick it up as "the" outgoing p2p session — and in history as pending forever.
                registered?.let {
                    TransferDaemon.removeActiveSession(it.sessionId)
                    TransferHistoryManager.updateStatus(context, it.sessionId, "failed")
                }
                ctx.onTransferError(false)
            } finally {
                try { connection.close() } catch (_: Exception) {}
                ctx.busy = false
                // If the sender backgrounded this transfer (left the QR/NFC screen), release the
                // Wi-Fi Direct group it held now that the upload reached a terminal state. No-op when
                // the screen is still resident (never handed off).
                com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridLiveTransfer.finishIfArmed(sessionId)
            }
        }
    }

    /** A peer is already in HANDSHAKE/TRANSFERRING (edge case 9). */
    private fun replyBusy(connection: TransportConnection) {
        TransportDiag.log("TX: P2P_HELLO — already busy with another peer → sent P2P_BUSY")
        TransferDaemon.daemonScope.launch(Dispatchers.IO) {
            try {
                connection.output.run {
                    write("P2P_BUSY\n".toByteArray(Charsets.UTF_8))
                    flush()
                }
            } catch (_: Exception) {
            } finally {
                try { connection.close() } catch (_: Exception) {}
            }
        }
    }
}
