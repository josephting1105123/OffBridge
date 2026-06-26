package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import org.json.JSONArray

object PrefsJson {
    /** Reads a JSONArray from SharedPreferences. Returns an empty array on corrupt/missing data. */
    fun readArray(context: Context, prefsName: String, key: String): JSONArray {
        val jsonString = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(key, "[]") ?: "[]"
        return try {
            JSONArray(jsonString)
        } catch (e: Exception) {
            JSONArray("[]")
        }
    }

    /** Persists a JSONArray into SharedPreferences under the given key. */
    fun writeArray(context: Context, prefsName: String, key: String, array: JSONArray) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(key, array.toString())
            .apply()
    }
}
