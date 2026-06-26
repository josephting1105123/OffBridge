package com.EntropicSoftwareLab.ClipboardShare.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.TransferFileEntity
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.TransferSessionEntity

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSession(session: TransferSessionEntity): Long

    @Query(
        "UPDATE transfer_session SET status = :status, ended_utc = :endedUtc, " +
                "final_transport_id = COALESCE(:finalTransportId, final_transport_id) " +
                "WHERE session_id = :sessionId"
    )
    fun updateSession(sessionId: String, status: Int, endedUtc: Long?, finalTransportId: Int?)

    @Query("UPDATE transfer_session SET peer_id = :peerId WHERE session_id = :sessionId")
    fun attachPeer(sessionId: String, peerId: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFiles(files: List<TransferFileEntity>)

    /** Durable resume cursor — O(1) point update on the composite PK. */
    @Query(
        "UPDATE transfer_file SET bytes_done = :bytesDone, status = :status " +
                "WHERE session_id = :sessionId AND ordinal = :ordinal"
    )
    fun updateFileProgress(sessionId: String, ordinal: Int, bytesDone: Long, status: Int)

    @Query("SELECT * FROM transfer_session WHERE session_id = :sessionId")
    fun getSession(sessionId: String): TransferSessionEntity?

    @Query("SELECT * FROM transfer_file WHERE session_id = :sessionId ORDER BY ordinal")
    fun getFiles(sessionId: String): List<TransferFileEntity>

    @Query("DELETE FROM transfer_session WHERE session_id IN (:sessionIds)")
    fun deleteSessions(sessionIds: List<String>)
}
