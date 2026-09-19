package com.joseph.clipboard.windows.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Theme-resolved "glass" tokens that Material 2's [androidx.compose.material.Colors] has no slot for
 * (hairline borders, muted accent, dividers, scrim, semantic states). Mirrors the Android client's
 * AppColors. Provided per-theme by [ClipboardTheme] via [LocalExtraColors]; components read it through
 * [appExtra] so a single switch flips the whole glass language between light and dark.
 */
@Immutable
data class ExtraColors(
    val green: Color,          // brand fill for the active theme
    val greenText: Color,      // WCAG-safe green for text/icons
    val textSecondary: Color,
    val divider: Color,
    val hairline: Color,       // 1dp glass border
    val accentMuted: Color,    // low-alpha green (chips, tonal buttons)
    val surfaceElevated: Color,
    val scrim: Color,
    val success: Color,
    val info: Color,
    val warning: Color,
    val error: Color,
    val errorMuted: Color,     // destructive button fill
    val errorText: Color,      // destructive button label
)

val DarkExtraColors = ExtraColors(
    green = GreenPrimary,
    greenText = GreenText,
    textSecondary = TextSecondaryDark,
    divider = DividerDark,
    hairline = HairlineDark,
    accentMuted = AccentMutedDark,
    surfaceElevated = SurfaceElevatedDark,
    scrim = ScrimColor,
    success = SuccessColor,
    info = InfoColor,
    warning = WarningColor,
    error = ErrorColor,
    errorMuted = ErrorMuted,
    errorText = ErrorText,
)

val LightExtraColors = ExtraColors(
    green = GreenPrimaryLight,
    greenText = GreenTextLight,
    textSecondary = TextSecondaryLight,
    divider = DividerLight,
    hairline = HairlineLight,
    accentMuted = AccentMutedLight,
    surfaceElevated = SurfaceElevatedLight,
    scrim = ScrimLight,
    success = SuccessColorLight,
    info = InfoColorLight,
    warning = WarningColorLight,
    error = ErrorColorLight,
    errorMuted = ErrorMutedLight,
    errorText = ErrorTextLight,
)

/** Defaults to dark; [ClipboardTheme] overrides per resolved theme. */
val LocalExtraColors = staticCompositionLocalOf { DarkExtraColors }

/** Shorthand: `appExtra().hairline` etc. inside composables. */
val appExtra: ExtraColors
    @Composable @ReadOnlyComposable get() = LocalExtraColors.current
