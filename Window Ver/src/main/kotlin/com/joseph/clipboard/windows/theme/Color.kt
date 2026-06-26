package com.joseph.clipboard.windows.theme

import androidx.compose.ui.graphics.Color

/*
 * Emerald accent on a neutral near-black grey (dark) / light-grey (light) base — mirrors the Android
 * client (see app ui/theme/TOKENS.md). Val names are kept stable so existing screens recolour with no
 * other edits. Material 2 is retained here; the colours are the overhaul.
 */
val GreenPrimary = Color(0xFF22C55E)        // brand fill / accent (dark)
val GreenPrimaryLight = Color(0xFF34D399)   // original emerald for light mode
val OnGreen = Color(0xFF06281C)             // dark label on a green fill
val DarkBackground = Color(0xFF141414)      // near-black grey
val DarkSurface = Color(0xFF1F1F1F)
val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val TextPrimaryDark = Color(0xFFF5F5F5)
val TextSecondaryDark = Color(0xFFA3A3A3)   // neutral grey
val TextPrimaryLight = Color(0xFF0F172A)
val TextSecondaryLight = Color(0xFF475569)

// Semantic + glass tokens — replace hardcoded literals in screens/dialogs/overlays.
val ScrimColor = Color(0xB8000000)          // black @ 72%
val SurfaceElevatedDark = Color(0xFF2A2A2A)
val HairlineDark = Color(0x14FFFFFF)        // white @ 8%
val HairlineLight = Color(0x0F0F172A)       // slate-900 @ 6%
val GreenText = Color(0xFF4ADE88)           // WCAG-safe green text on dark surfaces
val SuccessColor = Color(0xFF34D399)
val InfoColor = Color(0xFF60A5FA)
val WarningColor = Color(0xFFFBBF24)
val ErrorColor = Color(0xFFEF4444)
val ErrorMuted = Color(0xFF3A1F22)          // destructive button fill (dark overlay)
val ErrorText = Color(0xFFF87171)           // destructive button label

// Light-mode counterparts + extra glass tokens. These pair with the dark tokens above and are
// resolved per-theme through [ExtraColors] / [LocalExtraColors] so the glass recipe (hairline +
// accent + dividers) looks intentional in BOTH light and dark, mirroring the Android client.
val SurfaceElevatedLight = Color(0xFFF1F5F9) // slate-100, one step above the white card
val DividerDark = Color(0xFF303030)
val DividerLight = Color(0xFFE2E8F0)         // slate-200
val AccentMutedDark = Color(0x2422C55E)      // green @ ~14% (chips, tonal buttons)
val AccentMutedLight = Color(0x1A34D399)     // light green @ ~10%
val ScrimLight = Color(0x660F172A)           // slate-900 @ 40%
val GreenTextLight = Color(0xFF047857)       // WCAG-safe green text on light surfaces (emerald-700)
val SuccessColorLight = Color(0xFF15803D)
val InfoColorLight = Color(0xFF1D4ED8)
val WarningColorLight = Color(0xFFB45309)
val ErrorColorLight = Color(0xFFDC2626)
val ErrorMutedLight = Color(0xFFFDECEC)      // destructive button fill (light)
val ErrorTextLight = Color(0xFFDC2626)       // destructive button label (light)
