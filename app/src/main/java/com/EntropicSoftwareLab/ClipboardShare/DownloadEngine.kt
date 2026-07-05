package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.SocketConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.SessionCrypto
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import com.EntropicSoftwareLab.ClipboardShare.transport.FileSeal
import java.net.Socket
import java.net.InetSocketAddress




object DownloadEngine {
    fun executeDownloadStream(context: Context, socket: Socket, session: TransferSession, fileKey: ByteArray? = null) {
        executeDownloadStream(context, SocketConnection(socket), session, fileKey)
    }

    /**
     * V4 §2: the carrier-neutral download path. Protocol bytes are identical
     * over TCP and Bluetooth; only the duplex-stream carrier differs.
     * A non-null [fileKey] (the shared pairing key) means the bulk stream is FileSeal-sealed and is
     * decrypted here; null keeps the legacy plaintext read (off-grid + legacy peers).
     */
    fun executeDownloadStream(context: Context, connection: TransportConnection, session: TransferSession, fileKey: ByteArray? = null) {
        val manifest = session.manifest

        TransferDaemon.daemonScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
            val saveLocationUriString = prefs.getString("save_location_uri", null)
            val saveDir = if (saveLocationUriString != null) {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(saveLocationUriString))
            } else {
                null
            }

            try {
                connection.setReadTimeout(10000) // 10 second network timeout (no-op on BT)
                val inputStream = connection.input
                val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                // v2 (session.fileV2Negotiated, set when we sent "fgcm2") uses the per-session key and
                // the session/file-bound AAD prefix; v1 keeps deriveKey + empty prefix. Must mirror the
                // sender's streamSessionFiles derivation exactly or the AEAD tag fails.
                val aeadKeySpec = fileKey?.let {
                    FileSeal.keySpec(if (session.fileV2Negotiated) FileSeal.deriveKeyV2(it, session.sessionId) else FileSeal.deriveKey(it))
                }

                if (session.localFileNames.isEmpty()) {
                    for (m in manifest) {
                        // Sanitize the manifest-supplied name before it ever hits the filesystem
                        // (path-traversal / reserved chars), matching the Windows receiver (XP-DEF-03).
                        var finalName = TransferUtils.sanitizeIncomingFileName(m.first)
                        if (session.currentOffset == 0L) {
                            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                            // Media names must also be free in the publish destination
                            // (Pictures|Movies/"Clipboard Share"): a name unique only in Download
                            // could be shadowed by a same-named, earlier-published photo when
                            // history's "open" probes the media folder first.
                            val publishDir = TransferUtils.publicMediaDir(TransferUtils.mediaCategory(finalName))
                            finalName = TransferUtils.dedupeName(listOfNotNull(downloadDir, publishDir), finalName)
                        }
                        session.localFileNames.add(finalName)
                    }
                    TransferHistoryManager.updateFileNames(context, session.sessionId, session.localFileNames)
                }

                for (i in session.currentFileIndex until manifest.size) {
                    val (fname, fsize) = manifest[i]
                    val isFolder = fname.endsWith("/")
                    
                    if (isFolder) continue

                    // Name list and manifest can drift (e.g. resumed session); guard the
                    // index so a mismatch skips the entry instead of throwing IndexOutOfBounds.
                    val finalName = session.localFileNames.getOrNull(i) ?: continue

                    // Stream into a ".part" sidecar; promote it to the real name only once every byte
                    // is on disk, so a mid-transfer drop leaves a .part instead of a truncated real file.
                    val partName = "$finalName.part"
                    val append = session.currentOffset > 0
                    val saveTree = saveDir
                    var partDoc: androidx.documentfile.provider.DocumentFile? = null
                    var partFile: java.io.File? = null
                    val outputStream = if (saveTree != null && saveTree.canWrite()) {
                        val doc = saveTree.findFile(partName) ?: saveTree.createFile("*/*", partName)
                        partDoc = doc
                        if (doc != null) {
                            context.contentResolver.openOutputStream(doc.uri, if (append) "wa" else "w")
                        } else null
                    } else {
                        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val pf = java.io.File(downloadDir, partName)
                        partFile = pf
                        java.io.FileOutputStream(pf, append)
                    }
                    
                    // A null stream (SAF createFile failed, permission lost) must abort. The old
                    // `outputStream?.use{}` silently skipped writing this file's bytes while still
                    // advancing currentFileIndex/currentOffset, so the receiver kept reading this
                    // file's promised bytes out of the NEXT file — corrupting the rest of the batch.
                    if (outputStream == null) throw java.io.IOException("Cannot open output for $finalName")
                    outputStream.use { out ->
                        if (aeadKeySpec != null) {
                            // AEAD: read GCM records, decrypt, and append plaintext until we have the
                            // file's plaintext bytes. AEAD resume restarts the file (currentOffset==0).
                            var remaining = fsize - session.currentOffset
                            var chunkIndex = session.currentOffset / FileSeal.CHUNK_SIZE
                            // Same 0-based manifest index [i] the sender binds into the v2 AAD prefix.
                            val aadPrefix = if (session.fileV2Negotiated) FileSeal.v2AadPrefix(session.sessionId, i.toLong()) else ByteArray(0)
                            while (remaining > 0) {
                                if (session.isCancelled) throw Exception("Transfer cancelled")
                                val pt = FileSeal.openChunk(aeadKeySpec, inputStream, chunkIndex, remaining, aadPrefix)
                                out.write(pt)
                                session.currentOffset += pt.size
                                remaining -= pt.size
                                chunkIndex++
                            }
                        } else {
                            var remainingBytes = fsize - session.currentOffset
                            while (remainingBytes > 0) {
                                val toRead = if (remainingBytes > buffer.size) buffer.size else remainingBytes.toInt()
                                val read = inputStream.read(buffer, 0, toRead)
                                if (read == -1) throw java.io.IOException("Stream closed unexpectedly")
                                out.write(buffer, 0, read)
                                session.currentOffset += read
                                remainingBytes -= read
                            }
                        }
                    }

                    // Promote the completed .part to its final name (replacing any stale same-name file).
                    partDoc?.let { doc ->
                        saveTree?.findFile(finalName)?.delete()
                        doc.renameTo(finalName)
                    }
                    partFile?.let { pf ->
                        val finalFile = java.io.File(pf.parentFile, finalName)
                        if (finalFile.exists()) finalFile.delete()
                        if (pf.renameTo(finalFile)) {
                            // Images/videos move on into Pictures|Movies/"Clipboard Share" so
                            // gallery apps actually list them (scanning them inside Download never
                            // surfaces them in a gallery's photo view); other types stay here and
                            // just get indexed. Runs on its own coroutine: the MediaStore fallback
                            // re-copies the whole file, and done inline that would stall the next
                            // file's bytes and could blow the sender's 30s completion-ack window,
                            // flipping a fully delivered transfer to "paused" on the sender. The
                            // internal catch also keeps any publish/history-write failure from
                            // reaching this loop's outer catch (which would pause the session).
                            // A cancel arriving after promotion still publishes — the file is
                            // fully received.
                            val fileIndex = i
                            TransferDaemon.daemonScope.launch(Dispatchers.IO) {
                                try {
                                    val publishedName = TransferUtils.publishReceivedMedia(context, finalFile)
                                    if (publishedName != finalFile.name) {
                                        // Destination dedupe (or MediaStore auto-rename) changed the
                                        // leaf name — keep history pointing at the real file.
                                        session.localFileNames[fileIndex] = publishedName
                                        TransferHistoryManager.updateFileNames(context, session.sessionId, session.localFileNames)
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                    }
                    session.currentFileIndex = i + 1
                    session.currentOffset = 0
                }

                // End-to-end delivery confirmation: tell the sender every byte is on disk so it
                // declares "completed" on delivery, not on its own TCP write. Best-effort — an old
                // sender (no COMPLETION_ACK cap) never set this flag and isn't waiting for it.
                // Gate "completed" on the delivery ack actually reaching the sender. If the ack write
                // fails, the sender still sees the transfer as incomplete and will resume — so mark it
                // paused here too (keeping the session) rather than falsely declaring it completed.
                val ackDelivered = if (session.completionAckRequired) {
                    try {
                        connection.output.write("TRANSFER_COMPLETE|${session.sessionId}\n".toByteArray(Charsets.UTF_8))
                        connection.output.flush()
                        true
                    } catch (_: Exception) { false }
                } else true

                if (ackDelivered) {
                    TransferDaemon.removeActiveSession(session.sessionId)
                    TransferHistoryManager.updateStatus(context, session.sessionId, "completed", manifest.size)
                } else {
                    session.isPaused = true
                    session.lastUpdated = System.currentTimeMillis()
                    TransferHistoryManager.updateStatus(context, session.sessionId, "paused", session.currentFileIndex, session.currentOffset)
                }

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                if (session.isCancelled) {
                    TransferHistoryManager.updateStatus(context, session.sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
                } else {
                    session.isPaused = true
                    session.lastUpdated = System.currentTimeMillis()
                    TransferHistoryManager.updateStatus(context, session.sessionId, "paused", session.currentFileIndex, session.currentOffset)
                    // Transient drop while the L2 route persists → re-dial automatically (bounded +
                    // backed off). A genuinely dead route makes every attempt fail with no progress
                    // and the session is left paused for a manual resume after the budget runs out.
                    TransferDaemon.scheduleAutoResume(context, session)
                }
            } finally {
                try { connection.close() } catch(e:Exception){}
                // If the receiver backgrounded this download (left the camera), release the Wi-Fi
                // Direct joiner it held now that the download reached a terminal state. No-op when the
                // screen is still resident (never handed off).
                com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridLiveTransfer.finishIfArmed(session.sessionId)
            }
        }
    }

    fun resumeIncomingTransfer(context: Context, sessionId: String) {
        val session = TransferDaemon.activeSessions[sessionId] ?: return
        if (!session.isIncoming || !session.isPaused) return
        
        session.isPaused = false
        TransferHistoryManager.updateStatus(context, sessionId, "transferring")
        
        TransferDaemon.daemonScope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                val fileKey = TransferKeys.fileKeyIfAead(context, session.targetIp)
                if (fileKey != null) session.fileGcmNegotiated = true
                // v2 only when BOTH peers advertise FGCM2; else fall back to the v1 "fgcm" flag. Pinned
                // via TOFU so a later FGCM2-strip forcing v1 is blocked (XP-DEF-01) — throws
                // DowngradeBlockedException, caught below like the FGCM1 gate.
                val useV2 = TransferKeys.useFileV2(context, session.targetIp, fileKey != null)
                if (useV2) session.fileV2Negotiated = true
                // Bounded connect so a peer that vanished mid-transfer can't hang the resume on the
                // OS default SYN timeout (tens of seconds); mirrors Net.sendBytes.
                socket = Socket().apply { connect(InetSocketAddress(session.targetIp, Ports.FILE_TRANSFER), 8000) }
                socket.soTimeout = 10000
                val outStream = socket.getOutputStream()
                var trueOffset = session.currentOffset
                if (fileKey != null) {
                    // FileSeal records are not byte-seekable, so an AEAD resume restarts the
                    // in-progress file from chunk 0 (already-completed files are promoted out of the
                    // .part and are kept). offset 0 makes executeDownloadStream reopen the .part in
                    // overwrite mode, discarding the partial plaintext.
                    trueOffset = 0L
                } else if (session.currentFileIndex < session.localFileNames.size) {
                    val finalName = session.localFileNames[session.currentFileIndex]
                    val prefs = context.getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
                    val saveLocationUriString = prefs.getString("save_location_uri", null)
                    if (saveLocationUriString != null) {
                        val saveDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(saveLocationUriString))
                        val targetFile = saveDir?.findFile(finalName)
                        if (targetFile != null) {
                            trueOffset = targetFile.length()
                        }
                    } else {
                        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val targetFile = java.io.File(downloadDir, finalName)
                        if (targetFile.exists()) {
                            trueOffset = targetFile.length()
                        }
                    }
                }
                session.currentOffset = trueOffset

                val resumeCmd = if (fileKey != null)
                    "RESUME_REQUEST|${session.sessionId}|${session.currentFileIndex}|${session.currentOffset}|${if (useV2) "fgcm2" else "fgcm"}\n"
                else
                    "RESUME_REQUEST|${session.sessionId}|${session.currentFileIndex}|${session.currentOffset}\n"
                outStream.write(resumeCmd.toByteArray(Charsets.UTF_8))
                outStream.flush()

                executeDownloadStream(context, socket, session, fileKey)
            } catch (e: DowngradeBlockedException) {
                // The sender's FileSeal capability vanished after a prior sealed transfer — refuse the
                // bytes in the clear. Terminal (cancelled), not paused, so we don't auto-retry into it.
                // Mark cancelled and drop the session from the active map (as every other terminal path
                // does) so it can't linger as a zombie or be re-driven by a later frame.
                TransportDiag.log("RX: $e")
                try { socket?.close() } catch (_: Exception) {}
                session.isCancelled = true
                TransferDaemon.removeActiveSession(sessionId)
                TransferHistoryManager.updateStatus(context, sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                try { socket?.close() } catch (_: Exception) {}
                session.isPaused = true
                TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                // A failed reconnect counts toward the bounded budget and backs off; only surface the
                // "couldn't reach the sender" toast once the budget is spent (or the user cancelled).
                if (!TransferDaemon.scheduleAutoResume(context, session)) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, context.getString(R.string.transfer_failed_connect_sender), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun acceptOffer(context: Context, sessionId: String) {
        val session = TransferDaemon.activeSessions[sessionId] ?: return
        if (!session.isIncoming) return
        // One-shot per session object: a double-tap on the offer dialog/notification must not
        // spawn a second download coroutine writing the same .part (AND-19).
        synchronized(session) {
            if (session.acceptStarted) return
            session.acceptStarted = true
        }

        session.isPaused = false
        TransferHistoryManager.addRecord(context, TransferRecord(
            sessionId = sessionId,
            deviceName = session.senderName,
            direction = "incoming",
            fileNames = session.manifest.map { it.first.replace('\\', '/').substringAfterLast('/') },
            totalBytes = session.manifest.sumOf { it.second },
            timestamp = System.currentTimeMillis(),
            status = "transferring",
            filesCompleted = 0,
            totalFiles = session.totalFiles,
            fileSizes = session.manifest.map { it.second },
            currentFileOffset = 0L
        ))
        
        TransferDaemon.daemonScope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                val fileKey = TransferKeys.fileKeyIfAead(context, session.targetIp)
                if (fileKey != null) session.fileGcmNegotiated = true
                // Choose the v2 FileSeal flag only when BOTH peers advertise FGCM2 (our own cap is
                // implicit; the peer's is discovered). Otherwise fall back to the v1 "fgcm" flag. TOFU-
                // pinned so a later FGCM2-strip forcing v1 is blocked (XP-DEF-01); throws on downgrade.
                val useV2 = TransferKeys.useFileV2(context, session.targetIp, fileKey != null)
                if (useV2) session.fileV2Negotiated = true
                // Bounded connect so an unreachable sender fails fast instead of hanging on the OS
                // default SYN timeout; mirrors Net.sendBytes.
                socket = Socket().apply { connect(InetSocketAddress(session.targetIp, Ports.FILE_TRANSFER), 8000) }
                socket.soTimeout = 10000
                val outStream = socket.getOutputStream()
                // Request authenticated (FileSeal) file bytes when both peers support it; "fgcm"/"fgcm2"
                // tells the sender to seal (v1/v2). Legacy senders ignore the extra field and stream plaintext.
                val fgcmFlag = if (useV2) "fgcm2" else "fgcm"
                val acceptCmd = if (fileKey != null) "ACCEPT_OFFER|${session.sessionId}|$fgcmFlag\n" else "ACCEPT_OFFER|${session.sessionId}\n"
                outStream.write(acceptCmd.toByteArray(Charsets.UTF_8))
                outStream.flush()

                executeDownloadStream(context, socket, session, fileKey)
            } catch (e: DowngradeBlockedException) {
                // The sender's FileSeal capability vanished after a prior sealed transfer — refuse the
                // bytes in the clear. Terminal (cancelled), not paused, so we don't auto-retry into it.
                // Mark cancelled and drop the session from the active map (as every other terminal path
                // does) so it can't linger as a zombie or be re-driven by a later frame.
                TransportDiag.log("RX: $e")
                try { socket?.close() } catch (_: Exception) {}
                session.isCancelled = true
                TransferDaemon.removeActiveSession(sessionId)
                TransferHistoryManager.updateStatus(context, sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                try { socket?.close() } catch (_: Exception) {}
                session.isPaused = true
                TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                // A momentary failure on the initial accept-connect auto-retries (bounded + backed off)
                // before the transfer is left paused for a manual resume.
                TransferDaemon.scheduleAutoResume(context, session)
            }
        }
    }

    fun declineOffer(context: Context, sessionId: String) {
        val session = TransferDaemon.removeActiveSession(sessionId) ?: return
        if (!session.isIncoming) return

        TransferDaemon.daemonScope.launch(Dispatchers.IO) {
            Net.sendBytes(session.targetIp, Ports.FILE_TRANSFER, "DECLINE_OFFER|${session.sessionId}\n".toByteArray(Charsets.UTF_8), 5000)
        }
    }
}

