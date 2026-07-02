package com.joseph.clipboard.windows.storage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.joseph.clipboard.windows.TransferRecord
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Transfer history persistence, schema v2.
 *
 * Write path always emits v2: per-file facts live in a `files` array of
 * {i, n, s} entries; per-session facts (plus the deliberate totalFiles /
 * totalBytes denormalization) live on the record. Read path migrates lazily:
 * if `files` is absent, legacy fileNames×fileSizes are zipped; if `transport`
 * is absent, it defaults to "lan". Records upgrade on the next write — no
 * one-shot migration pass needed.
 */
object TransferHistoryManager {
    private val historyFile: File by lazy {
        val dir = File(System.getProperty("user.home"), ".ClipboardShare")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "transfer_history.json")
    }

    @Synchronized
    fun load(): List<TransferRecord> {
        return try {
            if (!historyFile.exists()) return emptyList()
            val root = JsonParser.parseString(historyFile.readText())
            if (!root.isJsonArray) return emptyList()
            root.asJsonArray.mapNotNull { element ->
                try { recordFromJson(element.asJsonObject) } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    @Synchronized
    fun save(records: List<TransferRecord>) {
        try {
            val array = JsonArray()
            for (record in records) {
                array.add(recordToJson(record))
            }
            // Write atomically so a crash/partial write can't corrupt the
            // history and wipe it on next parse. Write to a temp file in the same
            // directory, then move it into place (atomic when supported, else
            // a non-atomic replace as fallback).
            val tmp = File(historyFile.parentFile, historyFile.name + ".tmp")
            tmp.writeText(array.toString())
            try {
                Files.move(
                    tmp.toPath(), historyFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), historyFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun recordToJson(record: TransferRecord): JsonObject {
        val files = JsonArray()
        for (i in record.fileNames.indices) {
            files.add(JsonObject().apply {
                addProperty("i", i)
                addProperty("n", record.fileNames[i])
                addProperty("s", if (i < record.fileSizes.size) record.fileSizes[i] else 0L)
            })
        }
        return JsonObject().apply {
            addProperty("sessionId", record.sessionId)
            addProperty("deviceName", record.deviceName)
            addProperty("direction", record.direction)
            addProperty("transport", record.transport)
            addProperty("status", record.status)
            addProperty("timestamp", record.timestamp)
            addProperty("filesCompleted", record.filesCompleted)
            addProperty("currentFileOffset", record.currentFileOffset)
            addProperty("totalFiles", record.totalFiles)
            addProperty("totalBytes", record.totalBytes)
            add("files", files)
        }
    }

    private fun recordFromJson(json: JsonObject): TransferRecord {
        val names: List<String>
        val sizes: List<Long>
        if (json.has("files") && json.get("files").isJsonArray) {
            val entries = json.getAsJsonArray("files")
                .map { it.asJsonObject }
                .sortedBy { if (it.has("i")) it.get("i").asInt else 0 }
            names = entries.map { if (it.has("n")) it.get("n").asString else "" }
            sizes = entries.map { if (it.has("s")) it.get("s").asLong else 0L }
        } else {
            names = if (json.has("fileNames")) {
                json.getAsJsonArray("fileNames").map { it.asString }
            } else emptyList()
            sizes = if (json.has("fileSizes")) {
                json.getAsJsonArray("fileSizes").map { it.asLong }
            } else List(names.size) { 0L }
        }
        // Guard required fields with has()/!isJsonNull so a record missing a
        // field or carrying a JSON null doesn't throw (the per-element try in
        // load() still drops genuinely broken records); resilient to schema drift.
        fun str(key: String): String =
            if (json.has(key) && !json.get(key).isJsonNull) json.get(key).asString else ""
        fun long(key: String, default: Long): Long =
            if (json.has(key) && !json.get(key).isJsonNull) json.get(key).asLong else default
        return TransferRecord(
            sessionId = str("sessionId"),
            deviceName = str("deviceName"),
            direction = str("direction"),
            fileNames = names,
            totalBytes = if (json.has("totalBytes") && !json.get("totalBytes").isJsonNull) json.get("totalBytes").asLong else sizes.sum(),
            timestamp = long("timestamp", 0L),
            status = str("status"),
            filesCompleted = if (json.has("filesCompleted")) json.get("filesCompleted").asInt else 0,
            totalFiles = if (json.has("totalFiles")) json.get("totalFiles").asInt else names.size,
            fileSizes = sizes,
            currentFileOffset = if (json.has("currentFileOffset")) json.get("currentFileOffset").asLong else 0L,
            transport = if (json.has("transport")) json.get("transport").asString else "lan"
        )
    }
}
