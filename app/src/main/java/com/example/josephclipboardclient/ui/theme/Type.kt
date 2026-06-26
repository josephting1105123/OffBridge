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
 * Geist (Vercel's product typeface) for all text — bundled as a variable font in res/font and the app
 * font everywhere, replacing the Roboto platform default so the UI reads as intentional rather than
 * stock-Android. Inter is also bundled (res/font/inter_variable) as a one-line swap if ever preferred.
 * Numeric/data fields additionally merge [TabularFigures] (tnum) so live values don't reflow as digits
 * change width. Mirrored on the Windows client (theme/Type.kt).
 */
val Geist = FontFamily(
    Font(R.font.geist_variable, FontWeight.Normal),
    Font(R.font.geist_variable, FontWeight.Medium),
    Font(R.font.geist_variable, FontWeight.SemiBold),
    Font(R.font.geist_variable, FontWeight.Bold),
)

/**
 * Type scale mirroring res/values/styles.xml (TextAppearance.App.*): Bold headings/titles, Medium
 * labels, Normal body. Starts from the M3 default scale (so every slot a screen might touch is covered)
 * and applies Geist across all 15 slots, then overrides the five app styles with their sizes/weights.
 */
private val Base = Typography()
val AppTypography = Base.copy(
    displayLarge = Base.displayLarge.copy(fontFamily = Geist),
    displayMedium = Base.displayMedium.copy(fontFamily = Geist),
    displaySmall = Base.displaySmall.copy(fontFamily = Geist),
    headlineLarge = Base.headlineLarge.copy(fontFamily = Geist),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Geist),
    headlineSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 24.sp),   // App.Headline
    titleLarge = Base.titleLarge.copy(fontFamily = Geist),
    titleMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 18.sp),     // App.Title
    titleSmall = Base.titleSmall.copy(fontFamily = Geist),
    bodyLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 15.sp),     // App.Body
    bodyMedium = Base.bodyMedium.copy(fontFamily = Geist),
    bodySmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 12.sp),     // App.Caption
    labelLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 14.sp),    // App.Label
    labelMedium = Base.labelMedium.copy(fontFamily = Geist),
    labelSmall = Base.labelSmall.copy(fontFamily = Geist),
)

/**
 * Tabular (monospaced) figures — apply to live numeric/data text (transfer %, MB, MB/s, ETA, timers,
 * the tutorial "i / N" counter) so values don't reflow as digits change width. Same Geist base.
 * Merge onto any base style: `style.merge(TabularFigures)`.
 */
val TabularFigures = TextStyle(fontFamily = Geist, fontFeatureSettings = "tnum")
