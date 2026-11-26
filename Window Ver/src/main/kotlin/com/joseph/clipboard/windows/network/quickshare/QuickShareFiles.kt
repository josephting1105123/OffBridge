package com.joseph.clipboard.windows.network.quickshare

import com.joseph.clipboard.windows.network.FileStreamer
import com.joseph.clipboard.windows.os.MarkOfTheWeb
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/**
 * Where Quick Share files land. Data goes to a randomly named ".part" file in the save folder and
 * is renamed once complete. Unlike a transfer from a paired device, this never replaces a file
 * that is already there: the sender is a stranger, so a clash gets a " (1)" suffix.
 */
object QuickShareFiles {
    /** The leaf name to save under, from a name straight off the wire. */
    fun safeName(rawName: String): String = FileStreamer.sanitizeIncomingFileName(rawName)

    fun createPartFile(saveDir: File): File = File.createTempFile("quickshare-", ".part", saveDir)

    /**
     * Moves [partFile] to [name] (already through [safeName]) inside [saveDir], or to the first free
     * "name (n)". [reserved] holds names other in-flight transfers have claimed.
     */
    fun finalizeFile(partFile: File, saveDir: File, name: String, reserved: Set<String> = emptySet()): File {
        val dir = saveDir.canonicalFile
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        val taken = reserved.mapTo(HashSet()) { it.lowercase() } // NTFS names ignore case
        for (counter in 0..MAX_SUFFIX) {
            val candidate = if (counter == 0) name else "$stem ($counter)$extension"
            val target = File(dir, candidate)
            // The name is already sanitized; this is the last line of defence against leaving [dir].
            if (target.canonicalFile.parentFile != dir) throw IOException("file name escapes the save folder")
            if (candidate.lowercase() in taken || target.exists() || File(dir, "$candidate.part").exists()) continue
            try {
                Files.move(partFile.toPath(), target.toPath()) // no REPLACE_EXISTING, on purpose
            } catch (e: FileAlreadyExistsException) {
                continue
            }
            MarkOfTheWeb.tag(target)
            return target
        }
        throw IOException("no free file name for the incoming file")
    }

    private const val MAX_SUFFIX = 999
}
