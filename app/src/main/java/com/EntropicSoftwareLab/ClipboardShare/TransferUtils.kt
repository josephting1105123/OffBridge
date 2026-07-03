package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

object TransferUtils {
    fun indexForGallery(context: Context, file: java.io.File) {
        try {
            android.media.MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(file.absolutePath),
                null,
                null
            )
        } catch (_: Exception) { }
    }

    fun readUntilNewline(inputStream: InputStream, maxBytes: Int = 1024 * 1024): String {
        val baos = ByteArrayOutputStream()
        var b = inputStream.read()
        while (b != -1 && b != '\n'.code) {
            baos.write(b)
            // A header/manifest line is small; a peer streaming bytes without a newline
            // would otherwise grow this buffer until OutOfMemoryError. Cap and abort.
            if (baos.size() > maxBytes) throw IOException("Header line exceeded $maxBytes bytes")
            b = inputStream.read()
        }
        return baos.toString("UTF-8")
    }

    /**
     * Parses a LAN file-offer MANIFEST line: `MANIFEST|name:size|name:size|...`.
     *
     * Each entry is split on its LAST ':' so a filename containing ':' is preserved. Entries with
     * no ':', an empty name (`lastColonIndex <= 0`), or a non-numeric size are skipped. This is
     * byte-for-byte parity with the Windows client's `ManifestCodec.parse` and the Android off-grid
     * handshake — the sole authority for turning the wire manifest into (name, size) pairs, so all
     * three paths agree on which entries a hostile/degenerate manifest yields.
     */
    fun parseManifestEntries(manifestLine: String): List<Pair<String, Long>> {
        if (!manifestLine.startsWith("MANIFEST|")) return emptyList()
        val items = manifestLine.substringAfter("MANIFEST|").split('|').filter { it.isNotBlank() }
        val out = ArrayList<Pair<String, Long>>(items.size)
        for (item in items) {
            val lastColonIndex = item.lastIndexOf(':')
            if (lastColonIndex > 0) {
                val fname = item.substring(0, lastColonIndex)
                val fsize = item.substring(lastColonIndex + 1).toLongOrNull() ?: continue
                out.add(fname to fsize)
            }
        }
        return out
    }
}
