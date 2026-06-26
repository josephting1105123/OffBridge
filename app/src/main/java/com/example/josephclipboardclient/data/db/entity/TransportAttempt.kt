package com.EntropicSoftwareLab.ClipboardShare.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * §6 DDL: one row per ladder rung per session — the telemetry that says WHY
 * a device pair fell back. outcome: 0 pending, 1 connected, 2 failed,
 * 3 skipped; failure_code: [com.EntropicSoftwareLab.ClipboardShare.transport.FailureCode] ids.
 */
@Entity(
    tableName = "transport_attempt",
    foreignKeys = [
        ForeignKey(
            entity = TransferSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TransportDim::class,
            parentColumns = ["transport_id"],
            childColumns = ["transport_id"]
        )
    ],
    indices = [
        Index(value = ["session_id"], name = "idx_attempt_session"),
        Index(value = ["session_id", "seq"], unique = true),
        Index("transport_id")
    ]
)
data class TransportAttempt(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "attempt_id") val attemptId: Long = 0L,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "transport_id") val transportId: Int,
    /** 0-based ladder position within the session. */
    @ColumnInfo(name = "seq") val seq: Int,
    @ColumnInfo(name = "started_utc") val startedUtc: Long,
    @ColumnInfo(name = "ended_utc") val endedUtc: Long? = null,
    @ColumnInfo(name = "outcome") val outcome: Int,
    @ColumnInfo(name = "failure_code") val failureCode: Int? = null
)
