package com.EntropicSoftwareLab.ClipboardShare.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * §6 DDL: replaces the parallel fileNames/fileSizes arrays (a 1NF violation).
 * `(session_id, ordinal, bytes_done)` is exactly the
 * RESUME_REQUEST|sessionId|fileIndex|offset wire triple — durable resume.
 *
 * size_bytes ≥ 0 is enforced at the write path (Room has no CHECK support).
 * status: 0 pending, 1 active, 2 done, 3 failed.
 */
@Entity(
    tableName = "transfer_file",
    primaryKeys = ["session_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = TransferSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("session_id")]
)
data class TransferFileEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    /** Sanitized leaf name only. */
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "bytes_done") val bytesDone: Long = 0L,
    @ColumnInfo(name = "status") val status: Int
)
