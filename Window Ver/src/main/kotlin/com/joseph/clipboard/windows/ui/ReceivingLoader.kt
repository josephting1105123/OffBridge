package com.joseph.clipboard.windows.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joseph.clipboard.windows.theme.Dimens
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

/*
 * Desktop port of the Android ReceivingLoader (ui/components/ReceivingLoader.kt): the same three
 * infinite animations (ring rotation 90→450 over 5000ms, letter stagger over 1800ms, liquid flow
 * over 4000ms) and the same fixed three-green palette, with every dp constant scaled by size/198
 * so the geometry tuned at the Android reference size reads the same at pill scale. The one
 * platform substitution is blur: Android's BlurMaskFilter doesn't exist on the desktop JVM, so
 * the Skia mask filter is set directly using the standard radius→sigma conversion
 * (sigma = radius·0.57735 + 0.5). At pill scale the staggered label sits BESIDE the ring rather
 * than inside it — a 28dp ring has no room for text.
 */

// Fixed palette shared verbatim with Android — deliberately not theme tokens; the loader stays
// the same three greens in light and dark.
private val RingGreenLight = Color(0xFF34D399)
private val RingGreenMid = Color(0xFF10B981)
private val RingGreenDeep = Color(0xFF047857)

/** The Android reference size all dp constants in the ring were tuned at. */
private const val REFERENCE_SIZE_DP = 198f

/**
 * Small global "Receiving…" chip: animated liquid ring + per-letter staggered label on a glass
 * pill. Shown while an incoming byte-pump is live; click jumps to the transfer session.
 */
@Composable
fun ReceivingPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .glassSurface(RoundedCornerShape(Dimens.radiusPill))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(),
            ) { onClick() }
            .padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReceivingRing(size = 28.dp)
        Spacer(Modifier.width(Dimens.spaceSm))
        StaggeredLabel(text)
    }
}

/**
 * The label, animated per letter with the Android stagger (opacity pulse + 5% enlargement).
 * `internal` so the Updates page reuses the same in-progress text treatment as the receive pill.
 * [style]/[color]/[fontWeight] default to the pill's look; the Updates page passes a larger style.
 */
@Composable
internal fun StaggeredLabel(
    text: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.body2,
    color: Color = MaterialTheme.colors.onSurface,
    fontWeight: FontWeight = FontWeight.SemiBold,
) {
    val transition = rememberInfiniteTransition()
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    Row {
        text.forEachIndexed { index, char ->
            val staggerOffset = (index * 0.1f) / 3.0f
            // Proper modulo, generalizing the Android original's single +1 wrap (which breaks past
            // ~30 letters): keeps letterProgress in [0,1) for any label length, so alpha can never
            // go negative on a long localized string.
            val letterProgress = ((progress - staggerOffset) % 1f + 1f) % 1f
            val maxEnlargement = 0.05f
            val opacity: Float
            val scaleFactor: Float
            when {
                letterProgress < 0.2f -> {
                    val t = letterProgress / 0.2f
                    opacity = 0.4f + 0.6f * t
                    scaleFactor = 1.0f + maxEnlargement * t
                }
                letterProgress < 0.4f -> {
                    val t = (letterProgress - 0.2f) / 0.2f
                    opacity = 1.0f - 0.3f * t
                    scaleFactor = 1.0f + maxEnlargement - maxEnlargement * t
                }
                else -> {
                    val t = (letterProgress - 0.4f) / 0.6f
                    opacity = 0.7f - 0.3f * t
                    scaleFactor = 1.0f
                }
            }
            Text(
                text = char.toString(),
                style = style,
                fontWeight = fontWeight,
                color = color,
                modifier = Modifier
                    .alpha(opacity)
                    .graphicsLayer {
                        scaleX = scaleFactor
                        scaleY = scaleFactor
                    },
            )
        }
    }
}

/**
 * The rotating liquid ring, a 1:1 port of the Android Canvas except for the Skia blur.
 * `internal` (not private) so the Updates page reuses the same loader for its checking/downloading
 * states — it takes only [size] and carries no receive-session data.
 */
@Composable
internal fun ReceivingRing(size: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val rotation by transition.animateFloat(
        initialValue = 90f,
        targetValue = 450f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    val flowTime by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )

    val s = size.value / REFERENCE_SIZE_DP

    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation },
    ) {
        val radius = size.toPx() / 2f
        val center = Offset(radius, radius)

        // Android BlurMaskFilter(radius, NORMAL) equivalent.
        fun blurred(radiusPx: Float): MaskFilter =
            MaskFilter.makeBlur(FilterBlurMode.NORMAL, radiusPx * 0.57735f + 0.5f)

        drawIntoCanvas { canvas ->
            fun drawOutset(blurRadiusDp: Float, spreadDp: Float, color: Color) {
                val strokeWidthPx = (spreadDp * s).dp.toPx().coerceAtLeast(2f)
                val paint = Paint().apply {
                    this.color = color
                    this.style = PaintingStyle.Stroke
                    this.strokeWidth = strokeWidthPx
                }
                paint.asFrameworkPaint().maskFilter = blurred((blurRadiusDp * s).dp.toPx().coerceAtLeast(1f))
                canvas.drawCircle(center, radius + strokeWidthPx / 2f, paint)
            }

            drawOutset(3f, 1.2f, RingGreenLight.copy(alpha = 0.27f))
            drawOutset(6f, 1.8f, RingGreenMid.copy(alpha = 0.18f))
        }

        val clip = Path().apply { addOval(Rect(0f, 0f, size.toPx(), size.toPx())) }
        clipPath(clip) {
            drawIntoCanvas { canvas ->
                fun drawLiquidInset(yOffsetDp: Float, blurRadiusDp: Float, color: Color, phase: Float, amplitudeDp: Float) {
                    val strokeWidthPx = (24f * s).dp.toPx()
                    val paint = Paint().apply {
                        this.color = color
                        this.style = PaintingStyle.Stroke
                        this.strokeWidth = strokeWidthPx
                    }
                    paint.asFrameworkPaint().maskFilter = blurred((blurRadiusDp * s).dp.toPx().coerceAtLeast(1f))

                    val drawRadius = radius + strokeWidthPx / 2f
                    val offsetCenter = Offset(center.x, center.y + (yOffsetDp * s).dp.toPx())

                    val path = Path()
                    val points = 60
                    val time = flowTime * Math.PI.toFloat() * 2f
                    for (i in 0..points) {
                        val angle = (i.toFloat() / points) * 2f * Math.PI.toFloat()
                        // Whole-number time multipliers only: each wave must complete an exact
                        // number of cycles so the loop is seamless when flowTime wraps 1 → 0.
                        val wave1 = kotlin.math.sin(angle * 3f + time + phase)
                        val wave2 = kotlin.math.cos(angle * 2f - time * 2f + phase)
                        val wave3 = kotlin.math.sin(angle * 5f + time)

                        val amplitudePx = (amplitudeDp * s).dp.toPx()
                        val r = drawRadius + amplitudePx * (wave1 + wave2 + wave3) / 3f

                        val x = offsetCenter.x + r * kotlin.math.cos(angle)
                        val y = offsetCenter.y + r * kotlin.math.sin(angle)

                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()

                    canvas.drawPath(path, paint)
                }

                // Liquid physics layers, deepest first.
                drawLiquidInset(36f, 36f, RingGreenDeep, phase = 0f, amplitudeDp = 15f)
                drawLiquidInset(12f, 18f, RingGreenMid, phase = 2f, amplitudeDp = 10f)
                drawLiquidInset(6f, 12f, RingGreenLight, phase = 4f, amplitudeDp = 5f)
            }
        }
    }
}
