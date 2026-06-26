package com.joseph.clipboard.windows.theme

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/*
 * Renders digits with tabular figures so live numbers don't reflow; letters and digits both use the
 * surrounding TextStyle's font (Geist). Mirror of the Android helper — same Geist-everywhere result,
 * so the name is kept for parity. Call sites do:
 * Text(text.withInterNumerals(), style = MaterialTheme.typography.body1).
 */

// A numeric run: digits with optional . , : / separators between them, and an optional trailing %.
private val NUMERIC_RUN = Regex("""\d+([.,:/]\d+)*%?""")

private val InterNumeralSpan = SpanStyle(fontFamily = Geist, fontFeatureSettings = "tnum")

/** Wrap every numeric run in [this] with tabular figures; both letters and digits stay Geist. */
fun String.withInterNumerals(): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in NUMERIC_RUN.findAll(this@withInterNumerals)) {
        if (match.range.first > last) {
            append(this@withInterNumerals.substring(last, match.range.first))
        }
        withStyle(InterNumeralSpan) { append(match.value) }
        last = match.range.last + 1
    }
    if (last < this@withInterNumerals.length) {
        append(this@withInterNumerals.substring(last))
    }
}
