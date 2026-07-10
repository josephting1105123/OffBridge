package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.hypot

fun Modifier.circularReveal(
    revealX: Float,
    revealY: Float,
    durationMillis: Int = 400
): Modifier = composed {
    // If coordinates are invalid or zero, don't animate to avoid weird glitches
    if (revealX <= 0f && revealY <= 0f) return@composed this

    val progress = remember { Animatable(0f) }

    LaunchedEffect(revealX, revealY) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)
        )
    }

    this.drawWithContent {
        val maxRadius = hypot(size.width, size.height)
        val radius = maxRadius * progress.value

        val clipPath = Path().apply {
            addOval(
                androidx.compose.ui.geometry.Rect(
                    center = Offset(revealX, revealY),
                    radius = radius
                )
            )
        }
        clipPath(clipPath) {
            this@drawWithContent.drawContent()
        }
    }
}
