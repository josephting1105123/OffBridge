package com.joseph.clipboard.windows.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

private val DarkColorPalette = darkColors(
    primary = GreenPrimary,
    primaryVariant = GreenPrimary,
    secondary = GreenPrimary,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = DarkBackground,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark
)

private val LightColorPalette = lightColors(
    primary = GreenPrimaryLight,
    primaryVariant = GreenPrimaryLight,
    secondary = GreenPrimaryLight,
    background = LightBackground,
    surface = LightSurface,
    onPrimary = OnGreen,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight
)

@Composable
fun ClipboardTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }

    val colors = if (isDark) DarkColorPalette else LightColorPalette
    val extra = if (isDark) DarkExtraColors else LightExtraColors

    MaterialTheme(
        colors = colors,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(LocalExtraColors provides extra, content = content)
    }
}
