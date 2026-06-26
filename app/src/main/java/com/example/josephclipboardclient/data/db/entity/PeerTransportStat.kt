package com.EntropicSoftwareLab.ClipboardShare.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * §6 DDL: junction with a full composite PK → no partial dependencies (2NF).
 * Drives the adaptive ladder ordering for recognized peers.
 */
@Entity(
    tableName = "peer_transport_stat",
    primaryKeys = ["peer_id", "transport_id"],
    foreignKeys = [
        ForeignKey(
            entity = PeerDevice::class,
            parentColumns = ["peer_id"],
            childColumns = ["peer_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TransportDim::class,
            parentColumns = ["transport_id"],
            childColumns = ["transport_id"]
        )
    ],
    indices = [Index("transport_id")]
)
data class PeerTransportStat(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "transport_id") val transportId: Int,
    @ColumnInfo(name = "success_count") val successCount: Int = 0,
    @ColumnInfo(name = "failure_count") val failureCount: Int = 0,
    @ColumnInfo(name = "last_used_utc") val lastUsedUtc: Long
)
