package com.joseph.clipboard.windows.storage

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal append-only diagnostics log. A packaged (jpackage) build runs with no console, so
 * `System.err`/`println` go nowhere — which is why incoming-file OFFERs that are silently rejected by
 * the admission gate (wrong/missing pairing key, AUTH2 proof failure, downgrade block) are invisible
 * in the field. This writes those events to a file the tester can send back, next to the app's config
 * at %LOCALAPPDATA%\OffBridge\diagnostics.log.
 *
 * Best-effort by design — it must never throw into a network/transfer code path.
 */
object DiagLog {
    private val appDataPath = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
    private val logFile = File("$appDataPath\\OffBridge", "diagnostics.log")
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun w(message: String) {
        try {
            logFile.parentFile?.mkdirs()
            logFile.appendText("${stamp.format(Date())}  $message${System.lineSeparator()}")
        } catch (_: Throwable) {
            // Diagnostics must never break the caller.
        }
    }
}
