package com.EntropicSoftwareLab.ClipboardShare

import android.webkit.MimeTypeMap

/**
 * Extension → MIME resolution for the "open this file" flows, and the matching "is this readable as
 * text?" question.
 *
 * [MimeTypeMap] alone is not enough. It has no entry for `.md`, `.log`, `.kt`, `.properties` and
 * friends on most builds, so those files resolved to the wildcard type: no app matched, and the history sheet's
 * text fallback — gated on a `text/` MIME — never ran either, leaving "Open" doing nothing but
 * revealing the folder. Keeping our own table for the text extensions (the same trick
 * [TransferUtils.mimeForMediaExt] uses for the media ones) makes the answer deterministic across
 * OEM builds, and pure, so it can be pinned by host-JVM tests.
 */
object FileOpenTypes {

    /** Fallback MIME when nothing else knows: every app that registers for "any file" matches it. */
    const val ANY_TYPE = "*/*"

    // Text-ish extensions we are willing to show in the built-in viewer. The value is what we hand
    // an external app; a few are deliberately not text/* (json, sh) because that is what editors
    // register for — the built-in preview is chosen by the key being in this map, not by the value.
    private val TEXT_MIME = mapOf(
        "txt" to "text/plain", "text" to "text/plain", "log" to "text/plain",
        "md" to "text/markdown", "markdown" to "text/markdown",
        "csv" to "text/csv", "tsv" to "text/tab-separated-values",
        "json" to "application/json", "xml" to "text/xml",
        "yml" to "text/plain", "yaml" to "text/plain", "toml" to "text/plain",
        "ini" to "text/plain", "conf" to "text/plain", "cfg" to "text/plain",
        "properties" to "text/plain", "env" to "text/plain",
        "html" to "text/html", "htm" to "text/html", "css" to "text/css",
        "js" to "text/javascript", "mjs" to "text/javascript", "ts" to "text/plain",
        "kt" to "text/plain", "kts" to "text/plain", "java" to "text/plain",
        "py" to "text/plain", "rb" to "text/plain", "php" to "text/plain",
        "c" to "text/plain", "h" to "text/plain", "cpp" to "text/plain", "hpp" to "text/plain",
        "cs" to "text/plain", "go" to "text/plain", "rs" to "text/plain", "swift" to "text/plain",
        "sh" to "text/plain", "bat" to "text/plain", "ps1" to "text/plain",
        "sql" to "text/plain", "gradle" to "text/plain", "srt" to "text/plain", "vtt" to "text/plain",
    )

    /** Lower-cased extension of [fileName], "" when it has none. */
    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    /**
     * Whether the built-in text viewer can show [fileName]. Extension-driven on purpose: a `.ts` or
     * `.json` is perfectly readable text even though its MIME is not a `text/` type.
     */
    fun isTextLike(fileName: String): Boolean = extensionOf(fileName) in TEXT_MIME

    /**
     * MIME to hand `ACTION_VIEW` for [fileName]: our text table first (deterministic across OEM
     * builds), then the platform map, then [ANY_TYPE]. Never null — an intent with a null type
     * matches nothing.
     */
    fun mimeForOpening(fileName: String): String {
        val ext = extensionOf(fileName)
        if (ext.isEmpty()) return ANY_TYPE
        TEXT_MIME[ext]?.let { return it }
        val platform = try {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        } catch (_: Throwable) { null }  // MimeTypeMap is a stub under host-JVM unit tests
        return platform ?: TransferUtils.mimeForMediaExt(ext) ?: ANY_TYPE
    }
}
