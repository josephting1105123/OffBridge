package com.joseph.clipboard.windows.theme

import androidx.compose.material.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/*
 * Geist (Vercel's product typeface) for all text — matching the Android client, so the UI reads as
 * intentional rather than stock. Geist is bundled as a variable font (src/main/resources/font, packed
 * into the MSI automatically) and loaded here. Compose Desktop 1.6.x has no `variationSettings` on
 * Font(), so weights are requested by FontWeight and Skia selects/synthesises the wght axis. Inter is
 * also bundled (font/inter_variable.ttf) as a one-line swap if ever preferred. Numeric/data text opts
 * into tabular figures (tnum) via [TabularFigures] / String.withInterNumerals (MixedTypeText.kt) so
 * live values don't reflow as digits change width.
 */
private fun geist(weight: FontWeight) = Font("font/geist_variable.ttf", weight)

val Geist = FontFamily(
    geist(FontWeight.Normal), geist(FontWeight.Medium), geist(FontWeight.SemiBold), geist(FontWeight.Bold),
)

/**
 * A consistent type scale so headings, titles, body and labels share one rhythm across
 * every screen. Wired into [ClipboardTheme]; screens read MaterialTheme.typography.*
 * (h5 = screen header, subtitle1 = section/title, body1 = body, button = button label,
 * caption = secondary/meta). [defaultFontFamily] makes Geist the app font everywhere.
 */
val AppTypography = Typography(
    defaultFontFamily = Geist,
    h4 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    h5 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    h6 = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    subtitle1 = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    subtitle2 = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    body1 = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    body2 = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    button = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.4.sp),
    caption = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
)

/**
 * Tabular figures for numeric/data text (transfer %, MB, MB/s, ETA, timers, counters) so values don't
 * reflow as digits change width. Same Geist base as the rest of the app. Merge onto a base style:
 * `style.merge(TabularFigures)`.
 */
val TabularFigures = TextStyle(fontFamily = Geist, fontFeatureSettings = "tnum")
