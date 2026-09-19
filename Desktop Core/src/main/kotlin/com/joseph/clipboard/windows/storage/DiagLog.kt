package com.joseph.clipboard.windows.storage

import com.joseph.clipboard.desktop.platform.Platform
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal append-only diagnostics log. A packaged (jpackage) build runs with no console, so
 * `System.err`/`println` go nowhere — which is why incoming-file OFFERs that are silently rejected by
 * the admission gate (wrong/missing pairing key, AUTH2 proof failure, downgrade block) are invisible
 * in the field. This writes those events to a file the tester can send back, next to the app's config
 * in the platform's config dir (%LOCALAPPDATA%\OffBridge on Windows, $XDG_CONFIG_HOME/OffBridge on
 * Linux).
 *
 * Best-effort by design — it must never throw into a network/transfer code path.
 */
object DiagLog {
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun w(message: String) {
        try {
            // Resolved per call rather than cached: a log line emitted before main() installs the
            // platform backend would otherwise pin a null path for the rest of the process, silently
            // disabling the one evidence trail a packaged build produces.
            if (!Platform.isInstalled) return
            val logFile = File(Platform.current.configDir, "diagnostics.log")
            logFile.parentFile?.mkdirs()
            logFile.appendText("${stamp.format(Date())}  $message${System.lineSeparator()}")
        } catch (_: Throwable) {
            // Diagnostics must never break the caller.
        }
    }
}
