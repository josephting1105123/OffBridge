package com.EntropicSoftwareLab.ClipboardShare.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Emerald accent on a neutral near-black grey (dark) / light-grey (light) base. Mirrors the values /
 * values-night themes.xml custom attrs. Light and dark are co-equal — both tuned to WCAG 4.5:1.
 * See ui/theme/TOKENS.md (source of truth).
 *
 * Green discipline (unchanged): `green` is a FILL (place white/dark text ON it); `greenText` is the
 * WCAG-safe green for text / links / small icons.
 */

// ── Light palette (original emerald accent) ─────────────────────────────────
val LightBackground = Color(0xFFF8FAFC)
val LightCard = Color(0xFFFFFFFF)
val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF475569)
val LightDivider = Color(0xFFE2E8F0)
val LightNavBar = Color(0xFFFFFFFF)
val LightNavIcon = Color(0xFF475569)
val LightBottomSheet = Color(0xFFFFFFFF)
val LightGreen = Color(0xFF34D399)             // original brand emerald (light fill)
val LightGreenText = Color(0xFF047857)         // WCAG-safe green for text on white (>=4.5:1)
val LightSuccess = Color(0xFF047857)
val LightError = Color(0xFFDC2626)
val LightWarning = Color(0xFFB45309)
val LightInfo = Color(0xFF1D4ED8)
// Glass / depth tokens
val LightSurfaceElevated = Color(0xFFF8FAFC)
val LightScrim = Color(0x66000000)             // black @ 40%
val LightHairline = Color(0x0F0F172A)          // slate-900 @ 6%
val LightGradientTop = Color(0xFFFFFFFF)
val LightGradientBottom = Color(0xFFF1F5F9)
val LightAccentMuted = Color(0x1A34D399)       // green @ 10%

// ── Dark palette (neutral near-black grey, not blue) ────────────────────────
val DarkBackground = Color(0xFF141414)         // near-black grey
val DarkCard = Color(0xFF1F1F1F)
val DarkTextPrimary = Color(0xFFF5F5F5)
val DarkTextSecondary = Color(0xFFA3A3A3)      // neutral grey
val DarkDivider = Color(0xFF303030)
val DarkNavBar = Color(0xFF171717)
val DarkNavIcon = Color(0xFFA3A3A3)
val DarkBottomSheet = Color(0xFF1F1F1F)
val DarkGreen = Color(0xFF22C55E)              // brand fill (dark)
val DarkGreenText = Color(0xFF4ADE88)          // WCAG-safe green for text on card (>=4.5:1)
val DarkSuccess = Color(0xFF34D399)
val DarkError = Color(0xFFEF4444)
val DarkWarning = Color(0xFFFBBF24)
val DarkInfo = Color(0xFF60A5FA)
// Glass / depth tokens
val DarkSurfaceElevated = Color(0xFF2A2A2A)
val DarkScrim = Color(0xB8000000)              // black @ 72%
val DarkHairline = Color(0x14FFFFFF)           // white @ 8%
val DarkGradientTop = Color(0xFF1A1A1A)
val DarkGradientBottom = Color(0xFF0D0D0D)
val DarkAccentMuted = Color(0x2422C55E)        // green @ 14%
