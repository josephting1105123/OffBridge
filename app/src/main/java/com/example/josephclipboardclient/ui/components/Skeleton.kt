package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/** Pulsing skeleton placeholder for loading content (replaces the facebook-shimmer dependency). */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val animatedAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    // Respect reduced motion: hold a static mid alpha instead of pulsing.
    val alpha = if (AppThemeTokens.motion.reduceMotion) 0.5f else animatedAlpha
    Box(
        modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(AppThemeTokens.colors.divider.copy(alpha = alpha)),
    )
}
