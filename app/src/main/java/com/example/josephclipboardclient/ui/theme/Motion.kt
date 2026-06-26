package com.EntropicSoftwareLab.ClipboardShare.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion tokens — one global rhythm so every animation shares the same feel.
 * The signature `emphasized` curve is the spring-like cubic-bezier(0.16, 1, 0.3, 1) used by
 * Linear/Vercel. Read via [AppThemeTokens.motion].
 *
 * [reduceMotion] is computed in [AppTheme] from Settings.Global.ANIMATOR_DURATION_SCALE (Android)
 * — when true, callers should use 0ms durations and skip stagger / press-scale / shimmer.
 */
@Immutable
data class Motion(
    val emphasized: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f),
    val standard: Easing = FastOutSlowInEasing,
    val durFast: Int = 120,        // exit / collapse (ms)
    val durMed: Int = 220,         // enter / expand / crossfade (ms)
    val durSlow: Int = 320,        // hero / large transition (ms)
    val pressScale: Float = 0.97f, // tappable card / button press
    val staggerStepMs: Int = 40,   // list / bento item entrance, cap ~8 items
    val reduceMotion: Boolean = false,
) {
    /** Duration helper that collapses to 0 when reduced motion is requested. */
    fun dur(value: Int): Int = if (reduceMotion) 0 else value
}

val LocalMotion = staticCompositionLocalOf { Motion() }
