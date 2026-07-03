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
}
