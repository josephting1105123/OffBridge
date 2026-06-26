package com.EntropicSoftwareLab.ClipboardShare.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * §6 DDL. Deliberately NO total_bytes / file_count columns: derivable from
 * transfer_file (3NF — no stored derived data); aggregating reads join.
 *
 * status: 0 active, 1 paused, 2 done, 3 failed, 4 cancelled.
 * direction: 0 outgoing, 1 incoming.
 */
@Entity(
    tableName = "transfer_session",
    foreignKeys = [
        ForeignKey(
            entity = PeerDevice::class,
            parentColumns = ["peer_id"],
            childColumns = ["peer_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = TransportDim::class,
            parentColumns = ["transport_id"],
            childColumns = ["final_transport_id"]
        )
    ],
    indices = [
        Index(value = ["peer_id"], name = "idx_session_peer"),
        Index(value = ["started_utc"], name = "idx_session_started"),
        Index("final_transport_id")
    ]
)
data class TransferSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "peer_id") val peerId: String? = null,
    @ColumnInfo(name = "direction") val direction: Int,
    @ColumnInfo(name = "started_utc") val startedUtc: Long,
    @ColumnInfo(name = "ended_utc") val endedUtc: Long? = null,
    @ColumnInfo(name = "status") val status: Int,
    @ColumnInfo(name = "final_transport_id") val finalTransportId: Int? = null
)
