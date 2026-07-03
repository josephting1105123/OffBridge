package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import com.EntropicSoftwareLab.ClipboardShare.transport.FileSeal
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportConnection
import java.io.OutputStream
import java.net.Socket

object UploadEngine {
    internal fun streamSessionFiles(context: Context, session: TransferSession, outStream: OutputStream, fileKey: ByteArray? = null, sendGen: Int? = null, useFileV2: Boolean = false) {
        // Non-null fileKey Ã¢â€¡â€™ seal the bulk stream per-chunk with FileSeal (negotiated via the FGCM1
        // capability + the "fgcm" ACCEPT/RESUME flag); otherwise bytes go in the clear (legacy peers
        // and the off-grid ECDH path, which both pass null). [useFileV2] (negotiated via FGCM2 + the
        // "fgcm2" flag) selects the v2 per-session key and the session/file-bound v2 AAD prefix.
        val aeadKeySpec = fileKey?.let {
            FileSeal.keySpec(if (useFileV2) FileSeal.deriveKeyV2(it, session.sessionId) else FileSeal.deriveKey(it))
        }
        // Leg-local start position: capture the resume point once, so a concurrent resume (which bumps
        // sendGeneration and rewrites session.currentOffset/Index) can't make THIS leg follow the new
        // position mid-file. A superseded leg aborts via the generation check below instead.
        val startIndex = session.currentFileIndex
        var legOffset = session.currentOffset
        // True while this is still the session's current send attempt; a newer resume flips it false.
        fun isCurrent() = sendGen == null || session.sendGeneration.get() == sendGen
        for (i in startIndex until session.fileUris.size) {
            if (!isCurrent()) throw Exception("Send superseded by a newer resume")
            val uri = android.net.Uri.parse(session.fileUris[i])
            context.contentResolver.openInputStream(uri)?.use { fileIn ->
                if (legOffset > 0) {
                    // InputStream.skip may skip fewer bytes than requested; loop until the
                    // full offset is consumed (falling back to read-and-discard) so resume
                    // doesn't start mid-file and corrupt the stream.
                    var remaining = legOffset
                    val skipBuffer = ByteArray(8192)
                    while (remaining > 0) {
                        val skipped = fileIn.skip(remaining)
                        if (skipped > 0) {
                            remaining -= skipped
                        } else {
                            val toRead = if (remaining > skipBuffer.size) skipBuffer.size else remaining.toInt()
                            val read = fileIn.read(skipBuffer, 0, toRead)
                            if (read == -1) break
                            remaining -= read
                        }
                    }
                }
                if (aeadKeySpec != null) {
                    // AEAD: emit the file as GCM records sized from the manifest; the reader bounds by
                    // the same size. AEAD resume restarts the file, so currentOffset is 0 here and the
                    // chunk index starts at 0 (kept general via currentOffset/CHUNK_SIZE).
                    val plainLen = session.manifest.getOrNull(i)?.second ?: 0L
                    val buffer = ByteArray(FileSeal.CHUNK_SIZE)
                    var remaining = plainLen - legOffset
                    var chunkIndex = legOffset / FileSeal.CHUNK_SIZE
                    while (remaining > 0) {
                        if (session.isCancelled || session.isPaused) throw Exception("Transfer cancelled")
                        if (!isCurrent()) throw Exception("Send superseded by a newer resume")
                        val want = if (remaining > FileSeal.CHUNK_SIZE) FileSeal.CHUNK_SIZE else remaining.toInt()
                        var filled = 0
                        while (filled < want) {
                            val r = fileIn.read(buffer, filled, want - filled)
                            if (r == -1) throw java.io.IOException("File shorter than its manifest size")
                            filled += r
                        }
                        val aadPrefix = if (useFileV2) FileSeal.v2AadPrefix(session.sessionId, i.toLong()) else ByteArray(0)
                        outStream.write(FileSeal.sealChunk(aeadKeySpec, buffer, filled, chunkIndex, aadPrefix = aadPrefix))
                        legOffset += filled
                        remaining -= filled
                        chunkIndex++
                        if (isCurrent()) session.currentOffset = legOffset
                    }
                } else {
                    val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                    while (true) {
                        if (session.isCancelled || session.isPaused) throw Exception("Transfer cancelled")
                        if (!isCurrent()) throw Exception("Send superseded by a newer resume")
                        val read = fileIn.read(buffer)
                        if (read == -1) break
                        outStream.write(buffer, 0, read)
                        legOffset += read
                        if (isCurrent()) session.currentOffset = legOffset
                    }
                }
            }
            outStream.flush()
            legOffset = 0
            if (isCurrent()) {
                session.currentFileIndex = i + 1
                session.currentOffset = 0
            }
        }
    }

    private const val COMPLETION_ACK_TIMEOUT_MS = 30000

    fun awaitCompletionAck(connection: TransportConnection, sessionId: String): Boolean = try {
        connection.setReadTimeout(COMPLETION_ACK_TIMEOUT_MS)
        TransferUtils.readUntilNewline(connection.input) == "TRANSFER_COMPLETE|$sessionId"
    } catch (_: Exception) { false }

    fun awaitCompletionAck(socket: Socket, sessionId: String): Boolean = try {
        socket.soTimeout = COMPLETION_ACK_TIMEOUT_MS
        TransferUtils.readUntilNewline(socket.getInputStream()) == "TRANSFER_COMPLETE|$sessionId"
    } catch (_: Exception) { false }

}

