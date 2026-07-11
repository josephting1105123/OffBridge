package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mirrors the clipboard ledger and the transfer history into a single JSON file inside the user's
 * chosen save-location folder (the SAF tree already used for received files), so history survives
 * uninstall/reinstall and "Clear data".
 *
 * Why this is needed: both histories live only in app-private SharedPreferences
 * ([ClipboardHistoryManager], [TransferHistoryManager]) — wiped on uninstall/clear-data — and
 * res/xml/data_extraction_rules.xml deliberately excludes SharedPreferences from cloud backup, so
 * nothing recovers them today.
 *
 * Recovery flow: a SAF persisted-URI grant is NOT retained across uninstall/clear-data, so after a
 * reinstall the app cannot read the folder until the user re-selects it in Settings — that re-pick
 * calls [restoreFromSaveFolder]. The launch-time [maybeRestoreOnLaunch] only covers the rarer case
 * where the prefs were lost but the folder grant is still held.
 *
 * Concurrency: backup writes are debounced and serialized against restores via [ioLock]; while a
 * restore repopulates the managers, [isRestoring] suppresses the backup writes they would trigger,
 * so there is no write-back loop.
 */
object HistoryBackupManager {

    private const val BACKUP_FILE_NAME = "ClipboardShare_history_backup.json"
    private const val SAVE_PREFS = FILE_BRIDGE_PREFS
    private const val KEY_SAVE_LOCATION = "save_location_uri"
    private const val SCHEMA_VERSION = 1

    /** Coalesce bursts (e.g. a flurry of transfer status updates) into one write after the last change. */
    private const val DEBOUNCE_MS = 1000L

    /** Sanity ceiling: the real file is well under 1 MB (two 100-item lists); refuse to read a
     *  corrupt/oversized file (which would hold [ioLock] for an unbounded time). */
    private const val MAX_BACKUP_BYTES = 10L * 1024 * 1024

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ioLock = Any()
    private val jobLock = Any()
    private var pendingBackup: Job? = null

    @Volatile
    private var isRestoring = false

    /** Counts merged in during a restore; both zero means nothing was recovered. */
    data class RestoreResult(val clipboardAdded: Int, val transfersAdded: Int) {
        val isEmpty: Boolean get() = clipboardAdded == 0 && transfersAdded == 0
    }

    // --------------------------------------------------------------------- backup

    /**
     * Debounced request to rewrite the backup file. No-op when no save folder is set or while a
     * restore is in progress. Safe to call from any history mutation.
     */
    fun requestBackup(context: Context) {
        if (isRestoring) return
        val appContext = context.applicationContext
        synchronized(jobLock) {
            pendingBackup?.cancel()
            pendingBackup = scope.launch {
                delay(DEBOUNCE_MS)
                writeBackup(appContext)
            }
        }
    }

    private fun writeBackup(context: Context) = synchronized(ioLock) {
        val dir = saveDir(context) ?: return@synchronized
        if (!dir.canWrite()) return@synchronized

        val payload = runCatching {
            JSONObject().apply {
                put("version", SCHEMA_VERSION)
                put("exportedAt", System.currentTimeMillis())
                put("clipboard", JSONArray(ClipboardHistoryManager.getHistory(context)))
                val transfers = JSONArray()
                for (record in TransferHistoryManager.getRecords(context)) transfers.put(record.toJson())
                put("transfers", transfers)
            }.toString()
        }.getOrNull() ?: return@synchronized

        runCatching {
            // Find-or-create — never delete-then-create: a silently failed delete() makes the
            // provider dedupe createFile() to "...(1).json", which the restore's findFile() never
            // sees, so it would read stale data. "wt" truncates the reused file so a shorter payload
            // can't leave a stale tail (plain "w" is not guaranteed to truncate on every provider).
            val target = dir.findFile(BACKUP_FILE_NAME) ?: dir.createFile("*/*", BACKUP_FILE_NAME)
            if (target != null) {
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        }
    }

    // -------------------------------------------------------------------- restore

    /**
     * Restore at app launch, but only when BOTH stores are empty — the genuine "data was lost"
     * signal. Runs on IO; harmless when no folder/grant/backup exists (all no-ops).
     */
    fun maybeRestoreOnLaunch(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            synchronized(ioLock) {
                val clipboardEmpty = ClipboardHistoryManager.getHistory(appContext).isEmpty()
                val transfersEmpty = TransferHistoryManager.getRecords(appContext).isEmpty()
                if (clipboardEmpty && transfersEmpty) restoreInternal(appContext)
            }
        }
    }

    /**
     * Merge-restore from the currently selected save folder. Call on a background thread (the
     * Settings folder-pick does). Newest-wins merge — never clobbers existing local history.
     */
    fun restoreFromSaveFolder(context: Context): RestoreResult =
        synchronized(ioLock) { restoreInternal(context.applicationContext) }

    private fun restoreInternal(context: Context): RestoreResult {
        val dir = saveDir(context) ?: return RestoreResult(0, 0)
        // Set the suppression flag for the whole operation (not just the merge), so any backup
        // requested while we read/parse the file is dropped rather than racing the restore.
        isRestoring = true
        try {
            // findFile/openInputStream hit the ContentResolver and throw SecurityException if the
            // persisted grant was revoked (e.g. after a reinstall) — guard so a Settings re-pick
            // can't crash the calling coroutine. fromTreeUri in saveDir() can't throw (pure ctor).
            val text = runCatching {
                val file = dir.findFile(BACKUP_FILE_NAME) ?: return@runCatching null
                if (file.length() > MAX_BACKUP_BYTES) return@runCatching null
                context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: return RestoreResult(0, 0)

            val json = runCatching { JSONObject(text) }.getOrNull() ?: return RestoreResult(0, 0)
            val clipboardAdded = ClipboardHistoryManager.mergeEntries(context, readStrings(json.optJSONArray("clipboard")))
            val transfersAdded = TransferHistoryManager.mergeRecords(context, readRecords(json.optJSONArray("transfers")))
            return RestoreResult(clipboardAdded, transfersAdded)
        } finally {
            isRestoring = false
        }
    }

    private fun readStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
    }

    private fun readRecords(arr: JSONArray?): List<TransferRecord> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { TransferRecord.fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun saveDir(context: Context): DocumentFile? {
        val uriString = context.getSharedPreferences(SAVE_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SAVE_LOCATION, null) ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriString)) }.getOrNull()
    }
}
