package com.EntropicSoftwareLab.ClipboardShare.ui.theme

import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview

// Map what fits onto the M3 ColorScheme so stock M3 components (Button, Checkbox, sheets) inherit the
// brand without per-call tinting. App-specific slots live in AppColors / LocalAppColors.
private val LightColorScheme = lightColorScheme(
    primary = LightGreen,
    onPrimary = Color(0xFF06281C),
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightCard,
    onSurface = LightTextPrimary,
    error = LightError,
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkGreen,
    onPrimary = Color(0xFF06281C),
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkCard,
    onSurface = DarkTextPrimary,
    error = DarkError,
    onError = Color(0xFF3A0A0A),
)

/**
 * App-wide Compose theme. Defaults to [isSystemInDarkTheme] so it stays in lockstep with the XML
 * side (AppCompatDelegate forces the configuration, so both honour the same day/night state).
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val glass = if (darkTheme) DarkGlass else LightGlass

    // Honour the system "remove animations" / developer animator-scale=0 setting globally.
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    // Real blur is cheap only on Android 12+ (RenderEffect-backed Modifier.blur); flat fallback below.
    val enableBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    CompositionLocalProvider(
        LocalAppColors provides appColors,
        LocalDimens provides Dimens(),
        LocalMotion provides Motion(reduceMotion = reduceMotion),
        LocalElevation provides Elevation(),
        LocalGlass provides glass.copy(enableBlur = enableBlur),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/**
 * Ergonomic accessors: `AppThemeTokens.colors.card`, `AppThemeTokens.dimens.spaceLg`,
 * `AppThemeTokens.motion.emphasized`, `AppThemeTokens.elevation.level2`, `AppThemeTokens.glass`.
 */
object AppThemeTokens {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val dimens: Dimens
        @Composable @ReadOnlyComposable get() = LocalDimens.current
    val motion: Motion
        @Composable @ReadOnlyComposable get() = LocalMotion.current
    val elevation: Elevation
        @Composable @ReadOnlyComposable get() = LocalElevation.current
    val glass: GlassTokens
        @Composable @ReadOnlyComposable get() = LocalGlass.current
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppThemePreview() {
    AppTheme {
        Surface(color = AppThemeTokens.colors.background) {
            Column(Modifier.padding(AppThemeTokens.dimens.spaceLg)) {
                Text(
                    text = "OffBridge",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppThemeTokens.colors.textPrimary,
                )
                Spacer(Modifier.height(AppThemeTokens.dimens.spaceSm))
                Text(
                    text = "Connected · Encrypted",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppThemeTokens.colors.greenText,
                )
                Spacer(Modifier.height(AppThemeTokens.dimens.spaceMd))
                Button(onClick = {}) { Text("Send files") }
            }
        }
    }
}
