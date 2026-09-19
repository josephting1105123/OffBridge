package com.joseph.clipboard.windows.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.joseph.clipboard.windows.theme.Dimens
import com.joseph.clipboard.windows.theme.LocalExtraColors
import com.joseph.clipboard.windows.theme.Motion

/**
 * Press feedback: scale the surface to [Motion.pressScale] while the [interactionSource] is pressed,
 * springing back via the emphasized curve. Pass the same MutableInteractionSource you give to
 * `clickable(...)` so the scale tracks the real press.
 */
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.pressScale else 1f,
        animationSpec = tween(durationMillis = Motion.durMed, easing = Motion.emphasized),
        label = "pressScale",
    )
    this.scale(scale)
}

/**
 * The "glass" surface recipe: solid [fill] clipped to [shape] with a 1dp hairline border. This is the
 * load-bearing depth cue (no heavy Material drop shadows) — one call replaces ad-hoc
 * background + border + shadow across every card/row.
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    fill: Color = MaterialTheme.colors.surface,
): Modifier {
    val hairline = LocalExtraColors.current.hairline
    return this
        .clip(shape)
        .background(fill)
        .border(Dimens.hairlineWidth, hairline, shape)
}
