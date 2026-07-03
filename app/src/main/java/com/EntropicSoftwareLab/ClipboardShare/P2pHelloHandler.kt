package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.SocketConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.SessionCrypto
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag



import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

object P2pHelloHandler {
    internal fun handleP2pHello(context: Context, connection: TransportConnection, header: String) {
        TransportDiag.log("TX: P2P_HELLO received from ${connection.remoteIdentifier} â€” validating")
        val ctx = TransferDaemon.p2pContext
        if (ctx == null) {
            // A receiver hosting as GO can dial its own GO IP and reach THIS server (no send context
            // here). Name that case so it isn't mis-triaged as "sender closed / token mismatch".
            val remote = connection.remoteIdentifier
            if (remote.isNotEmpty() && remote in Net.localIpv4Addresses()) {
                TransportDiag.log("TX: P2P_HELLO dropped â€” self-connection (dialed our own GO IP $remote)")
            } else {
                TransportDiag.log("TX: P2P_HELLO dropped â€” no active send context (TransferDaemon.p2pContext=null)")
            }
            try { connection.close() } catch (_: Exception) {}
            return
        }

        val parts = header.split('|')
        if (parts.size < 5) {
            TransportDiag.log("TX: P2P_HELLO dropped â€” malformed (${parts.size} fields)")
            try { connection.close() } catch (_: Exception) {}
            return
        }

        if (ctx.busy) {
            TransportDiag.log("TX: P2P_HELLO â€” already busy with another peer â†’ sent P2P_BUSY")
            // A peer is already in HANDSHAKE/TRANSFERRING (edge case 9).
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
            return
        }

        val tokenBytes: ByteArray
        val receiverPubKey: ByteArray
        val helloHmac: ByteArray
        try {
            tokenBytes = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
            receiverPubKey = android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP)
            helloHmac = android.util.Base64.decode(parts[4], android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            TransportDiag.log("TX: P2P_HELLO dropped â€” base64 decode failed")
            try { connection.close() } catch (_: Exception) {}
            return
        }
        val receiverName = parts[3].ifEmpty { "Unknown Device" }

        // Token comparison must be constant-time (stale/expired tokens are
        // silently ignored â€” edge case 8: receiver times out client-side).
        if (!java.security.MessageDigest.isEqual(tokenBytes, ctx.token)) {
            TransportDiag.log("TX: P2P_HELLO dropped â€” TOKEN mismatch (stale QR / wrong session)")
            try { connection.close() } catch (_: Exception) {}
            return
        }

        // K = SHA-256(ECDH(senderPriv, receiverPub)) â€” same derivation as pairing.
        val sharedKey = try {
            ctx.ecdh.deriveSecrets(receiverPubKey).first
        } catch (_: Exception) {
            TransportDiag.log("TX: P2P_HELLO dropped â€” ECDH key derivation failed")
            try { connection.close() } catch (_: Exception) {}
            return
        }

        val expectedHmac = SessionCrypto.hmacSha256(sharedKey, tokenBytes)
        if (!java.security.MessageDigest.isEqual(helloHmac, expectedHmac)) {
            TransportDiag.log("TX: P2P_HELLO dropped â€” HMAC mismatch (key disagreement)")
            try { connection.close() } catch (_: Exception) {}
            return
        }
        TransportDiag.log("TX: P2P_HELLO OK from '$receiverName' â†’ sending P2P_WELCOME + OFFER")

        ctx.busy = true
        val sessionId = UUID.randomUUID().toString()

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
                TransportDiag.log("TX: P2P_WELCOME+OFFER+MANIFEST flushed â€” awaiting ACCEPT_OFFER (30s)")

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

                TransferHistoryManager.addRecord(context, TransferRecord(
                    sessionId = sessionId,
                    deviceName = receiverName,
                    direction = "outgoing",
                    fileNames = ctx.manifest.map { it.first.substringAfterLast('/') },
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
                // receiver appends "|ack" â€” it will send TRANSFER_COMPLETE once everything is
                // on disk, so we hold off declaring completion until then.
                val reply = TransferUtils.readUntilNewline(connection.input)
                val replyParts = reply.split('|')
                if (replyParts.getOrNull(0) != "ACCEPT_OFFER" || replyParts.getOrNull(1) != sessionId) {
                    TransportDiag.log("TX: expected ACCEPT_OFFER, got '${reply.take(24)}' â†’ aborting")
                    TransferDaemon.removeActiveSession(sessionId)
                    TransferHistoryManager.updateStatus(context, sessionId, "declined")
                    ctx.onTransferError(false)
                    return@launch
                }
                val ackExpected = replyParts.getOrNull(2) == "ack"

                TransportDiag.log("TX: ACCEPT_OFFER received â†’ streaming files")
                TransferHistoryManager.updateStatus(context, sessionId, "transferring")
                val myGen = session.sendGeneration.incrementAndGet()

                try {
                    UploadEngine.streamSessionFiles(context, session, outStream, sendGen = myGen)
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
                        // Only the current send generation may pause the session â€” a stale write here
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
}

