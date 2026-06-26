package com.EntropicSoftwareLab.ClipboardShare.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * App colour tokens that Material 3's [androidx.compose.material3.ColorScheme] doesn't model
 * (card / divider / navbar / bottom-sheet / secondary text / brand green / semantic states).
 * Provided via [LocalAppColors] in [AppTheme]; read through [AppThemeTokens.colors].
 */
@Immutable
data class AppColors(
    val background: Color,
    val card: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val navBar: Color,
    val navIcon: Color,
    val bottomSheet: Color,
    val green: Color,        // brand fill
    val greenText: Color,    // WCAG-safe green for text / small icons
    val success: Color,
    val error: Color,
    val warning: Color,
    val info: Color,
    // New glass / depth tokens (appended — keep at the end so positional construction stays stable).
    val surfaceElevated: Color, // bento tile / raised card (one step above `card`)
    val scrim: Color,           // modal / sheet dim
    val hairline: Color,        // 1dp glass border (white@8% dark / slate@6% light)
    val gradientTop: Color,     // root surface gradient stop A (avoid pure black)
    val gradientBottom: Color,  // root surface gradient stop B
    val accentMuted: Color,     // green @ low alpha for chip / halo backgrounds
)

val LightAppColors = AppColors(
    background = LightBackground,
    card = LightCard,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    divider = LightDivider,
    navBar = LightNavBar,
    navIcon = LightNavIcon,
    bottomSheet = LightBottomSheet,
    green = LightGreen,
    greenText = LightGreenText,
    success = LightSuccess,
    error = LightError,
    warning = LightWarning,
    info = LightInfo,
    surfaceElevated = LightSurfaceElevated,
    scrim = LightScrim,
    hairline = LightHairline,
    gradientTop = LightGradientTop,
    gradientBottom = LightGradientBottom,
    accentMuted = LightAccentMuted,
)

val DarkAppColors = AppColors(
    background = DarkBackground,
    card = DarkCard,
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextSecondary,
    divider = DarkDivider,
    navBar = DarkNavBar,
    navIcon = DarkNavIcon,
    bottomSheet = DarkBottomSheet,
    green = DarkGreen,
    greenText = DarkGreenText,
    success = DarkSuccess,
    error = DarkError,
    warning = DarkWarning,
    info = DarkInfo,
    surfaceElevated = DarkSurfaceElevated,
    scrim = DarkScrim,
    hairline = DarkHairline,
    gradientTop = DarkGradientTop,
    gradientBottom = DarkGradientBottom,
    accentMuted = DarkAccentMuted,
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
