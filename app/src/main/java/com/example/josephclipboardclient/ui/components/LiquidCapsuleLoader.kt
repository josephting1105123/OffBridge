package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import kotlin.math.PI
import kotlin.math.sin

// The liquid keeps one fixed palette across light/dark themes so the animation reads the same on
// both; only the capsule track and hairline derive from the theme's primary-text colour. Ported
// from the Windows client's LiquidCapsuleLoader (emerald body with a warm gloss).
private val LiquidTop = Color(0xFF6EE7B7)
private val LiquidMid = Color(0xFF34D399)
private val LiquidDeep = Color(0xFF047857)
private val WarmSheen = Color(0xFFF0B7A0)   // coral sheen just under the surface
private val BottomEdge = Color(0xFF047857)  // deep-emerald base line

/**
 * A horizontal capsule holding emerald "liquid" whose surface sloshes as two travelling sine waves.
 * The always-on hero of the Updates screen. Pure Canvas, no assets or new dependencies. Whole-integer
 * time multipliers keep the loop seamless when the driver wraps 1→0.
 */
@Composable
fun LiquidCapsuleLoader(
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    height: Dp = 44.dp,
) {
    val transition = rememberInfiniteTransition(label = "liquidCapsule")
    val animated by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "flow",
    )
    // Honour the system "remove animations" setting (Developer options / accessibility) that the
    // rest of the app respects via AppThemeTokens.motion: freeze the wave at a representative phase
    // instead of the perpetual slosh. Not reading `animated` also stops the Canvas repainting.
    val flow = if (AppThemeTokens.motion.reduceMotion) 0.25f else animated

    val trackColor = AppThemeTokens.colors.textPrimary.copy(alpha = 0.05f)
    val hairline = AppThemeTokens.colors.textPrimary.copy(alpha = 0.14f)

    Canvas(modifier = modifier.size(width, height)) {
        val w = size.width
        val h = size.height
        val r = h / 2f

        val capsule = Path().apply {
            addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r, r)))
        }

        clipPath(capsule) {
            // Empty track behind the liquid, so the capsule shape reads where the wave dips.
            drawRect(color = trackColor)

            // Liquid surface: two sine waves at different speeds morph a single left hump into a
            // twin-hump / centre-valley slosh.
            val rest = h * 0.30f
            val amp = h * 0.22f
            val time = flow * (2f * PI.toFloat())
            val liquid = Path().apply {
                moveTo(0f, h)
                val steps = 48
                for (i in 0..steps) {
                    val px = w * i / steps
                    val u = px / w
                    val surf = rest +
                        amp * 0.62f * sin(u * 2f * PI.toFloat() * 1.5f + time) +
                        amp * 0.42f * sin(u * 2f * PI.toFloat() * 1.0f - time * 2f)
                    lineTo(px, surf)
                }
                lineTo(w, h)
                close()
            }

            drawPath(
                path = liquid,
                brush = Brush.verticalGradient(
                    0f to LiquidTop,
                    0.5f to LiquidMid,
                    1f to LiquidDeep,
                    startY = 0f,
                    endY = h,
                ),
            )

            // Warm sheen just under the surface (only where liquid is), for the glossy meniscus.
            clipPath(liquid) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to WarmSheen.copy(alpha = 0.28f),
                        1f to Color.Transparent,
                        startY = rest - amp,
                        endY = rest + amp,
                    ),
                )
            }

            // Thin base edge along the bottom of the liquid.
            drawRect(
                color = BottomEdge.copy(alpha = 0.6f),
                topLeft = Offset(0f, h - 3f),
                size = Size(w, 3f),
            )
        }

        // Capsule hairline.
        drawRoundRect(
            color = hairline,
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = 1f),
        )
    }
}
