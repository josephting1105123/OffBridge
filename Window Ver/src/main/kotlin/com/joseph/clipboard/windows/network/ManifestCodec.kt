package com.joseph.clipboard.windows.network

/**
 * Parses the LAN file-offer MANIFEST line: `MANIFEST|name:size|name:size|...`.
 *
 * Each entry is split on its LAST ':' so a filename that itself contains ':' is preserved — this is
 * byte-for-byte parity with the Android client, whose TransferDaemon uses `lastIndexOf(':')`. Entries
 * with no ':' or a non-numeric size are skipped (defensive against malformed/hostile input). Pure and
 * dependency-free so it is unit-testable on the host JVM.
 */
object ManifestCodec {
    /**
     * Builds the wire MANIFEST line for [entries] (name to OFFER-time size), including the
     * historical trailing pipe AND the terminating newline: `MANIFEST|a:1|b:2|\n`. Both platforms'
     * parsers tolerate the trailing pipe but older peers were only ever sent this exact shape, so
     * it is frozen wire format — do not "clean it up".
     */
    fun encode(entries: List<Pair<String, Long>>): String {
        val sb = StringBuilder("MANIFEST|")
        for ((name, size) in entries) sb.append(name).append(':').append(size).append('|')
        sb.append('\n')
        return sb.toString()
    }

    fun parse(manifestLine: String): List<Pair<String, Long>> {
        if (!manifestLine.startsWith("MANIFEST|")) return emptyList()
        val items = manifestLine.substringAfter("MANIFEST|").split("|").filter { it.isNotBlank() }
        val out = ArrayList<Pair<String, Long>>(items.size)
        for (item in items) {
            val lastColonIndex = item.lastIndexOf(':')
            if (lastColonIndex > 0) {
                val name = item.substring(0, lastColonIndex)
                val size = item.substring(lastColonIndex + 1).toLongOrNull() ?: continue
                out.add(name to size)
            }
        }
        return out
    }
}
