package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.content.Intent
import org.json.JSONArray

object ClipboardHistoryManager {
    private const val PREFS_NAME = "ClipboardShareHistory"
    private const val KEY_HISTORY = "clipboard_ledger"
    private const val MAX_ENTRIES = 100

    fun getHistory(context: Context): List<String> {
        val jsonArray = PrefsJson.readArray(context, PREFS_NAME, KEY_HISTORY)
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i))
        }
        return list
    }

    private fun saveList(context: Context, list: List<String>) {
        val jsonArray = JSONArray()
        for (item in list) { jsonArray.put(item) }
        PrefsJson.writeArray(context, PREFS_NAME, KEY_HISTORY, jsonArray)
        // Mirror to the off-device backup so history survives reinstall/clear-data.
        HistoryBackupManager.requestBackup(context)
    }

    // @Synchronized on the mutators: each is a read-modify-write over the same prefs key, and
    // concurrent writers (receive loop vs UI delete) lost whichever update saved first (AND-05).
    @Synchronized
    fun addEntry(context: Context, text: String) {
        if (text.isBlank()) return
        val list = getHistory(context).toMutableList()
        list.remove(text)
        list.add(0, text)

        if (list.size > MAX_ENTRIES) {
            list.removeAt(list.size - 1)
        }

        saveList(context, list)
    }

    @Synchronized
    fun removeEntry(context: Context, text: String) {
        val list = getHistory(context).toMutableList()
        if (list.remove(text)) {
            saveList(context, list)
        }
    }

    /**
     * Merges backup [entries] underneath the current list (current/newest stays first), de-duping
     * by exact text and capping at [MAX_ENTRIES]. Returns how many new entries were added. Used by
     * [HistoryBackupManager] to recover history without clobbering anything already present.
     */
    @Synchronized
    fun mergeEntries(context: Context, entries: List<String>): Int {
        if (entries.isEmpty()) return 0
        val current = getHistory(context)
        val present = current.toHashSet()
        val added = entries.filter { it.isNotBlank() && present.add(it) }
        if (added.isEmpty()) return 0
        saveList(context, (current + added).take(MAX_ENTRIES))
        // The History tab only repaints on this broadcast or onResume; a restore writes prefs
        // directly, so signal it explicitly (transfers refresh via TransferHistoryManager listeners).
        context.sendBroadcast(
            Intent(ClipboardService.ACTION_HISTORY_UPDATED).setPackage(context.packageName)
        )
        return added.size
    }
}
