package com.EntropicSoftwareLab.ClipboardShare.data.db

import android.content.Context
import android.util.Log
import com.EntropicSoftwareLab.ClipboardShare.TransferRecord
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.PeerDevice
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.PeerTransportStat
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.SelfGoStat
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.TransferFileEntity
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.TransferSessionEntity
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.TransportAttempt
import com.EntropicSoftwareLab.ClipboardShare.transport.AttemptOutcome
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportAttemptLog
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDescriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Phase 3 data layer (§6): a single-writer facade over [AppDatabase].
 *
 * All writes are serialized on one background executor (SQLite single-writer
 * anyway); the adaptive-ordering read path is served from an in-memory cache
 * so the controllers never block the main thread — a cold cache simply yields
 * the default ladder order for that one session.
 *
 * It also installs the Room sink for [TransportAttemptLog] and runs the
 * one-time SharedPreferences → Room history import.
 */
object OffGridRepository {

    private const val TAG = "OffGridRepo"
    private const val IMPORT_FLAG_PREFS = "FileBridgePrefs"
    private const val IMPORT_FLAG_KEY = "room_history_imported_v1"

    /** The ladder id of the in-progress / most-recent off-grid session — scopes the diagnostics screen. */
    private const val ACTIVE_LADDER_KEY = "active_offgrid_ladder_id"

    /** Telemetry stubs carry direction 0 + status 0; real sessions overwrite nothing (INSERT IGNORE). */
    private const val STUB_DIRECTION = 0

    private val executor = Executors.newSingleThreadExecutor()
    private val orderCache = ConcurrentHashMap<String, List<Int>>()

    // This device's own GO reliability, cached so GoFitness reads never block the main thread.
    @Volatile private var selfGoSuccess = 0
    @Volatile private var selfGoFailure = 0

    @Volatile
    private var initialized = false

    /** Idempotent. Call early (the controllers call it implicitly via the API below). */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        val appContext = context.applicationContext
        TransportAttemptLog.installSink { attempt -> persistAttempt(appContext, attempt) }
        executor.execute { importHistoryIfNeeded(appContext) }
        // Warm the self-GO cache so GoFitness can read it synchronously off any thread.
        executor.execute {
            try {
                AppDatabase.get(appContext).peerDao().selfGo()?.let {
                    selfGoSuccess = it.goSuccess
                    selfGoFailure = it.goFailure
                }
            } catch (e: Exception) {
                Log.w(TAG, "self-GO warm failed: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------- self-GO reliability

    /**
     * Record whether THIS device just succeeded as the Wi-Fi Direct group owner.
     * Drives [selfGoCounts] / GoFitness so a chronically-bad GO yields over time.
     */
    fun recordSelfGoOutcome(context: Context, ok: Boolean) {
        init(context)
        val appContext = context.applicationContext
        // Update the cache immediately (main thread is fine — volatile ints).
        if (ok) selfGoSuccess++ else selfGoFailure++
        executor.execute {
            try {
                val db = AppDatabase.get(appContext)
                db.peerDao().initSelfGo(SelfGoStat())
                db.peerDao().bumpSelfGo(if (ok) 1 else 0, if (ok) 0 else 1, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "recordSelfGoOutcome failed: ${e.message}")
            }
        }
    }

    /** (goSuccess, goFailure) for THIS device, served from the in-memory cache. */
    fun selfGoCounts(context: Context): Pair<Int, Int> {
        init(context)
        return selfGoSuccess to selfGoFailure
    }

    // -------------------------------------------------------------- peers

    fun recordPeerSeen(context: Context, peerId: String, displayName: String) {
        init(context)
        val appContext = context.applicationContext
        executor.execute {
            try {
                val db = AppDatabase.get(appContext)
                val now = System.currentTimeMillis()
                db.peerDao().insertPeer(PeerDevice(peerId, displayName.take(32), now, now))
                db.peerDao().touchPeer(peerId, displayName.take(32), now)
                orderCache[peerId] = db.peerDao().preferredOrder(peerId)
            } catch (e: Exception) {
                Log.w(TAG, "recordPeerSeen failed: ${e.message}")
            }
        }
    }

    fun recordTransportOutcome(context: Context, peerId: String, tier: TransportDescriptor, ok: Boolean) {
        init(context)
        val appContext = context.applicationContext
        executor.execute {
            try {
                val db = AppDatabase.get(appContext)
                val now = System.currentTimeMillis()
                db.peerDao().insertStat(PeerTransportStat(peerId, tier.id, 0, 0, now))
                db.peerDao().bumpStat(peerId, tier.id, if (ok) 1 else 0, if (ok) 0 else 1, now)
                orderCache[peerId] = db.peerDao().preferredOrder(peerId)
            } catch (e: Exception) {
                Log.w(TAG, "recordTransportOutcome failed: ${e.message}")
            }
        }
    }

    /**
     * Cached adaptive ordering (§3.1 step 2). Non-blocking: returns null for
     * an unknown/cold peer — callers fall back to the default plan order.
     */
    fun preferredTransportOrder(context: Context, peerId: String): List<Int>? {
        init(context)
        return orderCache[peerId]?.takeIf { it.isNotEmpty() }
    }

    // ----------------------------------------------------------- telemetry

    private fun persistAttempt(context: Context, attempt: TransportAttemptLog.Attempt) {
        // Already on the TransportAttemptLog executor — do the DB work inline here
        // on our own executor to keep a single Room write lane.
        executor.execute {
            try {
                val db = AppDatabase.get(context)
                // The attempt's session key is the token-derived ladder id; satisfy
                // the FK with a telemetry stub row (INSERT IGNORE — a real session
                // row with the same id is never overwritten).
                db.sessionDao().insertSession(
                    TransferSessionEntity(
                        sessionId = attempt.sessionId,
                        direction = STUB_DIRECTION,
                        startedUtc = attempt.startedUtc,
                        status = 0
                    )
                )
                if (attempt.outcome == AttemptOutcome.PENDING) {
                    db.attemptDao().insert(
                        TransportAttempt(
                            sessionId = attempt.sessionId,
                            transportId = attempt.transport.id,
                            seq = attempt.seq,
                            startedUtc = attempt.startedUtc,
                            endedUtc = attempt.endedUtc,
                            outcome = attempt.outcome,
                            failureCode = null
                        )
                    )
                } else {
                    db.attemptDao().finish(
                        attempt.sessionId, attempt.seq,
                        attempt.endedUtc, attempt.outcome, attempt.failureCode.id
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "persistAttempt failed: ${e.message}")
            }
        }
    }

    // ------------------------------------------------- diagnostics read

    /** One ladder rung, flattened for the diagnostics UI (ids already → text). */
    data class AttemptRow(
        val seq: Int,
        val tierName: String,
        val outcome: Int,
        val failureCode: Int?,
        val durationMs: Long?
    )

    /** Last off-grid session's ladder attempts, for the diagnostics screen. */
    data class Diagnostics(
        val sessionId: String,
        val startedUtc: Long,
        val attempts: List<AttemptRow>,
        val winningTierName: String?
    )

    /**
     * Record the ladder id of the off-grid session now starting, so [lastSessionDiagnostics] reports
     * THIS session even when it writes no transport_attempt rows (e.g. the control channel never formed)
     * — instead of falling back to the globally-newest session, which would surface a stale prior run.
     */
    fun markActiveLadder(context: Context, ladderId: String) {
        try {
            context.applicationContext
                .getSharedPreferences(IMPORT_FLAG_PREFS, Context.MODE_PRIVATE)
                .edit().putString(ACTIVE_LADDER_KEY, ladderId).apply()
        } catch (e: Exception) {
            Log.w(TAG, "markActiveLadder failed: ${e.message}")
        }
    }

    /**
     * Blocking read of the most recent ladder run — call from a background thread
     * (the diagnostics fragment hops to IO first). SQLite reads are concurrent, so
     * this does not contend with the single write lane.
     */
    fun lastSessionDiagnostics(context: Context): Diagnostics? {
        init(context)
        return try {
            val db = AppDatabase.get(context.applicationContext)
            // Prefer the marked active session: a control-failed session legitimately has zero attempt
            // rows, and we must show "this session, no structured attempts" (→ the rich trace) rather
            // than a stale prior session. Fall back to the globally-newest only when none was marked.
            val activeLadder = try {
                context.applicationContext
                    .getSharedPreferences(IMPORT_FLAG_PREFS, Context.MODE_PRIVATE)
                    .getString(ACTIVE_LADDER_KEY, null)
            } catch (_: Exception) { null }
            val sessionId = activeLadder ?: db.attemptDao().lastSessionId() ?: return null
            val rows = db.attemptDao().forSession(sessionId)
            if (rows.isEmpty()) return null
            val attempts = rows.map { r ->
                AttemptRow(
                    seq = r.seq,
                    tierName = TransportDescriptor.fromId(r.transportId)?.wireName ?: "T${r.transportId}",
                    outcome = r.outcome,
                    failureCode = r.failureCode,
                    durationMs = r.endedUtc?.let { it - r.startedUtc }
                )
            }
            val winner = rows.firstOrNull { it.outcome == AttemptOutcome.CONNECTED }
                ?.let { TransportDescriptor.fromId(it.transportId)?.wireName }
            Diagnostics(sessionId, rows.minOf { it.startedUtc }, attempts, winner)
        } catch (e: Exception) {
            Log.w(TAG, "lastSessionDiagnostics failed: ${e.message}")
            null
        }
    }

    // ------------------------------------------------- history mirror/import

    /** Facade hook: every TransferHistoryManager.addRecord mirrors here. */
    fun mirrorRecord(context: Context, record: TransferRecord) {
        init(context)
        val appContext = context.applicationContext
        executor.execute {
            try {
                writeRecord(appContext, record)
            } catch (e: Exception) {
                Log.w(TAG, "mirrorRecord failed: ${e.message}")
            }
        }
    }

    /** Facade hook for TransferHistoryManager.updateStatus — keeps the resume cursor durable. */
    fun mirrorStatus(
        context: Context,
        sessionId: String,
        status: String,
        filesCompleted: Int?,
        currentFileOffset: Long?
    ) {
        init(context)
        val appContext = context.applicationContext
        executor.execute {
            try {
                val db = AppDatabase.get(appContext)
                val terminal = status in setOf("completed", "failed", "cancelled", "declined")
                db.sessionDao().updateSession(
                    sessionId, statusCode(status),
                    if (terminal) System.currentTimeMillis() else null,
                    null
                )
                if (filesCompleted != null) {
                    val files = db.sessionDao().getFiles(sessionId)
                    for (f in files) {
                        when {
                            f.ordinal < filesCompleted ->
                                db.sessionDao().updateFileProgress(sessionId, f.ordinal, f.sizeBytes, 2)
                            f.ordinal == filesCompleted && currentFileOffset != null ->
                                db.sessionDao().updateFileProgress(sessionId, f.ordinal, currentFileOffset, 1)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "mirrorStatus failed: ${e.message}")
            }
        }
    }

    fun mirrorDelete(context: Context, sessionIds: Set<String>) {
        init(context)
        val appContext = context.applicationContext
        executor.execute {
            try {
                AppDatabase.get(appContext).sessionDao().deleteSessions(sessionIds.toList())
            } catch (e: Exception) {
                Log.w(TAG, "mirrorDelete failed: ${e.message}")
            }
        }
    }

    private fun writeRecord(context: Context, record: TransferRecord) {
        val db = AppDatabase.get(context)
        db.sessionDao().insertSession(
            TransferSessionEntity(
                sessionId = record.sessionId,
                peerId = null, // attached later when the identity key is known
                direction = if (record.direction == "incoming") 1 else 0,
                startedUtc = record.timestamp,
                endedUtc = null,
                status = statusCode(record.status),
                finalTransportId = null
            )
        )
        val files = record.fileNames.mapIndexed { i, name ->
            TransferFileEntity(
                sessionId = record.sessionId,
                ordinal = i,
                fileName = name.replace('\\', '/').substringAfterLast('/'),
                sizeBytes = (record.fileSizes.getOrNull(i) ?: 0L).coerceAtLeast(0L),
                bytesDone = when {
                    i < record.filesCompleted -> (record.fileSizes.getOrNull(i) ?: 0L).coerceAtLeast(0L)
                    i == record.filesCompleted -> record.currentFileOffset.coerceAtLeast(0L)
                    else -> 0L
                },
                status = when {
                    i < record.filesCompleted -> 2
                    i == record.filesCompleted && record.status == "transferring" -> 1
                    else -> 0
                }
            )
        }
        if (files.isNotEmpty()) db.sessionDao().insertFiles(files)
    }

    /** One-time SharedPreferences → Room import (§6 migration). */
    private fun importHistoryIfNeeded(context: Context) {
        try {
            val prefs = context.getSharedPreferences(IMPORT_FLAG_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(IMPORT_FLAG_KEY, false)) return
            val records = com.EntropicSoftwareLab.ClipboardShare.TransferHistoryManager.getRecords(context)
            for (record in records) {
                writeRecord(context, record)
            }
            prefs.edit().putBoolean(IMPORT_FLAG_KEY, true).apply()
            Log.i(TAG, "imported ${records.size} history records into Room")
        } catch (e: Exception) {
            Log.w(TAG, "history import failed: ${e.message}")
        }
    }

    private fun statusCode(status: String): Int = when (status) {
        "transferring", "pending" -> 0
        "paused" -> 1
        "completed" -> 2
        "failed" -> 3
        "cancelled", "declined" -> 4
        else -> 0
    }
}
