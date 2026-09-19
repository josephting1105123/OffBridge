package com.joseph.clipboard.windows.storage

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import java.io.StringReader

/**
 * Parses the packaged translation.json.txt into per-language key→value maps. Each language is a
 * self-contained JSON object preceded by a human-readable header line (e.g. "English (en)"); this
 * slices by header, then parses each block with gson. Kept free of SecurityVault / UI / Android state
 * so it is unit-testable on the host JVM, unlike the object it feeds (TranslationManager).
 *
 * This replaces the previous `"([^"]+)":"([^"]*)"` regex, which silently dropped ANY value that
 * contained an escaped quote (\") — which is exactly why privacy_policy_text / user_agreement_text
 * needed bespoke extraction before. gson unescapes \", \n, and \' natively, so every key now loads.
 * gson runs in lenient mode because a couple of the long legal values use \' (invalid in strict JSON).
 */
object TranslationParser {
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, String>>() {}.type

    /**
     * @param content the full translation.json.txt text.
     * @param languageMap uiName → header string (e.g. "English" → "English (en)").
     * @return uiName → (key → value); languages whose block is absent/unparseable are omitted.
     */
    fun parse(content: String, languageMap: Map<String, String>): Map<String, MutableMap<String, String>> {
        val out = mutableMapOf<String, MutableMap<String, String>>()
        if (content.isBlank()) return out
        // Each header's position is scanned once up front; block boundaries derive from this map.
        val headerPositions = languageMap.values.associateWith { content.indexOf(it) }
        for ((uiName, headerName) in languageMap) {
            val startIndex = headerPositions.getValue(headerName)
            if (startIndex == -1) continue
            // A block runs from its header to the next language header (or EOF) — same slicing as before.
            val endIndex = headerPositions.values.filter { it > startIndex }.minOrNull() ?: content.length
            val block = content.substring(startIndex, endIndex)
            // The JSON object is everything from the first '{' to the last '}' in the block.
            val jsonStart = block.indexOf('{')
            val jsonEnd = block.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd <= jsonStart) continue
            val json = block.substring(jsonStart, jsonEnd + 1)
            try {
                val reader = JsonReader(StringReader(json)).apply { isLenient = true }
                val parsed: Map<String, String>? = gson.fromJson(reader, mapType)
                if (parsed != null) out[uiName] = parsed.toMutableMap()
            } catch (e: Exception) {
                System.err.println("TranslationParser: failed to parse '$headerName' block: ${e.message}")
            }
        }
        return out
    }
}
