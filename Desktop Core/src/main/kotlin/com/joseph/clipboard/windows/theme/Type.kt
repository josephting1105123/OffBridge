package com.joseph.clipboard.windows.theme

import androidx.compose.material.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/*
 * Serif type voice, matching the Android client's 3.6.2 ramp (ui/theme/Type.kt): the whole scale
 * rides FontFamily.Serif at Medium weight for headings/titles/buttons and Normal for body/captions.
 * On the desktop JVM the generic serif alias resolves through DirectWrite (Times New Roman on stock
 * Windows 10/11); Android resolves the same alias to its device serif — the shared decision is the
 * serif voice, not identical glyphs. CJK text falls back to system fonts via Skia's paragraph
 * fallback, as it always has. Numeric/data text stays on IBM Plex Sans tabular figures (tnum) via
 * [TabularFigures] / String.withInterNumerals (MixedTypeText.kt) — same split as Android — so live
 * values don't reflow as digits change width.
 */
private fun plex(weight: FontWeight) = Font("font/ibm_plex_sans_variable.ttf", weight)

val PlexSans = FontFamily(
    plex(FontWeight.Normal), plex(FontWeight.Medium), plex(FontWeight.SemiBold), plex(FontWeight.Bold),
)

/**
 * A consistent type scale so headings, titles, body and labels share one rhythm across
 * every screen. Wired into [ClipboardTheme]; screens read MaterialTheme.typography.*
 * (h5 = screen header, subtitle1 = section/title, body1 = body, button = button label,
 * caption = secondary/meta).
 */
val AppTypography = Typography(
    defaultFontFamily = FontFamily.Serif,
    h4 = TextStyle(fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp),
    h5 = TextStyle(fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp),
    h6 = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    subtitle1 = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    subtitle2 = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    body1 = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    body2 = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    button = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    caption = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
)

/**
 * Tabular figures for numeric/data text (transfer %, MB, MB/s, ETA, timers, counters) so values don't
 * reflow as digits change width. Plex base to match the content text these numbers sit in. Merge onto
 * a base style: `style.merge(TabularFigures)`.
 */
val TabularFigures = TextStyle(fontFamily = PlexSans, fontFeatureSettings = "tnum")
