package com.joseph.clipboard.windows.os

import java.io.File
import java.io.FileOutputStream

/**
 * Marks files received from a peer as coming from the Internet zone, so Office (Protected View)
 * and SmartScreen treat them as untrusted. Windows reads the mark from the file's
 * "Zone.Identifier" NTFS alternate data stream.
 */
object MarkOfTheWeb {
    /** Zone 3 = Internet. Windows reads this from the file's Zone.Identifier alternate data stream. */
    internal const val STREAM_CONTENT = "[ZoneTransfer]\r\nZoneId=3\r\n"

    /** Tags [file] as coming from an untrusted source. Best-effort: returns false (never throws)
     *  when the volume has no alternate data streams (FAT/exFAT, some network shares) or the OS is not Windows. */
    fun tag(file: File): Boolean {
        if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return false
        return try {
            if (!file.isFile) return false
            FileOutputStream(File(file.path + ":Zone.Identifier")).use {
                it.write(STREAM_CONTENT.toByteArray(Charsets.US_ASCII))
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
