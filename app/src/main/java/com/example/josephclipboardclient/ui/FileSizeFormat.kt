package com.EntropicSoftwareLab.ClipboardShare.ui

import java.text.DecimalFormat

/**
 * Formats a queued-selection total as "<n> MB" below 10 MB and "<n> GB" from 10 MB up, with up to
 * two decimals (locale-aware separator, trailing zeros trimmed). Binary units (MiB/GiB) to match
 * the byte maths used everywhere else; the MB/GB suffix stays untranslated like the app's other
 * size strings.
 */
fun formatTotalSize(bytes: Long): String {
    val format = DecimalFormat("0.##")
    val mb = bytes / 1048576.0
    return if (mb < 10.0) "${format.format(mb)} MB" else "${format.format(mb / 1024.0)} GB"
}
