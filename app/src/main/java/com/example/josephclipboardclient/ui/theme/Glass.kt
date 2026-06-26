package com.EntropicSoftwareLab.ClipboardShare.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glass surface tokens — the signature card recipe is: solid [surface] + [verticalTint] gradient
 * overlay + a 1dp [hairline] border, with an OPTIONAL background-layer blur of [blurRadius]
 * (Android 12+; flat fallback below). Read via [AppThemeTokens.glass].
 */
@Immutable
data class GlassTokens(
    val surface: Color,
    val surfaceElevated: Color,
    val hairline: Color,
    val tintTop: Color,
    val tintBottom: Color,
    val blurRadius: Dp = 24.dp,
    /** Gate for real blur — off on API < 31 / low-end; the tint + hairline fallback always renders. */
    val enableBlur: Boolean = false,
) {
    /** Top-down sheen overlay laid over [surface] to fake depth without a blur pass. */
    fun verticalTint(): Brush = Brush.verticalGradient(listOf(tintTop, tintBottom))
}

val LightGlass = GlassTokens(
    surface = LightCard,
    surfaceElevated = LightSurfaceElevated,
    hairline = LightHairline,
    tintTop = Color(0x14FFFFFF),   // white @ 8%
    tintBottom = Color(0x00FFFFFF),
)

val DarkGlass = GlassTokens(
    surface = DarkCard,
    surfaceElevated = DarkSurfaceElevated,
    hairline = DarkHairline,
    tintTop = Color(0x0FFFFFFF),   // white @ 6%
    tintBottom = Color(0x05FFFFFF), // white @ 2%
)

val LocalGlass = staticCompositionLocalOf { LightGlass }
