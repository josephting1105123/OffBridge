package com.joseph.clipboard.windows.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * Skeleton-loading shimmer: an animated diagonal gradient brush swept across
 * the placeholder while real content mounts (Windows counterpart of the
 * Android Facebook Shimmer wrapper).
 */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition()
    val translate by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing)
        )
    )
    val base = MaterialTheme.colors.onSurface
    background(
        Brush.linearGradient(
            colors = listOf(
                base.copy(alpha = 0.08f),
                base.copy(alpha = 0.22f),
                base.copy(alpha = 0.08f)
            ),
            start = Offset(translate, 0f),
            end = Offset(translate + 400f, 200f)
        )
    )
}
