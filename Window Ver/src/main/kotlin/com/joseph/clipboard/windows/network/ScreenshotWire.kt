package com.joseph.clipboard.windows.network

/**
 * The two control frames behind "your phone took a screenshot, open it here".
 *
 * ```
 * SCREENSHOT_AVAILABLE|<sessionId>|<sealedB64>    phone -> laptop
 * SCREENSHOT_FETCH|<sessionId>                    laptop -> phone
 * ```
 *
 * Same newline-terminated, pipe-delimited shape as every other control frame on port 65433, so the
 * dispatchers on both platforms pick them up with one more `startsWith` branch and no new framing.
 *
 * The announcement carries no readable metadata: `sealedB64` is the base64 of a [ClipboardSeal]
 * blob whose plaintext is [Metadata]. A filename and a thumbnail of someone's screen are exactly
 * the sort of thing the rest of this app refuses to put on the wire in the clear, and the GCM1
 * clipboard seal already provides authentication, a bound timestamp and a replay cache. Only the
 * opcode and an opaque session id stay visible.
 *
 * Byte-identical to the Android mirror at `transport/ScreenshotWire.kt`, pinned by a golden-vector
 * test on both sides.
 */
object ScreenshotWire {

    const val AVAILABLE = "SCREENSHOT_AVAILABLE"
    const val FETCH = "SCREENSHOT_FETCH"

    /**
     * Cap on the encoded thumbnail.
     *
     * Both platforms' line readers abort past 1 MiB, so this is not the last line of defence — it
     * is the point at which a preview stops being worth the bytes. A 256px JPEG lands around 20 KB.
     */
    const val MAX_THUMBNAIL_B64 = 64 * 1024

    /** What the sealed payload says. [thumbnailB64] may be empty when no preview was produced. */
    data class Metadata(
        val name: String,
        val size: Long,
        val modifiedMillis: Long,
        val thumbnailB64: String,
    )

    /**
     * `name|size|mtime|thumb`.
     *
     * The name goes first even though it is the only free-form field, because the other three have
     * known shapes (two integers and base64, none of which can contain a pipe) — so [decodeMetadata]
     * splits from the RIGHT and a filename containing '|' survives intact. Putting the name last
     * would have been the obvious choice and the wrong one.
     */
    fun encodeMetadata(metadata: Metadata): String =
        "${metadata.name}|${metadata.size}|${metadata.modifiedMillis}|${metadata.thumbnailB64}"

    /** Inverse of [encodeMetadata]. Null on anything malformed — never a partly-filled Metadata. */
    fun decodeMetadata(encoded: String): Metadata? {
        val lastPipe = encoded.lastIndexOf('|')
        if (lastPipe < 0) return null
        val thumbnail = encoded.substring(lastPipe + 1)
        val head = encoded.substring(0, lastPipe)

        val secondPipe = head.lastIndexOf('|')
        if (secondPipe < 0) return null
        val modified = head.substring(secondPipe + 1).toLongOrNull() ?: return null
        val head2 = head.substring(0, secondPipe)

        val thirdPipe = head2.lastIndexOf('|')
        if (thirdPipe < 0) return null
        val size = head2.substring(thirdPipe + 1).toLongOrNull() ?: return null
        val name = head2.substring(0, thirdPipe)

        if (name.isEmpty() || size < 0 || modified < 0) return null
        if (thumbnail.length > MAX_THUMBNAIL_B64) return null
        return Metadata(name, size, modified, thumbnail)
    }

    /** Builds the announcement. [sessionId] must not contain a pipe; callers generate it as hex. */
    fun availableLine(sessionId: String, sealedB64: String): String =
        "$AVAILABLE|$sessionId|$sealedB64"

    /** Session id and sealed payload from an announcement, or null if this isn't one. */
    fun parseAvailable(line: String): Pair<String, String>? {
        if (!line.startsWith("$AVAILABLE|")) return null
        val body = line.removePrefix("$AVAILABLE|")
        val pipe = body.indexOf('|')
        if (pipe <= 0) return null
        val sessionId = body.substring(0, pipe)
        val sealed = body.substring(pipe + 1)
        if (sessionId.isEmpty() || sealed.isEmpty()) return null
        return sessionId to sealed
    }

    /** Builds the fetch request the laptop sends when the user asks to open the screenshot. */
    fun fetchLine(sessionId: String): String = "$FETCH|$sessionId"

    /** Session id from a fetch request, or null if this isn't one. */
    fun parseFetch(line: String): String? {
        if (!line.startsWith("$FETCH|")) return null
        return line.removePrefix("$FETCH|").trim().takeIf { it.isNotEmpty() && !it.contains('|') }
    }

    /**
     * File extensions the laptop is willing to auto-accept and auto-open.
     *
     * This is the security boundary of the whole feature. The laptop accepts a screenshot transfer
     * without asking and then hands the result to the shell, so the set of things that can arrive
     * that way has to be closed and small. Anything outside it falls back to the normal manual
     * accept prompt.
     */
    private val OPENABLE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    /** True when [fileName] is one of the image types this feature is allowed to open unattended. */
    fun isOpenableImage(fileName: String): Boolean {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return false
        return fileName.substring(dot + 1).lowercase() in OPENABLE_EXTENSIONS
    }
}
