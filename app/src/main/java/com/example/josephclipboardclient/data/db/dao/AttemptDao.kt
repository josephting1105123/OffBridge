package com.EntropicSoftwareLab.ClipboardShare.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.TransportAttempt

@Dao
interface AttemptDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(attempt: TransportAttempt): Long

    @Query(
        "UPDATE transport_attempt SET ended_utc = :endedUtc, outcome = :outcome, " +
                "failure_code = :failureCode WHERE session_id = :sessionId AND seq = :seq"
    )
    fun finish(sessionId: String, seq: Int, endedUtc: Long?, outcome: Int, failureCode: Int?)

    @Query("SELECT * FROM transport_attempt WHERE session_id = :sessionId ORDER BY seq")
    fun forSession(sessionId: String): List<TransportAttempt>

    /** Session id of the most recent ladder run (by start time) — drives the diagnostics screen. */
    @Query("SELECT session_id FROM transport_attempt ORDER BY started_utc DESC LIMIT 1")
    fun lastSessionId(): String?
}
