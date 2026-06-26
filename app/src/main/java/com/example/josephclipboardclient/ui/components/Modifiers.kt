package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/**
 * Subtle press-scale (0.97 → 1.0) for tappable cards/buttons. Driven by the shared [pressScale]
 * motion token and disabled under reduced motion. Pass the same [interactionSource] you give the
 * clickable/Button so press is detected once.
 */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier {
    val motion = AppThemeTokens.motion
    val pressed by interactionSource.collectIsPressedAsState()
    val target = if (pressed && !motion.reduceMotion) motion.pressScale else 1f
    val scale by animateFloatAsState(target, label = "pressScale")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * The signature glass surface: solid surface + a top-down tint sheen + a 1dp hairline border on
 * [shape]. The flat tint + hairline is the load-bearing look; real blur (Android 12+) is layered
 * separately by callers that want it.
 */
@Composable
fun Modifier.glassSurface(shape: Shape): Modifier {
    val glass = AppThemeTokens.glass
    val dimens = AppThemeTokens.dimens
    return this
        .clip(shape)
        .background(glass.surface)
        .background(glass.verticalTint())
        .border(dimens.hairlineWidth, glass.hairline, shape)
}
