@file:OptIn(ExperimentalTextApi::class)

package com.EntropicSoftwareLab.ClipboardShare.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.EntropicSoftwareLab.ClipboardShare.R

/*
 * Type system, mirrored on the Windows client (theme/Type.kt):
 *  - Voice: the platform serif (FontFamily.Serif) on every slot. It resolves to the device's own
 *    serif on Android and through DirectWrite on Windows, so the shared decision is the serif
 *    voice, not identical glyphs. Nothing about it is bundled.
 *  - Numerals: IBM Plex Sans, bundled as a variable font in res/font and merged into numeric/data
 *    text via [TabularFigures] (tnum) so live values don't reflow as digits change width. The
 *    View-layer styles (res/values/styles.xml) also use Plex for their content text.
 * Geist and Inter were dropped when the ramp moved to serif; neither ships any more.
 */
val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_variable, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_variable, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_variable, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_variable, FontWeight.Bold),
)

/**
 * Type scale mirroring res/values/styles.xml (TextAppearance.App.*): Bold headings/titles, Medium
 * labels, Normal body. Starts from the M3 default scale (so every slot a screen might touch is covered),
 * puts the serif voice on every slot — content and controls alike — then overrides the five app
 * styles with their sizes/weights.
 */
private val Base = Typography()
val AppTypography = Base.copy(
    displayLarge = Base.displayLarge.copy(fontFamily = FontFamily.Serif),
    displayMedium = Base.displayMedium.copy(fontFamily = FontFamily.Serif),
    displaySmall = Base.displaySmall.copy(fontFamily = FontFamily.Serif),
    headlineLarge = Base.headlineLarge.copy(fontFamily = FontFamily.Serif),
    headlineMedium = Base.headlineMedium.copy(fontFamily = FontFamily.Serif),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 24.sp), // App.Headline
    titleLarge = Base.titleLarge.copy(fontFamily = FontFamily.Serif),
    titleMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 18.sp),   // App.Title
    titleSmall = Base.titleSmall.copy(fontFamily = FontFamily.Serif),
    bodyLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 15.sp),   // App.Body
    bodyMedium = Base.bodyMedium.copy(fontFamily = FontFamily.Serif),
    bodySmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 12.sp),   // App.Caption
    labelLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 14.sp),     // App.Label
    labelMedium = Base.labelMedium.copy(fontFamily = FontFamily.Serif),
    labelSmall = Base.labelSmall.copy(fontFamily = FontFamily.Serif),
)

/**
 * Tabular (monospaced) figures — apply to live numeric/data text (transfer %, MB, MB/s, ETA, timers,
 * the tutorial "i / N" counter) so values don't reflow as digits change width. Plex base to match the
 * content text these numbers sit in. Merge onto any base style: `style.merge(TabularFigures)`.
 */
val TabularFigures = TextStyle(fontFamily = PlexSans, fontFeatureSettings = "tnum")
