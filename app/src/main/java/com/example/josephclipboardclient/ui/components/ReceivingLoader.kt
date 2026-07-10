package com.EntropicSoftwareLab.ClipboardShare.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.sp
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

@Composable
fun ReceivingLoader(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 198.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ReceivingLoader")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 90f,
        targetValue = 450f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "text_progress"
    )

        val flowTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flow_time"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(size)
                .graphicsLayer { rotationZ = rotation }
        ) {
            val radius = size.toPx() / 2f
            val center = Offset(radius, radius)
            
            // Green equivalent of original blue palette
            val color1 = Color(0xFF34D399) // Light green (inner)
            val color2 = Color(0xFF10B981) // Mid green
            val color3 = Color(0xFF047857) // Dark green (deepest)

            drawIntoCanvas { canvas ->
                fun drawOutset(blurRadiusDp: Float, spreadDp: Float, color: Color) {
                    val strokeWidthPx = spreadDp.dp.toPx().coerceAtLeast(2f)
                    val paint = Paint().apply {
                        this.color = color
                        this.style = PaintingStyle.Stroke
                        this.strokeWidth = strokeWidthPx
                    }
                    paint.asFrameworkPaint().maskFilter = BlurMaskFilter(blurRadiusDp.dp.toPx().coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
                    val drawRadius = radius + (strokeWidthPx / 2f)
                    canvas.drawCircle(center, drawRadius, paint)
                }

                // 0 0 3px 1.2px rgba(...)
                drawOutset(3f, 1.2f, color1.copy(alpha = 0.27f))
                // 0 0 6px 1.8px rgba(...)
                drawOutset(6f, 1.8f, color2.copy(alpha = 0.18f))
            }

            val clipPath = Path().apply {
                addOval(Rect(0f, 0f, size.toPx(), size.toPx()))
            }

            clipPath(clipPath) {
                drawIntoCanvas { canvas ->
                    fun drawLiquidInset(yOffsetDp: Float, blurRadiusDp: Float, color: Color, phase: Float, amplitudeDp: Float) {
                        val strokeWidthPx = 24.dp.toPx()
                        val paint = Paint().apply {
                            this.color = color
                            this.style = PaintingStyle.Stroke
                            this.strokeWidth = strokeWidthPx
                        }
                        paint.asFrameworkPaint().maskFilter = BlurMaskFilter(blurRadiusDp.dp.toPx().coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
                        
                        val drawRadius = radius + (strokeWidthPx / 2f)
                        val offsetCenter = Offset(center.x, center.y + yOffsetDp.dp.toPx())
                        
                        val path = Path()
                        val points = 60
                        val time = flowTime * Math.PI.toFloat() * 2f
                        for (i in 0..points) {
                            val angle = (i.toFloat() / points) * 2f * Math.PI.toFloat()
                            // To ensure a seamless loop when flowTime jumps from 1f to 0f (time jumps from 2PI to 0),
                            // the multiplier for 'time' MUST be a whole number (integer). 
                            // This ensures the wave completes an exact number of cycles (2PI multiples).
                            val wave1 = kotlin.math.sin(angle * 3f + time + phase) // 1x time = 1 cycle
                            val wave2 = kotlin.math.cos(angle * 2f - time * 2f + phase) // 2x time = 2 cycles
                            val wave3 = kotlin.math.sin(angle * 5f + time * 1f) // 1x time = 1 cycle
                            
                            val amplitudePx = amplitudeDp.dp.toPx()
                            val r = drawRadius + amplitudePx * (wave1 + wave2 + wave3) / 3f
                            
                            val x = offsetCenter.x + r * kotlin.math.cos(angle)
                            val y = offsetCenter.y + r * kotlin.math.sin(angle)
                            
                            if (i == 0) {
                                path.moveTo(x, y)
                            } else {
                                path.lineTo(x, y)
                            }
                        }
                        path.close()
                        
                        canvas.drawPath(path, paint)
                    }

                    // Liquid physics physics layers
                    drawLiquidInset(36f, 36f, color3, phase = 0f, amplitudeDp = 15f)
                    drawLiquidInset(12f, 18f, color2, phase = 2f, amplitudeDp = 10f)
                    drawLiquidInset(6f, 12f, color1, phase = 4f, amplitudeDp = 5f)
                }
            }
        }

        Row(modifier = Modifier.graphicsLayer {
            scaleX = 0.77f
            scaleY = 0.77f
        }) {
            text.forEachIndexed { index, char ->
                val staggerOffset = (index * 0.1f) / 3.0f
                val letterProgress = (progress - staggerOffset).let { if (it < 0f) it + 1f else it }
                
                val opacity: Float
                val scaleFactor: Float
                val baseScale = 1.0f // Reverted, we will scale the whole Row instead
                val maxEnlargement = 0.05f // Reduced enlargement from 0.15 to 0.05 (10% less)
                
                when {
                    letterProgress < 0.2f -> {
                        val t = letterProgress / 0.2f
                        opacity = 0.4f + 0.6f * t
                        scaleFactor = baseScale * (1.0f + maxEnlargement * t)
                    }
                    letterProgress < 0.4f -> {
                        val t = (letterProgress - 0.2f) / 0.2f
                        opacity = 1.0f - 0.3f * t
                        scaleFactor = baseScale * (1.0f + maxEnlargement - maxEnlargement * t)
                    }
                    else -> {
                        val t = (letterProgress - 0.4f) / 0.6f
                        opacity = 0.7f - 0.3f * t
                        scaleFactor = baseScale
                    }
                }
                
                Text(
                    text = char.toString(),
                    color = AppThemeTokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold, // Reduced thickness
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    letterSpacing = (-2.5).sp, // Gap heavily reduced
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .alpha(opacity)
                        .graphicsLayer {
                            scaleX = scaleFactor
                            scaleY = scaleFactor
                        }
                )
            }
        }
    }
}
