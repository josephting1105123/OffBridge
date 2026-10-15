package com.joseph.clipboard.windows.network

import com.joseph.clipboard.windows.storage.FileSeal
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * The two bulk byte-pumps for LAN/off-grid file transfer, extracted verbatim from
 * ClipboardAppController. One responsibility: pump manifest-ordered bytes over an established
 * stream — plaintext or FileSeal-sealed (v1/v2) — resumable from (fileIndex, offset).
 *
 * Deliberately stream-based and free of sockets, session maps, and history state: the caller
 * registers sockets, decides status transitions, and maps [Listener] callbacks onto its own
 * bookkeeping. Both pumps throw on any failure; the caller owns the failure policy.
 */
object FileStreamer {

    interface Listener {
        /** Rate-limited (~10x/s) progress tick: absolute byte offset within file [fileIndex]. */
        fun onProgress(fileIndex: Int, currentFileOffset: Long)

        /** File [fileIndex] fully transferred (receiver: renamed from .part; sender: fully written). */
        fun onFileComplete(fileIndex: Int)
    }

    /**
     * Receives manifest-ordered files from [input] into [saveDir], writing each to `name.part`
     * and renaming on completion. Non-null [fileKey] ⇒ the stream is FileSeal-sealed; [useV2]
     * selects the v2 per-session key + session/file-bound AAD (must mirror the sender exactly).
     * Throws on premature EOF, AEAD failure, or a failed .part rename.
     *
     * [names] is the final on-disk leaf name for each manifest entry, index-aligned. Pass the SAME
     * list on every call for a given [sessionId] (accept and every resume) — the caller is
     * responsible for resolving it once and reusing it, typically via a cross-session-aware
     * reservation (see ClipboardAppController.resolveIncomingNames) so two concurrent incoming
     * sessions carrying same-named files don't land on one shared ".part" and corrupt each other.
     * Defaults to the within-manifest-only [deCollideNames] when omitted, for callers (tests) that
     * don't need cross-session awareness.
     */
    fun receiveToDir(
        input: InputStream,
        saveDir: File,
        manifest: List<Pair<String, Long>>,
        sessionId: String,
        startFileIndex: Int,
        startOffset: Long,
        fileKey: ByteArray?,
        useV2: Boolean,
        listener: Listener,
        names: List<String> = deCollideNames(manifest),
    ) {
        if (!saveDir.exists()) saveDir.mkdirs()

        val buffer = ByteArray(8192)
        val fs = FileSeal
        val aeadKeySpec = fileKey?.let { fs.keySpec(if (useV2) fs.deriveKeyV2(it, sessionId) else fs.deriveKey(it)) }

        for (i in startFileIndex until manifest.size) {
            val name = names[i]
            val size = manifest[i].second
            val isResumingThisFile = (i == startFileIndex && startOffset > 0)

            val finalFile = File(saveDir, name)
            val partFile = File(saveDir, "$name.part")

            if (!isResumingThisFile && partFile.exists()) {
                partFile.delete()
            }

            var remaining = size - (if (isResumingThisFile) startOffset else 0L)
            var currentOffset = if (isResumingThisFile) startOffset else 0L
            var lastUpdateTime = System.currentTimeMillis()

            FileOutputStream(partFile, isResumingThisFile).use { fos ->
                if (aeadKeySpec != null) {
                    // AEAD: read GCM records, decrypt, append plaintext until the file's bytes are
                    // all on disk. AEAD resume restarts the file, so currentOffset is 0 here.
                    var chunkIndex = currentOffset / fs.CHUNK_SIZE
                    // Same 0-based manifest index [i] the sender binds into the v2 AAD prefix.
                    val aadPrefix = if (useV2) fs.v2AadPrefix(sessionId, i.toLong()) else ByteArray(0)
                    while (remaining > 0) {
                        val pt = fs.openChunk(aeadKeySpec, input, chunkIndex, remaining, aadPrefix)
                        fos.write(pt)
                        remaining -= pt.size
                        currentOffset += pt.size
                        chunkIndex++
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime > 100) {
                            lastUpdateTime = now
                            listener.onProgress(i, currentOffset)
                        }
                    }
                } else {
                    while (remaining > 0) {
                        val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                        val bytesRead = input.read(buffer, 0, toRead)
                        if (bytesRead == -1) break
                        fos.write(buffer, 0, bytesRead)
                        remaining -= bytesRead
                        currentOffset += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime > 100) {
                            lastUpdateTime = now
                            listener.onProgress(i, currentOffset)
                        }
                    }
                }
                fos.flush()
            }

            if (remaining <= 0L) {
                if (finalFile.exists()) finalFile.delete()
                // renameTo can silently fail (target locked, cross-volume, permissions). Ignoring
                // its result would report the file completed while only a .part exists on disk, so
                // fail the transfer instead of lying about success.
                if (!partFile.renameTo(finalFile)) {
                    throw Exception("Failed to finalize ${finalFile.name} (rename from .part failed)")
                }

                listener.onFileComplete(i)
            } else {
                throw Exception("Stream ended prematurely")
            }
        }
    }

    /**
     * Streams [files] (exactly their OFFER-time [fileSizes] byte counts) to [output], optionally
     * FileSeal-sealed. Aborts — rather than desyncing the receiver's positional read — when a
     * source file is missing, changed length since OFFER time, or underruns mid-read.
     */
    fun sendFromFiles(
        output: OutputStream,
        files: List<File>,
        fileSizes: List<Long>,
        sessionId: String,
        startFileIndex: Int,
        startOffset: Long,
        fileKey: ByteArray?,
        useV2: Boolean,
        listener: Listener,
    ) {
        val buffer = ByteArray(8192)
        val fs = FileSeal
        val aeadKeySpec = fileKey?.let { fs.keySpec(if (useV2) fs.deriveKeyV2(it, sessionId) else fs.deriveKey(it)) }

        for (i in startFileIndex until files.size) {
            val file = files[i]
            // The MANIFEST already committed the receiver to read exactly expectedSize bytes for
            // this file at this position. Skipping a missing file (the old `continue`) left the
            // receiver reading this file's bytes from the NEXT file — desyncing every file after
            // it. A file whose length changed since OFFER time would desync the same way. Abort
            // the whole transfer instead so the receiver fails cleanly rather than silently
            // corrupting. (Normal transfers of existing, unchanged files are unaffected.)
            val expectedSize = fileSizes[i]
            if (!file.exists() || file.length() != expectedSize) {
                throw Exception("Source file changed mid-transfer (${file.name}): expected $expectedSize bytes")
            }

            var currentOffset = if (i == startFileIndex) startOffset else 0L
            var lastUpdateTime = System.currentTimeMillis()

            FileInputStream(file).use { fileIn ->
                if (i == startFileIndex && startOffset > 0) {
                    // InputStream.skip() may skip fewer bytes than requested; looping
                    // until startOffset bytes are skipped prevents re-sending already-sent
                    // bytes, which would desync the receiver's positional read of this file.
                    var toSkip = startOffset
                    while (toSkip > 0) {
                        val skipped = fileIn.skip(toSkip)
                        if (skipped <= 0) break
                        toSkip -= skipped
                    }
                    if (toSkip > 0) throw Exception("Failed to skip to resume offset $startOffset for ${file.name}")
                }
                if (aeadKeySpec != null) {
                    // AEAD: seal the file as GCM records sized from the OFFER-time snapshot; the
                    // receiver bounds by the manifest size, so seal exactly that many plaintext
                    // bytes. AEAD resume restarts the file (offset 0).
                    val plainLen = expectedSize
                    var remaining = plainLen - currentOffset
                    var chunkIndex = currentOffset / fs.CHUNK_SIZE
                    // Same 0-based file index [i] the receiver binds into the v2 AAD prefix.
                    val aadPrefix = if (useV2) fs.v2AadPrefix(sessionId, i.toLong()) else ByteArray(0)
                    val cbuf = ByteArray(fs.CHUNK_SIZE)
                    while (remaining > 0) {
                        val want = if (remaining > fs.CHUNK_SIZE) fs.CHUNK_SIZE else remaining.toInt()
                        var filled = 0
                        while (filled < want) {
                            val r = fileIn.read(cbuf, filled, want - filled)
                            if (r == -1) break
                            filled += r
                        }
                        // The file underran its OFFER-time length (truncated after the length
                        // check) — sealing fewer bytes than the manifest promised would desync
                        // the receiver's chunk read, so abort.
                        if (filled < want) {
                            throw Exception("Source file truncated mid-transfer (${file.name})")
                        }
                        output.write(fs.sealChunk(aeadKeySpec, cbuf, filled, chunkIndex, aadPrefix = aadPrefix))
                        currentOffset += filled
                        remaining -= filled
                        chunkIndex++

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime > 100) {
                            lastUpdateTime = now
                            listener.onProgress(i, currentOffset)
                        }
                    }
                } else {
                    // Stream exactly the OFFER-time byte count the receiver will read for this
                    // file. Bounding by expectedSize (not EOF) means a file that grew is capped,
                    // and a short read from a truncated file aborts rather than desyncing.
                    var remaining = expectedSize - currentOffset
                    while (remaining > 0) {
                        val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                        val read = fileIn.read(buffer, 0, toRead)
                        if (read == -1) {
                            throw Exception("Source file truncated mid-transfer (${file.name})")
                        }
                        output.write(buffer, 0, read)
                        currentOffset += read
                        remaining -= read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime > 100) {
                            lastUpdateTime = now
                            listener.onProgress(i, currentOffset)
                        }
                    }
                }
            }

            listener.onFileComplete(i)
        }
        output.flush()
    }

    /**
     * Ceiling the sender waits for the receiver's TRANSFER_COMPLETE after the last byte is written.
     * Mirrors Android `TransferDaemon.COMPLETION_ACK_TIMEOUT_MS`. Returns false on timeout/EOF/mismatch.
     */
    fun awaitCompletionAck(socket: Socket, sessionId: String): Boolean = try {
        socket.soTimeout = 30000
        Net.readLineUtf8(socket.getInputStream()) == "TRANSFER_COMPLETE|$sessionId"
    } catch (e: Exception) { false }

    /**
     * Expands a user selection into the flat list of regular files it actually contains: any
     * directory is walked recursively (depth-first, stable order) and replaced by the files under
     * it; plain files pass through unchanged. Order is deterministic so the MANIFEST and the byte
     * stream stay in agreement.
     *
     * Without this, a picked directory reached the byte pump as a File, and `FileInputStream(dir)`
     * throws — which aborted the entire "send folder" at 0% (the reported resume-stuck-at-0% bug).
     * The receivers still flatten each name to a safe leaf via [sanitizeIncomingFileName], so a sent
     * folder lands as its files in the save location.
     */
    fun expandSelectionToFiles(selection: List<File>): List<File> =
        selection.flatMap { f ->
            if (f.isDirectory) f.walkTopDown().filter { it.isFile }.toList()
            else listOf(f)
        }

    /**
     * De-collides duplicate leaf names WITHIN one manifest, deterministically — the result depends
     * only on the manifest and [reserved], so a resume recomputes identical names as long as the
     * caller passes the same [reserved] snapshot (see [receiveToDir]'s `names` contract — in
     * practice callers resolve once and cache, rather than relying on this determinism directly,
     * since [reserved] itself is NOT stable across calls once other sessions start/finish).
     * A sent folder can flatten two files to the same leaf ("a.txt" under two subfolders); without
     * this the second would delete and overwrite the first on disk (silent data loss). [reserved]
     * additionally seeds the "already taken" set — e.g. names another concurrently-active incoming
     * session has already claimed — so two concurrent same-name transfers don't collide either.
     * Non-duplicate, non-reserved manifests are returned unchanged, so ordinary transfers stay
     * byte-identical. Does NOT dedupe against pre-existing FINISHED files in the save folder —
     * that overwrite behavior is intentionally unchanged (unlike [reserved] entries, which are
     * in-flight ".part" claims, not completed files).
     */
    fun deCollideNames(manifest: List<Pair<String, Long>>, reserved: Set<String> = emptySet()): List<String> {
        val out = ArrayList<String>(manifest.size)
        val used = HashSet<String>(reserved)
        for ((rawName, _) in manifest) {
            var candidate = rawName
            var counter = 1
            val stem = rawName.substringBeforeLast('.')
            val ext = if (rawName.contains('.')) "." + rawName.substringAfterLast('.') else ""
            while (!used.add(candidate)) {
                candidate = "$stem ($counter)$ext"
                counter++
            }
            out.add(candidate)
        }
        return out
    }

    /**
     * Reduce an untrusted manifest filename to a safe leaf written into the save folder.
     * The name comes straight off the wire, so a hostile sender could embed path separators
     * or ".." to escape the save directory. Keep only the final path component (handling both
     * '/' and '\\'), reject empty/dot/dot-dot, and strip Windows-illegal/control chars, falling
     * back to a stable default so a bad name degrades gracefully instead of failing the transfer.
     */
    fun sanitizeIncomingFileName(rawName: String): String {
        val leaf = rawName.replace('\\', '/').substringAfterLast('/').trim()
        if (leaf.isEmpty() || leaf == "." || leaf == "..") return "received_file"
        // Bidi controls (U+202A-202E, U+2066-2069) are dropped too: "invoice\u202Efdp.exe" would
        // otherwise display as "invoiceexe.pdf". Trailing dots/spaces go because Windows silently
        // drops them ("evil.exe." is opened as evil.exe), so the name on disk would differ from this one.
        val cleaned = capLeafLength(
            leaf.filterNot { it in "<>:\"/\\|?*" || it.code < 0x20 || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }
                .trimEnd('.', ' ')
        ).ifBlank { "received_file" }
        // Windows reserves a set of device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9). Opening a file
        // whose stem matches one — even with an extension — targets the device, not a disk file, so
        // prefix it to keep it on disk (e.g. CON.txt → _CON.txt).
        // Windows matches the device name against the text before the FIRST dot, ignoring trailing
        // spaces, so "NUL.tar.gz" and "CON .txt" also hit the device; the last-dot stem missed both.
        val stem = cleaned.substringBefore('.').trimEnd(' ')
        val reserved = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", RegexOption.IGNORE_CASE)
        return if (reserved.matches(stem)) "_$cleaned" else cleaned
    }

    /**
     * Caps a sanitized leaf at [maxBytes] UTF-8 bytes, keeping a short extension, so a hostile
     * manifest name can't exceed the 255-byte filesystem limit (plus ".part" and a dedupe suffix)
     * and fail the write on every resume attempt. Cuts on a code-point boundary.
     */
    fun capLeafLength(name: String, maxBytes: Int = 200): String {
        if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val budget = maxBytes - ext.toByteArray(Charsets.UTF_8).size
        val stem = name.substring(0, name.length - ext.length)
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < stem.length) {
            val cp = stem.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + n > budget) break
            out.appendCodePoint(cp)
            used += n
            i += Character.charCount(cp)
        }
        return out.toString().trimEnd('.', ' ') + ext
    }
}
