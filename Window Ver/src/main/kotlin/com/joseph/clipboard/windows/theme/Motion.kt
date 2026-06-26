package com.joseph.clipboard.windows.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * Motion tokens mirroring the Android client (Linear/Vercel spring feel). The signature
 * [emphasized] curve is used for enter/emphasis; [standard] for general transitions. Durations are in
 * milliseconds. [pressScale] is the tappable-surface press feedback. Desktop has no OS reduced-motion
 * flag, so motion is always on here (unlike Android, which gates on ANIMATOR_DURATION_SCALE).
 */
object Motion {
    val emphasized: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val standard: Easing = FastOutSlowInEasing
    const val durFast = 120
    const val durMed = 220
    const val durSlow = 320
    const val pressScale = 0.97f
    const val staggerStepMs = 40
}
