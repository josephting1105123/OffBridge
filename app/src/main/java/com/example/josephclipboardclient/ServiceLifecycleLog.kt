package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Why the background daemon is (or isn't) running, kept across process death.
 *
 * [TransportDiag] can't answer that: it's scoped to one off-grid send/scan session and calls
 * reset() at the start of each, so anything written at boot is gone by the time the user looks.
 * The questions this file exists to answer are all post-mortem — did BOOT_COMPLETED arrive at all,
 * did startForeground() throw, was the process killed with the task — and every one of them is
 * asked after a restart, so the trace has to outlive the process that wrote it.
 *
 * Append-only, bounded, best-effort. Never a correctness input: a failed write costs a diagnostic
 * line, not a feature.
 */
object ServiceLifecycleLog {

    private const val FILE_NAME = "service_lifecycle.txt"

    /** Trim to [KEEP_BYTES] once the file passes this, so it can't grow without bound. */
    private const val MAX_BYTES = 64 * 1024
    private const val KEEP_BYTES = 32 * 1024

    /** Single thread so appends stay ordered and never block the caller (often the main thread). */
    private val ioExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var logFile: File? = null

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Point the log at this app's filesDir. Idempotent; safe to call from every entry point. */
    fun attach(context: Context) {
        if (logFile == null) {
            logFile = File(context.applicationContext.filesDir, FILE_NAME)
        }
    }

    /**
     * Record one lifecycle event. Callers that may run before any Activity exists (BootReceiver)
     * should pass a context so the file can be resolved on first use.
     */
    fun log(context: Context?, msg: String) {
        if (context != null) attach(context)
        Log.i("ServiceLifecycle", msg)
        val target = logFile ?: return
        val line = "${stamp.format(Date())}  $msg\n"
        ioExecutor.execute {
            try {
                target.appendText(line)
                if (target.length() > MAX_BYTES) trim(target)
            } catch (e: Exception) {
                // Diagnostics must never take the app down with them.
                Log.w("ServiceLifecycle", "append failed: ${e.message}")
            }
        }
    }

    /** The whole trace, for the Diagnostics screen. Empty string when nothing has been written. */
    fun snapshot(): String = try {
        logFile?.takeIf { it.exists() }?.readText().orEmpty()
    } catch (e: Exception) {
        ""
    }

    /** Drop the oldest lines, keeping the tail whole — a half-line at the head reads as corruption. */
    private fun trim(target: File) {
        val text = target.readText()
        val tail = text.takeLast(KEEP_BYTES)
        val firstBreak = tail.indexOf('\n')
        target.writeText(if (firstBreak >= 0) tail.substring(firstBreak + 1) else tail)
    }
}
