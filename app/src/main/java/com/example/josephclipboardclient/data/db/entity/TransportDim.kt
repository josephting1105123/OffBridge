package com.EntropicSoftwareLab.ClipboardShare.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Dimension table (§6 DDL): stable ids are the [com.EntropicSoftwareLab.ClipboardShare.transport.TransportDescriptor]
 * enum ordinals, NOT autoincrement — transport names stored once (3NF).
 */
@Entity(
    tableName = "transport",
    indices = [Index(value = ["name"], unique = true)]
)
data class TransportDim(
    @PrimaryKey
    @ColumnInfo(name = "transport_id") val transportId: Int,
    @ColumnInfo(name = "name") val name: String
)
