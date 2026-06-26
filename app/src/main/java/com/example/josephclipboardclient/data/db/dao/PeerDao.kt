package com.EntropicSoftwareLab.ClipboardShare.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.PeerDevice
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.PeerTransportStat
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.SelfGoStat

@Dao
interface PeerDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPeer(peer: PeerDevice): Long

    @Query("UPDATE peer_device SET display_name = :name, last_seen_utc = :seenUtc WHERE peer_id = :peerId")
    fun touchPeer(peerId: String, name: String, seenUtc: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertStat(stat: PeerTransportStat): Long

    @Query(
        "UPDATE peer_transport_stat SET " +
                "success_count = success_count + :successDelta, " +
                "failure_count = failure_count + :failureDelta, " +
                "last_used_utc = :nowUtc " +
                "WHERE peer_id = :peerId AND transport_id = :transportId"
    )
    fun bumpStat(peerId: String, transportId: Int, successDelta: Int, failureDelta: Int, nowUtc: Long)

    /**
     * Adaptive ladder ordering: transports that ever succeeded for this peer,
     * best success-ratio first, most-recent as the tie-break. O(log) via the
     * composite PK; the table is tiny (≤ 6 rows per peer).
     */
    @Query(
        "SELECT transport_id FROM peer_transport_stat " +
                "WHERE peer_id = :peerId AND success_count > 0 " +
                "ORDER BY (success_count * 1.0) / (success_count + failure_count) DESC, last_used_utc DESC"
    )
    fun preferredOrder(peerId: String): List<Int>

    // ---- This device's own GO reliability (singleton row id=0) ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun initSelfGo(row: SelfGoStat): Long

    @Query(
        "UPDATE self_go_stat SET " +
                "go_success = go_success + :successDelta, " +
                "go_failure = go_failure + :failureDelta, " +
                "last_used_utc = :nowUtc WHERE id = 0"
    )
    fun bumpSelfGo(successDelta: Int, failureDelta: Int, nowUtc: Long)

    @Query("SELECT * FROM self_go_stat WHERE id = 0")
    fun selfGo(): SelfGoStat?
}
