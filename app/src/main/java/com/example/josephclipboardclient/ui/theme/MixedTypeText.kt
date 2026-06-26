package com.EntropicSoftwareLab.ClipboardShare.ui.theme

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/*
 * Renders digits with tabular figures so live numbers don't reflow; letters and digits both use the
 * surrounding TextStyle's font (Roboto). Call sites do:
 * Text(text.withInterNumerals(), style = MaterialTheme.typography.bodyLarge).
 */

// A numeric run: digits with optional . , : / separators between them, and an optional trailing %.
// Matches "12", "12.5", "12:30", "12/25", "100%" — leaves surrounding letters untouched.
private val NUMERIC_RUN = Regex("""\d+([.,:/]\d+)*%?""")

private val InterNumeralSpan = SpanStyle(fontFeatureSettings = "tnum")

/** Wrap every numeric run in [this] with Inter + tabular figures; leave letters in the base font. */
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
