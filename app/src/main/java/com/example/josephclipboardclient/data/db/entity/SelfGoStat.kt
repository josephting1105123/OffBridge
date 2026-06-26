package com.EntropicSoftwareLab.ClipboardShare.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * This device's own Wi-Fi Direct group-owner (GO) reliability — a single
 * singleton row (id = 0). Incremented whenever THIS device hosted the group:
 * [goSuccess] when the peer connected and data flowed, [goFailure] when it
 * hosted but the peer couldn't join/serve. Self-keyed (not per-peer) so the
 * learned signal generalizes to brand-new peers: a chronically-bad GO lowers
 * its own GoFitness score and yields the GO role to the more-capable device.
 */
@Entity(tableName = "self_go_stat")
data class SelfGoStat(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 0,
    @ColumnInfo(name = "go_success") val goSuccess: Int = 0,
    @ColumnInfo(name = "go_failure") val goFailure: Int = 0,
    @ColumnInfo(name = "last_used_utc") val lastUsedUtc: Long = 0
)
