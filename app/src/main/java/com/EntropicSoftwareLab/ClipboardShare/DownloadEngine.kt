package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.SocketConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.SessionCrypto
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import com.EntropicSoftwareLab.ClipboardShare.transport.FileSeal
import java.net.Socket
import java.net.InetSocketAddress
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore




object DownloadEngine {
    fun executeDownloadStream(context: Context, socket: Socket, session: TransferSession, fileKey: ByteArray? = null) {
        executeDownloadStream(context, SocketConnection(socket), session, fileKey)
    }

    /**
     * V4 §2: the carrier-neutral download path — protocol bytes don't depend on the carrier.
     * A non-null [fileKey] (the shared pairing key) means the bulk stream is FileSeal-sealed and is
     * decrypted here; null keeps the legacy plaintext read (off-grid + legacy peers).
     */
    fun executeDownloadStream(context: Context, connection: TransportConnection, session: TransferSession, fileKey: ByteArray? = null) {
        val manifest = session.manifest

        TransferDaemon.daemonScope.launch(Dispatchers.IO) {
            // Destination snapshot: read the "Save file/media to" prefs once per session (first
            // stream) and pin them on the session, so every later pass — append after a drop, the
            // resume-offset probe — writes and probes the SAME destination even when the user flips
            // a save setting mid-transfer. Re-reading live here split the .part across two folders
            // and silently corrupted the resumed file (FOLLOWUPS-2026-07-06 #1).
            if (!session.destResolved) {
                synchronized(session) {
                    if (!session.destResolved) {
                        val prefs = context.getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
                        session.destSaveTreeUri = prefs.getString("save_location_uri", null)
                        // "Save media to": GALLERY (default) publishes photos/videos into the
                        // Pictures|Movies gallery collections; SAME_AS_FILES keeps them in the
                        // file save folder like everything else.
                        session.destSaveMediaTo = prefs.getString("save_media_to", TransferUtils.SAVE_MEDIA_GALLERY)
                            ?: TransferUtils.SAVE_MEDIA_GALLERY
                        session.destResolved = true
                    }
                }
            }
            val saveLocationUriString = session.destSaveTreeUri
            val saveDir = if (saveLocationUriString != null) {
                DocumentFile.fromTreeUri(context, Uri.parse(saveLocationUriString))
            } else {
                null
            }
            val saveMediaTo = session.destSaveMediaTo
            val stagingDir = TransferUtils.receiveStagingDir(context)
            // One listing of the custom SAF folder per stream pass — a provider children-query
            // is expensive and findFile() is a full scan per call. It seeds the session-start
            // name reservation AND backs every promote-time collision check below, gaining each
            // promoted name as the batch progresses. A received file must never claim (or be
            // renamed over) a name already in that folder: the old delete-then-rename promote
            // was a confirmed unprompted data-loss path (FOLLOWUPS-2026-07-06 #4).
            val safTreeNames: HashSet<String> = if (saveDir != null) {
                try { saveDir.listFiles().mapNotNullTo(HashSet()) { it.name } } catch (_: Exception) { HashSet() }
            } else HashSet()

            try {
                connection.setReadTimeout(10000) // 10 second network timeout
                val inputStream = connection.input
                val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                // v2 (session.fileV2Negotiated, set when we sent "fgcm2") uses the per-session key and
                // the session/file-bound AAD prefix; v1 keeps deriveKey + empty prefix. Must mirror the
                // sender's streamSessionFiles derivation exactly or the AEAD tag fails.
                val aeadKeySpec = fileKey?.let {
                    FileSeal.keySpec(if (session.fileV2Negotiated) FileSeal.deriveKeyV2(it, session.sessionId) else FileSeal.deriveKey(it))
                }

                if (session.localFileNames.isEmpty()) {
                    // Reserve leaf names against (a) THIS batch — two manifest entries flattening to
                    // the same leaf must not overwrite each other — and (b) what is ALREADY at each
                    // destination. The destinations (staging dir + both media publish collections) are
                    // listed ONCE into sets: per-candidate File.exists() probes cost O(N) stats for a
                    // folder batch, and — the real bug — a probe of the FINAL name can't see another
                    // live session's in-flight "name.part" sidecar, so two concurrent same-name
                    // receives streamed into ONE shared sidecar and corrupted both. A listing surfaces
                    // the sidecars and dedupeName(reservePartSidecars) treats "name.part" as claiming
                    // "name". SAF-bound entries additionally reserve against the custom folder's
                    // listing (safTreeNames, which includes its .part names).
                    val reservedNonSaf = HashSet<String>()
                    try { stagingDir.list()?.let { reservedNonSaf.addAll(it) } } catch (_: Exception) {}
                    for (cat in arrayOf(TransferUtils.MediaCategory.IMAGE, TransferUtils.MediaCategory.VIDEO)) {
                        try { TransferUtils.publicMediaDir(cat)?.list()?.let { reservedNonSaf.addAll(it) } } catch (_: Exception) {}
                    }
                    // Also reserve the names other LIVE incoming sessions have already claimed: their
                    // sidecar may not exist on disk yet (they name at stream start, write later), so
                    // the listing alone can miss a same-instant race. The other session's plain
                    // ArrayList may be mid-grow on another core, so a torn read CAN throw — best-effort
                    // catch and fall back to the on-disk listing, like the sibling reads above.
                    for (other in TransferDaemon.activeSessions.values) {
                        if (other === session || !other.isIncoming) continue
                        try {
                            for (idx in 0 until other.localFileNames.size) {
                                other.localFileNames.getOrNull(idx)?.let { reservedNonSaf.add(it) }
                            }
                        } catch (_: Exception) {}
                    }
                    val reservedSaf = HashSet(safTreeNames).apply { addAll(reservedNonSaf) }
                    for (m in manifest) {
                        // Sanitize the manifest-supplied name before it ever hits the filesystem
                        // (path-traversal / reserved chars), matching the Windows receiver (XP-DEF-03).
                        var finalName = TransferUtils.sanitizeIncomingFileName(m.first)
                        if (session.currentOffset == 0L) {
                            val landsInSafTree = saveDir != null && !TransferUtils.routeMediaToGallery(finalName, saveMediaTo)
                            finalName = TransferUtils.dedupeName(
                                emptyList(), finalName,
                                if (landsInSafTree) reservedSaf else reservedNonSaf,
                                reservePartSidecars = true
                            )
                        }
                        reservedNonSaf.add(finalName)
                        reservedSaf.add(finalName)
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
                    // Media under "Save media to: Gallery" is staged in Download and then published into
                    // Pictures|Movies/"Clipboard Share" — even when a custom file folder is set, so it
                    // bypasses the SAF folder. Everything else (all files, plus media under "Same as
                    // files") goes to the file folder: the SAF tree if chosen, otherwise Download.
                    val routeToGallery = TransferUtils.routeMediaToGallery(finalName, saveMediaTo)
                    val saveTree = if (routeToGallery) null else saveDir
                    var partDoc: DocumentFile? = null
                    var partFile: java.io.File? = null
                    val outputStream = if (saveTree != null && saveTree.canWrite()) {
                        val doc = saveTree.findFile(partName) ?: saveTree.createFile("*/*", partName)
                        partDoc = doc
                        if (doc != null) {
                            context.contentResolver.openOutputStream(doc.uri, if (append) "wa" else "w")
                        } else null
                    } else {
                        // Public Download on API 30+; app-private staging on API 29, where a raw
                        // write into a public collection throws EACCES (scoped storage, no legacy
                        // flag) and used to stall the session unrecoverably (FOLLOWUPS-2026-07-06
                        // #3). API-29 files are published out via MediaStore after promote.
                        val pf = java.io.File(stagingDir, partName)
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

                    // Promote the completed .part to its final name — NEVER by deleting a same-name
                    // file that appeared since the session-start dedupe. That file is either the
                    // user's own document (the old delete-then-rename was a confirmed unprompted
                    // data-loss path, FOLLOWUPS-2026-07-06 #4) or our own already-promoted copy
                    // after a lost completion-ack re-drove the resume. Promote under a fresh
                    // "name (N)" instead; an empty re-drive sidecar is just dropped.
                    partDoc?.let { doc ->
                        if (finalName in safTreeNames && doc.length() == 0L && fsize > 0L) {
                            // Re-driven resume after this file was already promoted: nothing was
                            // appended and the finished file is in place — drop the empty sidecar.
                            // fsize > 0 keeps a genuinely empty manifest file (also 0-length on
                            // disk) from being mistaken for a re-drive and silently discarded.
                            doc.delete()
                        } else {
                            var promoteName = if (finalName in safTreeNames)
                                TransferUtils.dedupeName(emptyList(), finalName, safTreeNames) else finalName
                            // renameTo can fail on a collision the stream-start listing can't see
                            // (a file created in the folder since then) — advance to the next
                            // "name (N)" rather than silently stranding fully received bytes in
                            // the .part with the session still reported completed.
                            var renamed = false
                            for (attempt in 0 until 4) {
                                if (doc.renameTo(promoteName)) { renamed = true; break }
                                safTreeNames.add(promoteName)
                                promoteName = TransferUtils.dedupeName(emptyList(), finalName, safTreeNames)
                            }
                            if (renamed) {
                                safTreeNames.add(promoteName)
                                if (promoteName != finalName) recordLocalName(context, session, i, promoteName)
                            } else {
                                // Exhausted the rename attempts: the bytes stay in the .part. Log so a
                                // "received but missing" report is diagnosable rather than silent.
                                TransportDiag.log("RX: promote failed for $finalName — left as .part")
                            }
                        }
                    }
                    partFile?.let { pf ->
                        val parentDir = pf.parentFile
                        val existsAlready = java.io.File(parentDir, finalName).exists()
                        if (existsAlready && pf.length() == 0L && fsize > 0L) {
                            // Re-driven resume after this file was already promoted: nothing was
                            // appended and the finished file is in place — drop the empty sidecar.
                            // fsize > 0 keeps a genuinely empty manifest file (also 0-length on
                            // disk) from being mistaken for a re-drive and silently discarded.
                            pf.delete()
                        } else {
                            var promoteName = if (existsAlready)
                                TransferUtils.dedupeName(listOfNotNull(parentDir), finalName) else finalName
                            var finalFile = java.io.File(parentDir, promoteName)
                            // renameTo can fail on a same-name file exists() can't see (on API 30+
                            // public Download, other apps' files are invisible to java.io probes but
                            // FUSE refuses to replace them) — advance to the next "name (N)" rather
                            // than silently stranding the received bytes in the .part.
                            var renamed = false
                            val tried = HashSet<String>()
                            for (attempt in 0 until 4) {
                                if (pf.renameTo(finalFile)) { renamed = true; break }
                                tried.add(promoteName)
                                promoteName = TransferUtils.dedupeName(listOfNotNull(parentDir), finalName, tried)
                                finalFile = java.io.File(parentDir, promoteName)
                            }
                            if (renamed) {
                                if (promoteName != finalName) recordLocalName(context, session, i, promoteName)
                                if (routeToGallery) {
                                    // Images/videos move on into Pictures|Movies/"Clipboard Share" so
                                    // gallery apps actually list them (scanning them inside Download
                                    // never surfaces them in a gallery's photo view).
                                    publishPromotedFileAsync(context, session, i, finalFile, TransferUtils::publishReceivedMedia)
                                } else if (TransferUtils.isLegacyScopedStorage()) {
                                    // API 29 staged this file app-privately (raw public-Download writes
                                    // EACCES there) — copy it out to the public Downloads collection via
                                    // MediaStore so the user can actually see it.
                                    publishPromotedFileAsync(context, session, i, finalFile, TransferUtils::publishToPublicDownloads)
                                } else {
                                    // Stays in Download — a non-media file, or media the user routed to
                                    // "Same as files" with no custom folder. Just index it in place so file
                                    // browsers/galleries that read Download can find it; no move, so the leaf
                                    // name is unchanged and history stays valid.
                                    TransferUtils.indexForGallery(context, finalFile)
                                }
                            } else {
                                // Exhausted the rename attempts: the bytes stay in the .part. Log so a
                                // "received but missing" report is diagnosable rather than silent.
                                TransportDiag.log("RX: promote failed for $finalName — left as .part")
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
                if (e is CancellationException) throw e
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

    /** Updates the session's local leaf-name list and the persisted history together, so the
     *  history "open" action always points at the file that is actually on disk. */
    private fun recordLocalName(context: Context, session: TransferSession, index: Int, name: String) {
        session.localFileNames[index] = name
        TransferHistoryManager.updateFileNames(context, session.sessionId, session.localFileNames)
    }

    /** Bounds concurrent MediaStore publish copies. On API 29 EVERY non-SAF receive publishes
     *  (full-file copy through a pending row), so an unbounded launch-per-file would let a large
     *  batch flood Dispatchers.IO with simultaneous copies contending with the still-live receive
     *  stream — the exact completion-ack stall the async split exists to avoid. Two lanes keep the
     *  pipeline moving without saturating flash/MediaProvider. */
    private val publishGate = Semaphore(2)

    /** Publishes a promoted file out of its staging spot on its own coroutine: the MediaStore
     *  fallback re-copies the whole file, and done inline that would stall the next file's bytes
     *  and could blow the sender's 30s completion-ack window, flipping a fully delivered transfer
     *  to "paused" on the sender. The internal catch keeps any publish/history-write failure from
     *  reaching the receive loop's outer catch (which would pause the session); a cancel arriving
     *  after promotion still publishes — the file is fully received. [publish] returns the
     *  destination leaf name (null on failure); when the destination renamed the leaf (dedupe or
     *  MediaStore auto-rename), history is repointed at the real file. */
    private fun publishPromotedFileAsync(
        context: Context, session: TransferSession, fileIndex: Int, file: java.io.File,
        publish: (Context, java.io.File) -> String?
    ) {
        TransferDaemon.daemonScope.launch(Dispatchers.IO) {
            try {
                publishGate.withPermit {
                    val publishedName = publish(context, file)
                    if (publishedName != null && publishedName != file.name) {
                        recordLocalName(context, session, fileIndex, publishedName)
                    }
                }
            } catch (e: Exception) {
                // Best-effort: publishing must not fail an already-delivered transfer. Log so a
                // "received but not visible in gallery/history" report is diagnosable.
                TransportDiag.log("RX: publish of ${file.name} failed: ${e.message}")
            }
        }
    }

    fun resumeIncomingTransfer(context: Context, sessionId: String) {
        val session = TransferDaemon.activeSessions[sessionId] ?: return
        // Atomic claim of the paused state (same latch pattern as acceptOffer): a manual Resume tap
        // racing a scheduled auto-resume could both pass a plain check and spawn two download
        // coroutines appending into the same .part.
        synchronized(session) {
            if (!session.isIncoming || !session.isPaused) return
            session.isPaused = false
        }
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
                    // Probe the SAME destination the write path used: the session's pinned snapshot,
                    // not a live prefs read. Re-reading live here let a mid-transfer save-setting
                    // flip point this probe — and the next write pass — at the NEW destination while
                    // the .part sat in the OLD one, silently corrupting the resumed file
                    // (FOLLOWUPS-2026-07-06 #1). The prefs fallback is defensive only: localFileNames
                    // is non-empty (guard above), which means executeDownloadStream already ran and
                    // pinned the snapshot, so destResolved is true on every reachable path.
                    val saveLocationUriString: String?
                    val saveMediaTo: String
                    if (session.destResolved) {
                        saveLocationUriString = session.destSaveTreeUri
                        saveMediaTo = session.destSaveMediaTo
                    } else {
                        val prefs = context.getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
                        saveLocationUriString = prefs.getString("save_location_uri", null)
                        saveMediaTo = prefs.getString("save_media_to", TransferUtils.SAVE_MEDIA_GALLERY) ?: TransferUtils.SAVE_MEDIA_GALLERY
                    }
                    // Match the write path's destination: gallery-bound media stages in the staging
                    // dir even when a custom file folder is set, so read its offset there, not the
                    // SAF tree. Probe the ".part" SIDECAR — that is where the write path streams the
                    // partial bytes; the bare finalName doesn't exist until promote, and a same-named
                    // foreign file at the destination would yield a bogus offset that corrupts the
                    // resumed file.
                    val partName = "$finalName.part"
                    val useSaf = saveLocationUriString != null && !TransferUtils.routeMediaToGallery(finalName, saveMediaTo)
                    if (useSaf) {
                        val saveDir = DocumentFile.fromTreeUri(context, Uri.parse(saveLocationUriString!!))
                        val targetFile = saveDir?.findFile(partName)
                        if (targetFile != null) {
                            trueOffset = targetFile.length()
                        }
                    } else {
                        val stagingDir = TransferUtils.receiveStagingDir(context)
                        val targetFile = java.io.File(stagingDir, partName)
                        if (targetFile.exists()) {
                            trueOffset = targetFile.length()
                        }
                    }
                }
                session.currentOffset = trueOffset

                // Carry the completion-ack request across a resume (off-grid sessions set this): without
                // it the sender's ackExpected=false, so it declares "completed" on its TCP write and drops
                // the session — the false-done + dead-resume bug, reopened on resume. Flags after the
                // offset are order-independent on both receivers, and a legacy sender ignores the extra
                // field. Mirrors the initial off-grid accept (QrScannerFragment ackSuffix).
                val ackSuffix = if (session.completionAckRequired) "|ack" else ""
                val resumeCmd = if (fileKey != null)
                    "RESUME_REQUEST|${session.sessionId}|${session.currentFileIndex}|${session.currentOffset}|${if (useV2) "fgcm2" else "fgcm"}$ackSuffix\n"
                else
                    "RESUME_REQUEST|${session.sessionId}|${session.currentFileIndex}|${session.currentOffset}$ackSuffix\n"
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
                if (e is CancellationException) throw e
                e.printStackTrace()
                try { socket?.close() } catch (_: Exception) {}
                session.isPaused = true
                TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                // A failed reconnect counts toward the bounded budget and backs off; only surface the
                // "couldn't reach the sender" toast once the budget is spent (or the user cancelled).
                if (!TransferDaemon.scheduleAutoResume(context, session)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.transfer_failed_connect_sender), Toast.LENGTH_SHORT).show()
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
            fileNames = session.manifest.map { TransferUtils.manifestDisplayLeaf(it.first) },
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
                if (e is CancellationException) throw e
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

