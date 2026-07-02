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
 * Two-voice type system, mirrored on the Windows client (theme/Type.kt):
 *  - Content voice: IBM Plex Sans (headings, titles, body, captions) — humanist letterforms with
 *    Latin + Cyrillic coverage, bundled as a variable font in res/font.
 *  - Control voice: Geist (buttons, labels, chips — the label* slots) so controls keep their
 *    tighter, tracked look.
 * Both are variable fonts; Inter (res/font/inter_variable) stays bundled as a one-line swap if ever
 * preferred. Numeric/data fields additionally merge [TabularFigures] (tnum) so live values don't
 * reflow as digits change width.
 */
val Geist = FontFamily(
    Font(R.font.geist_variable, FontWeight.Normal),
    Font(R.font.geist_variable, FontWeight.Medium),
    Font(R.font.geist_variable, FontWeight.SemiBold),
    Font(R.font.geist_variable, FontWeight.Bold),
)

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_variable, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_variable, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_variable, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_variable, FontWeight.Bold),
)

/**
 * Type scale mirroring res/values/styles.xml (TextAppearance.App.*): Bold headings/titles, Medium
 * labels, Normal body. Starts from the M3 default scale (so every slot a screen might touch is covered),
 * puts Plex on the content slots (display/headline/title/body) and Geist on the control slots (label*,
 * which M3 buttons/chips draw from), then overrides the five app styles with their sizes/weights.
 */
private val Base = Typography()
val AppTypography = Base.copy(
    displayLarge = Base.displayLarge.copy(fontFamily = PlexSans),
    displayMedium = Base.displayMedium.copy(fontFamily = PlexSans),
    displaySmall = Base.displaySmall.copy(fontFamily = PlexSans),
    headlineLarge = Base.headlineLarge.copy(fontFamily = PlexSans),
    headlineMedium = Base.headlineMedium.copy(fontFamily = PlexSans),
    headlineSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Bold, fontSize = 24.sp), // App.Headline
    titleLarge = Base.titleLarge.copy(fontFamily = PlexSans),
    titleMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Bold, fontSize = 18.sp),   // App.Title
    titleSmall = Base.titleSmall.copy(fontFamily = PlexSans),
    bodyLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 15.sp),   // App.Body
    bodyMedium = Base.bodyMedium.copy(fontFamily = PlexSans),
    bodySmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 12.sp),   // App.Caption
    labelLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 14.sp),     // App.Label
    labelMedium = Base.labelMedium.copy(fontFamily = Geist),
    labelSmall = Base.labelSmall.copy(fontFamily = Geist),
)

/**
 * Tabular (monospaced) figures — apply to live numeric/data text (transfer %, MB, MB/s, ETA, timers,
 * the tutorial "i / N" counter) so values don't reflow as digits change width. Plex base to match the
 * content text these numbers sit in. Merge onto any base style: `style.merge(TabularFigures)`.
 */
val TabularFigures = TextStyle(fontFamily = PlexSans, fontFeatureSettings = "tnum")
