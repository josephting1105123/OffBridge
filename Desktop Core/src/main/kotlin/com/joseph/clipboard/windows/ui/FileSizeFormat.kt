package com.joseph.clipboard.windows.ui

import com.joseph.clipboard.windows.storage.TranslationManager
import java.io.File
import java.text.DecimalFormat

/** Demo total shown by the tutorial's fake Send-Via dialog (~1.2 GB). */
internal const val TUTORIAL_DEMO_TOTAL_BYTES = 1_288_490_189L

/**
 * Formats a queued-selection total as "<n> MB" below 10 MB and "<n> GB" from 10 MB up, with up to
 * two decimals (locale-aware separator, trailing zeros trimmed). Binary units (MiB/GiB) to match
 * the byte maths used elsewhere; the MB/GB suffix stays untranslated like the app's other size
 * strings. Mirrors the Android client's formatTotalSize.
 */
internal fun formatTotalSize(bytes: Long): String {
    val format = DecimalFormat("0.##")
    val mb = bytes / 1048576.0
    return if (mb < 10.0) "${format.format(mb)} MB" else "${format.format(mb / 1024.0)} GB"
}

/** Localized "<size> of files selected" line shown under the Send-Via title. */
internal fun sendViaSizeLabel(bytes: Long): String {
    val template = TranslationManager.getString("send_via_size_selected")
        .takeIf { it != "send_via_size_selected" } ?: "%s of files selected"
    return String.format(template, formatTotalSize(bytes))
}

/** Total bytes of the queued entries; folder entries are walked recursively. */
internal fun totalSizeOf(files: List<File>): Long = files.sumOf { entry ->
    if (entry.isDirectory) entry.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    else entry.length()
}
