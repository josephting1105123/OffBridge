package com.joseph.clipboard.windows.update

/**
 * Dotted-integer version comparison — mirrors OffBridge.iss's CompareVersions (Pascal Script)
 * so the in-app update checker agrees with what the installer itself uses to detect upgrades.
 * Numeric per component, not lexicographic: "3.5.2" < "3.5.10". A missing or non-numeric component
 * is treated as 0, matching the installer's StrToIntDef(part, 0) fallback.
 */
object VersionCompare {
    /** -1 if [a] < [b], 0 if equal, 1 if [a] > [b]. */
    fun compare(a: String, b: String): Int {
        val partsA = a.split(".")
        val partsB = b.split(".")
        val len = maxOf(partsA.size, partsB.size)
        for (i in 0 until len) {
            val na = partsA.getOrNull(i)?.toIntOrNull() ?: 0
            val nb = partsB.getOrNull(i)?.toIntOrNull() ?: 0
            if (na != nb) return na.compareTo(nb)
        }
        return 0
    }

    /** True iff [remote] is a strictly newer version than [local]. */
    fun isNewer(remote: String, local: String): Boolean = compare(remote, local) > 0
}
