package com.EntropicSoftwareLab.ClipboardShare.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** §6 DDL: peer identity = hex SHA-256 of the peer's identity public key (64 chars). */
@Entity(tableName = "peer_device")
data class PeerDevice(
    @PrimaryKey
    @ColumnInfo(name = "peer_id") val peerId: String,
    /** Last seen name — display only, untrusted. */
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "first_seen_utc") val firstSeenUtc: Long,
    @ColumnInfo(name = "last_seen_utc") val lastSeenUtc: Long
)
