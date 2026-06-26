package com.EntropicSoftwareLab.ClipboardShare.transport

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Process-wide diagnostic trace for the off-grid transport ladder, surfaced on-screen
 * (sender QR screen + receiver scanning screen) so a failed cross-network connection can be
 * diagnosed WITHOUT adb. Each transport stage appends a timestamped line; the visible fragment
 * observes [listener] and renders [snapshot]. Best-effort and lossy (bounded ring buffer) — never
 * a correctness input.
 *
 * Mirrored to logcat under tag "OffGridDiag" AND persisted to disk on every line ([persistedTrace])
 * so the LAST session's full trace is always retrievable from the menu Diagnostics screen on each
 * device — even when the on-failure dialog never appears (e.g. a single-tier HANDSHAKE_FAILED that
 * only shows the retry button) or the process is restarted before the trace can be read.
 */
object TransportDiag {
    private const val MAX_LINES = 80
    /** Last session's human-readable trace, persisted in filesDir for the diagnostics screen. */
    const val TRACE_FILE_NAME = "transport_diag_last.txt"

    private val lines = ArrayDeque<String>()
    private var startMs = 0L
    private var sessionStartWallMs = 0L

    private val ioExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var traceFile: File? = null

    /**
     * Called with the full snapshot on every new line. Set by the visible fragment; cleared in
     * onPause/onDestroyView. May fire on any thread — the fragment must hop to the main thread.
     */
    @Volatile
    var listener: ((String) -> Unit)? = null

    /** Point on-disk persistence at this app's filesDir. Idempotent; cheap to call repeatedly. */
    fun attach(context: Context) {
        if (traceFile == null) {
            traceFile = File(context.applicationContext.filesDir, TRACE_FILE_NAME)
        }
    }

    @Synchronized
    fun log(msg: String) {
        if (startMs == 0L) {
            startMs = SystemClock.elapsedRealtime()
            sessionStartWallMs = System.currentTimeMillis()
        }
        val secs = (SystemClock.elapsedRealtime() - startMs) / 1000.0
        val line = String.format(Locale.US, "%6.1fs  %s", secs, msg)
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        Log.i("OffGridDiag", line)
        val snap = lines.joinToString("\n")
        listener?.invoke(snap)
        persist(snap, sessionStartWallMs)
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    /** Reset the trace + clock at the start of a fresh send/scan session. */
    @Synchronized
    fun reset() {
        lines.clear()
        startMs = SystemClock.elapsedRealtime()
        sessionStartWallMs = System.currentTimeMillis()
    }

    /** The last persisted session trace (wall-clock save time + text), or null if none on disk. */
    fun persistedTrace(context: Context): PersistedTrace? {
        val file = traceFile ?: File(context.applicationContext.filesDir, TRACE_FILE_NAME)
        return try {
            if (!file.exists() || file.length() == 0L) return null
            val raw = file.readText()
            val nl = raw.indexOf('\n')
            if (nl < 0) PersistedTrace(0L, raw)
            else PersistedTrace(raw.substring(0, nl).toLongOrNull() ?: 0L, raw.substring(nl + 1))
        } catch (e: Exception) {
            Log.w("OffGridDiag", "read persisted trace failed: ${e.message}")
            null
        }
    }

    data class PersistedTrace(val savedAtMillis: Long, val text: String)

    /** Atomic-ish overwrite of the trace file on a background thread (write temp, then rename). */
    private fun persist(snap: String, wallMs: Long) {
        val file = traceFile ?: return
        ioExecutor.execute {
            try {
                val body = "$wallMs\n$snap"
                val tmp = File(file.parentFile, "$TRACE_FILE_NAME.tmp")
                tmp.writeText(body)
                if (!tmp.renameTo(file)) {
                    file.writeText(body)
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.w("OffGridDiag", "persist trace failed: ${e.message}")
            }
        }
    }
}
