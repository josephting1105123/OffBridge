package com.joseph.clipboard.windows.theme

import androidx.compose.material.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/*
 * Two-voice type system, matching the Android client (ui/theme/Type.kt):
 *  - Content voice: IBM Plex Sans (headings, titles, body, captions) — humanist letterforms with
 *    Latin + Cyrillic coverage.
 *  - Control voice: Geist on the button slot, so controls keep their tighter, tracked look.
 * Both are bundled as variable fonts (src/main/resources/font, packed into the MSI automatically).
 * Compose Desktop 1.6.x has no `variationSettings` on Font(), so weights are requested by FontWeight
 * and Skia selects/synthesises the wght axis. Inter is also bundled (font/inter_variable.ttf) as a
 * one-line swap if ever preferred. Numeric/data text opts into tabular figures (tnum) via
 * [TabularFigures] / String.withInterNumerals (MixedTypeText.kt) so live values don't reflow as
 * digits change width.
 */
private fun geist(weight: FontWeight) = Font("font/geist_variable.ttf", weight)
private fun plex(weight: FontWeight) = Font("font/ibm_plex_sans_variable.ttf", weight)

val Geist = FontFamily(
    geist(FontWeight.Normal), geist(FontWeight.Medium), geist(FontWeight.SemiBold), geist(FontWeight.Bold),
)

val PlexSans = FontFamily(
    plex(FontWeight.Normal), plex(FontWeight.Medium), plex(FontWeight.SemiBold), plex(FontWeight.Bold),
)

/**
 * A consistent type scale so headings, titles, body and labels share one rhythm across
 * every screen. Wired into [ClipboardTheme]; screens read MaterialTheme.typography.*
 * (h5 = screen header, subtitle1 = section/title, body1 = body, button = button label,
 * caption = secondary/meta). [defaultFontFamily] makes Plex the content font; the button slot pins
 * Geist so controls keep the previous look.
 */
val AppTypography = Typography(
    defaultFontFamily = PlexSans,
    h4 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    h5 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    h6 = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    subtitle1 = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    subtitle2 = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    body1 = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    body2 = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    button = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.4.sp),
    caption = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
)

/**
 * Tabular figures for numeric/data text (transfer %, MB, MB/s, ETA, timers, counters) so values don't
 * reflow as digits change width. Plex base to match the content text these numbers sit in. Merge onto
 * a base style: `style.merge(TabularFigures)`.
 */
val TabularFigures = TextStyle(fontFamily = PlexSans, fontFeatureSettings = "tnum")
