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
