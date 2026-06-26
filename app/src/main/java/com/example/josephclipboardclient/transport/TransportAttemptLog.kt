package com.EntropicSoftwareLab.ClipboardShare.transport

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ladder telemetry: one record per ladder rung per session — the data that
 * says WHY a device pair fell back instead of guessing from user reports.
 *
 * Phase 1 sink is an append-only JSONL file (`files/transport_attempts.jsonl`,
 * rotated at 256 KiB). Phase 3 installs a Room sink ([installSink]) that
 * mirrors every record into the `transport_attempt` table; the JSONL file is
 * kept as the zero-dependency debug view.
 */
object TransportAttemptLog {

    data class Attempt(
        val sessionId: String,
        val transport: TransportDescriptor,
        val seq: Int,
        val startedUtc: Long,
        var endedUtc: Long? = null,
        var outcome: Int = AttemptOutcome.PENDING,
        var failureCode: FailureCode = FailureCode.NONE
    )

    private const val TAG = "OffGridLadder"
    /** Append-only JSONL telemetry in filesDir; shared verbatim by the diagnostics screen. */
    const val FILE_NAME = "transport_attempts.jsonl"
    private const val MAX_FILE_BYTES = 256 * 1024L

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val seqCounter = HashMap<String, AtomicInteger>()
    /** Bound seqCounter so old session ids can't leak unbounded memory across many sessions. */
    private const val MAX_TRACKED_SESSIONS = 512

    @Volatile
    private var roomSink: ((Attempt) -> Unit)? = null

    /** Installed once by the Phase 3 data layer; mirrors records into Room. */
    fun installSink(sink: (Attempt) -> Unit) {
        roomSink = sink
    }

    /** Next ladder position for [sessionId] — 0-based, unique per session. */
    @Synchronized
    fun nextSeq(sessionId: String): Int {
        // Cap the map: drop stale entries before tracking a new session so the
        // counter map can't grow without bound across long-lived processes.
        // Safe under @Synchronized; clearing only resets seq numbering, not telemetry.
        if (!seqCounter.containsKey(sessionId) && seqCounter.size >= MAX_TRACKED_SESSIONS) {
            seqCounter.clear()
        }
        return seqCounter.getOrPut(sessionId) { AtomicInteger(0) }.getAndIncrement()
    }

    fun begin(context: Context, sessionId: String, transport: TransportDescriptor): Attempt {
        val attempt = Attempt(
            sessionId = sessionId,
            transport = transport,
            seq = nextSeq(sessionId),
            startedUtc = System.currentTimeMillis()
        )
        write(context, attempt)
        return attempt
    }

    fun end(context: Context, attempt: Attempt, outcome: Int, failureCode: FailureCode = FailureCode.NONE) {
        attempt.endedUtc = System.currentTimeMillis()
        attempt.outcome = outcome
        attempt.failureCode = failureCode
        write(context, attempt)
    }

    private fun write(context: Context, attempt: Attempt) {
        val appContext = context.applicationContext
        val snapshot = attempt.copy()
        ioExecutor.execute {
            try {
                roomSink?.invoke(snapshot)
            } catch (e: Exception) {
                Log.w(TAG, "room sink failed: ${e.message}")
            }
            try {
                val file = File(appContext.filesDir, FILE_NAME)
                if (file.length() > MAX_FILE_BYTES) {
                    file.copyTo(File(appContext.filesDir, "$FILE_NAME.1"), overwrite = true)
                    file.writeText("")
                }
                val json = JSONObject().apply {
                    put("session", snapshot.sessionId)
                    put("transport", snapshot.transport.wireName)
                    put("seq", snapshot.seq)
                    put("started", snapshot.startedUtc)
                    snapshot.endedUtc?.let { put("ended", it) }
                    put("outcome", snapshot.outcome)
                    put("failure", snapshot.failureCode.name)
                }
                file.appendText(json.toString() + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "attempt log write failed: ${e.message}")
            }
        }
    }
}
