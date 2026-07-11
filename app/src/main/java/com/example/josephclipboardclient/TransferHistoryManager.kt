package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import android.os.Handler
import android.os.Looper

/**
 * Transfer history record, schema v2 (§3.2 of the V3.0 plan).
 *
 * Logical model is 3NF: per-session facts live on the record, per-file facts
 * in the `files` array ({i, n, s} entries). `totalFiles`/`totalBytes` are
 * retained as denormalized aggregates only because the list view renders up
 * to 100 records per frame and recomputing sums per bind is wasteful — a
 * deliberate, documented denormalization. The in-memory `fileNames`/`fileSizes`
 * lists are the materialized view of `files` that all existing UI binds to.
 *
 * Physical storage stays JSON-in-SharedPreferences: the dataset is hard-capped
 * at 100 records, read in full on every open and never queried relationally.
 */
data class TransferRecord(
    val sessionId: String,
    val deviceName: String,
    val direction: String,  // "incoming" or "outgoing"
    val fileNames: List<String>,
    val totalBytes: Long,
    val timestamp: Long,
    val status: String,  // "completed", "paused", "failed"
    val filesCompleted: Int,
    val totalFiles: Int,
    val fileSizes: List<Long>,
    val currentFileOffset: Long,
    val transport: String = "lan"  // "lan" or "p2p"
) {
    /** Write path always emits v2. */
    fun toJson(): JSONObject {
        val filesArray = JSONArray()
        for (i in fileNames.indices) {
            filesArray.put(JSONObject().apply {
                put("i", i)
                put("n", fileNames[i])
                put("s", if (i < fileSizes.size) fileSizes[i] else 0L)
            })
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("deviceName", deviceName)
            put("direction", direction)
            put("transport", transport)
            put("totalBytes", totalBytes)
            put("timestamp", timestamp)
            put("status", status)
            put("filesCompleted", filesCompleted)
            put("totalFiles", totalFiles)
            put("currentFileOffset", currentFileOffset)
            put("files", filesArray)
        }
    }

    companion object {
        /**
         * Migration rule: if `files` is absent, zip legacy fileNames×fileSizes;
         * if `transport` is absent, default "lan". Records upgrade lazily on
         * the next write — the 100-record cap bounds legacy lifetime.
         */
        fun fromJson(json: JSONObject): TransferRecord {
            val names: List<String>
            val sizes: List<Long>
            val filesArray = json.optJSONArray("files")
            if (filesArray != null) {
                val entries = (0 until filesArray.length())
                    .map { filesArray.getJSONObject(it) }
                    .sortedBy { it.optInt("i", 0) }
                names = entries.map { it.optString("n", "") }
                sizes = entries.map { it.optLong("s", 0L) }
            } else {
                val namesArray = json.getJSONArray("fileNames")
                names = (0 until namesArray.length()).map { namesArray.getString(it) }
                val sizesArray = json.optJSONArray("fileSizes")
                sizes = if (sizesArray != null) {
                    (0 until sizesArray.length()).map { sizesArray.getLong(it) }
                } else {
                    List(names.size) { 0L } // fallback for old records
                }
            }
            return TransferRecord(
                sessionId = json.getString("sessionId"),
                deviceName = json.getString("deviceName"),
                direction = json.getString("direction"),
                fileNames = names,
                totalBytes = json.getLong("totalBytes"),
                timestamp = json.getLong("timestamp"),
                status = json.getString("status"),
                filesCompleted = json.optInt("filesCompleted", 0),
                totalFiles = json.optInt("totalFiles", 0),
                fileSizes = sizes,
                currentFileOffset = json.optLong("currentFileOffset", 0L),
                transport = json.optString("transport", "lan")
            )
        }
    }
}

object TransferHistoryManager {
    interface HistoryUpdateListener {
        fun onHistoryChanged()
    }
    
    private val listeners = mutableListOf<HistoryUpdateListener>()

    fun addListener(listener: HistoryUpdateListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: HistoryUpdateListener) {
        listeners.remove(listener)
    }

    private const val PREFS_NAME = "TransferHistoryPrefs"
    private const val KEY_HISTORY = "transfer_history"
    private const val MAX_RECORDS = 100

    // Mutators are @Synchronized (one monitor for the singleton): each is a read-modify-write
    // over the same prefs key, and concurrent sessions (e.g. two transfers finishing together)
    // lost whichever update saved first (AND-05).
    @Synchronized
    fun addRecord(context: Context, record: TransferRecord) {
        val records = getRecords(context).toMutableList()
        // Update existing record if same sessionId
        val existingIndex = records.indexOfFirst { it.sessionId == record.sessionId }
        if (existingIndex >= 0) {
            records[existingIndex] = record
        } else {
            records.add(0, record)
        }
        // Trim to max
        while (records.size > MAX_RECORDS) records.removeAt(records.size - 1)
        saveRecords(context, records)
        // V4 §6: this manager is the UI's materialized view; Room is the
        // normalized durable store. Mirror is async, never blocks the UI.
        com.EntropicSoftwareLab.ClipboardShare.data.db.OffGridRepository.mirrorRecord(context, record)
    }

    @Synchronized
    fun updateStatus(context: Context, sessionId: String, status: String, filesCompleted: Int? = null, currentFileOffset: Long? = null) {
        val records = getRecords(context).toMutableList()
        val index = records.indexOfFirst { it.sessionId == sessionId }
        if (index >= 0) {
            val old = records[index]
            records[index] = old.copy(
                status = status,
                filesCompleted = filesCompleted ?: old.filesCompleted,
                currentFileOffset = currentFileOffset ?: old.currentFileOffset
            )
            saveRecords(context, records)
        }
        com.EntropicSoftwareLab.ClipboardShare.data.db.OffGridRepository
            .mirrorStatus(context, sessionId, status, filesCompleted, currentFileOffset)
    }

    @Synchronized
    fun updateFileNames(context: Context, sessionId: String, fileNames: List<String>) {
        val records = getRecords(context).toMutableList()
        val index = records.indexOfFirst { it.sessionId == sessionId }
        if (index >= 0) {
            val old = records[index]
            records[index] = old.copy(fileNames = fileNames)
            saveRecords(context, records)
        }
    }

    fun getRecords(context: Context): List<TransferRecord> {
        return try {
            val array = PrefsJson.readArray(context, PREFS_NAME, KEY_HISTORY)
            // Parse each record independently: one malformed/legacy entry must not sink the whole
            // history. The old single try/catch returned emptyList() on any bad record, and the next
            // addRecord/updateStatus then saved only the new record — permanently wiping the rest
            // (and the JSON backup). Mirrors HistoryBackupManager.readRecords' per-record isolation.
            (0 until array.length()).mapNotNull { i ->
                runCatching { TransferRecord.fromJson(array.getJSONObject(i)) }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Returns records sorted by timestamp descending.
     */
    fun getSortedRecords(context: Context): List<TransferRecord> {
        return getRecords(context).sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun deleteRecords(context: Context, sessionIds: Set<String>) {
        val records = getRecords(context).toMutableList()
        records.removeAll { it.sessionId in sessionIds }
        saveRecords(context, records)
        com.EntropicSoftwareLab.ClipboardShare.data.db.OffGridRepository.mirrorDelete(context, sessionIds)
    }

    /**
     * Merges [incoming] backup records by sessionId (existing local record wins on conflict),
     * sorts newest-first and caps at [MAX_RECORDS]. Returns how many new records were added.
     * Used by [HistoryBackupManager] to recover history without clobbering newer local data.
     */
    @Synchronized
    fun mergeRecords(context: Context, incoming: List<TransferRecord>): Int {
        if (incoming.isEmpty()) return 0
        val bySession = LinkedHashMap<String, TransferRecord>()
        for (record in getRecords(context)) bySession[record.sessionId] = record
        val added = mutableListOf<TransferRecord>()
        for (record in incoming) {
            if (!bySession.containsKey(record.sessionId)) {
                bySession[record.sessionId] = record
                added.add(record)
            }
        }
        if (added.isEmpty()) return 0
        val merged = bySession.values.sortedByDescending { it.timestamp }.take(MAX_RECORDS)
        saveRecords(context, merged)
        // Only mirror + count records that actually survived the cap, so Room can't accumulate
        // rows the UI never shows and the recovered-count toast can't overstate what was kept.
        val survivingIds = merged.mapTo(HashSet()) { it.sessionId }
        val persisted = added.filter { it.sessionId in survivingIds }
        for (record in persisted) {
            com.EntropicSoftwareLab.ClipboardShare.data.db.OffGridRepository.mirrorRecord(context, record)
        }
        return persisted.size
    }

    private fun saveRecords(context: Context, records: List<TransferRecord>) {
        val array = JSONArray()
        for (record in records) {
            array.put(record.toJson())
        }
        PrefsJson.writeArray(context, PREFS_NAME, KEY_HISTORY, array)
        // Mirror to the off-device backup so history survives reinstall/clear-data.
        HistoryBackupManager.requestBackup(context)

        Handler(Looper.getMainLooper()).post {
            for (listener in listeners) {
                listener.onHistoryChanged()
            }
        }
    }
}
