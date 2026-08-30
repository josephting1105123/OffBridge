package com.EntropicSoftwareLab.ClipboardShare.transport

/**
 * Deciding whether a MediaStore row is a screenshot this app should announce.
 *
 * Pure so the two things most likely to be wrong can be tested on the host rather than discovered
 * on a phone: which folders count, and the fact that MediaStore reports DATE_ADDED in seconds while
 * everything else in this codebase works in milliseconds.
 */
object ScreenshotRules {

    /**
     * How recent a row has to be to count as "just now".
     *
     * The observer fires for any change to the images collection, including edits and deletions of
     * old files, so recency is what separates a new screenshot from a gallery app touching a photo
     * from last year. Generous enough to survive a slow write on a busy device.
     */
    const val RECENCY_WINDOW_MS = 15_000L

    /** Folder names that mean "screenshot" across the OEMs, lowercased. */
    private val SCREENSHOT_DIRS = listOf("screenshots", "screenshot", "screen shots", "screencapture")

    /**
     * True when the row's folder is a screenshots folder.
     *
     * Matched on the path only, never the filename. Vendors disagree about the filename
     * ("Screenshot_x", "Screenshot-x", "IMG_x" on some ColorOS builds) but they all agree the file
     * lands in a Screenshots directory, and matching filenames would sweep in every photo a user
     * happened to name that way.
     */
    fun isScreenshotPath(relativePath: String?): Boolean {
        if (relativePath.isNullOrBlank()) return false
        // RELATIVE_PATH is always forward-slash separated, whatever the underlying filesystem.
        val segments = relativePath.lowercase().split('/').filter { it.isNotBlank() }
        return segments.any { it in SCREENSHOT_DIRS }
    }

    /**
     * True when a row added at [dateAddedSeconds] is recent enough to announce.
     *
     * The seconds-to-milliseconds conversion is the whole point of this function existing. Comparing
     * MediaStore's DATE_ADDED against System.currentTimeMillis() directly makes every row look about
     * fifty years old, so nothing ever matches and the feature silently does nothing at all.
     */
    fun isRecent(dateAddedSeconds: Long, nowMillis: Long, windowMs: Long = RECENCY_WINDOW_MS): Boolean {
        if (dateAddedSeconds <= 0L) return false
        val addedMillis = dateAddedSeconds * 1000L
        val age = nowMillis - addedMillis
        // A small negative age is normal clock jitter between MediaStore's write and our read; a
        // large one means a bogus future timestamp, which is not something to notify about.
        return age >= -windowMs && age <= windowMs
    }

    /** Both conditions together — what the observer actually asks. */
    fun isFreshScreenshot(relativePath: String?, dateAddedSeconds: Long, nowMillis: Long): Boolean =
        isScreenshotPath(relativePath) && isRecent(dateAddedSeconds, nowMillis)
}
