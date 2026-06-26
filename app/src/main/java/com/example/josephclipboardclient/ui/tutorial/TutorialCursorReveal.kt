package com.EntropicSoftwareLab.ClipboardShare.ui.tutorial

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/*
 * Steps 6-8: replay the bottom-docked "Send Via" sheet, animate a finger-tap pointer onto the relevant
 * tile (Connected Devices / QR / NFC) with a click ripple, then crossfade into the FULL-SCREEN replica
 * of that real destination. Used as a TutorialStep.fullScreenContent, so the overlay stepping logic is
 * untouched and the engine's tap-catcher still advances the tour.
 */
@Composable
internal fun TutorialRevealConnectedDevices() =
    SendViaReveal(SendViaTarget.CONNECTED_DEVICES) { TutorialReplicaConnectedDevices() }

@Composable
internal fun TutorialRevealQr() =
    SendViaReveal(SendViaTarget.QR) { TutorialReplicaQr() }

@Composable
internal fun TutorialRevealNfc() =
    SendViaReveal(SendViaTarget.NFC) { TutorialReplicaNfc() }

private enum class SendViaTarget { CONNECTED_DEVICES, QR, NFC }

@Composable
private fun SendViaReveal(
    target: SendViaTarget,
    reveal: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var revealed by remember { mutableStateOf(false) }
    var containerOrigin by remember { mutableStateOf<Offset?>(null) }
    var buttonCenter by remember { mutableStateOf<Offset?>(null) }
    val progress = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }
    val ripple = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        withTimeoutOrNull(1500) {
            snapshotFlow { containerOrigin != null && buttonCenter != null }.first { it }
        }
        delay(300)
        progress.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        delay(120)
        launch { ripple.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        press.animateTo(1f, tween(110, easing = FastOutSlowInEasing))
        press.animateTo(0f, tween(160))
        delay(260)
        revealed = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { containerOrigin = it.boundsInWindow().topLeft },
    ) {
        Crossfade(targetState = revealed, animationSpec = tween(280), modifier = Modifier.fillMaxSize()) { isRevealed ->
            if (isRevealed) {
                reveal()
            } else {
                // Send-Via sheet docked at the bottom (matching step 5), with the tapped tile probed.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    val probe = Modifier.onGloballyPositioned { buttonCenter = it.boundsInWindow().center }
                    TutorialPlaceholderSendVia(
                        connectedButtonModifier = if (target == SendViaTarget.CONNECTED_DEVICES) probe else Modifier,
                        qrButtonModifier = if (target == SendViaTarget.QR) probe else Modifier,
                        nfcButtonModifier = if (target == SendViaTarget.NFC) probe else Modifier,
                    )
                }
            }
        }

        val origin = containerOrigin
        val center = buttonCenter
        if (!revealed && origin != null && center != null) {
            val targetLocal = center - origin
            // The sheet sits at the bottom, so the finger reaches DOWN into the tile from above-right.
            val startLocal = Offset(
                targetLocal.x + with(density) { 28.dp.toPx() },
                targetLocal.y - with(density) { 84.dp.toPx() },
            )
            TutorialFingerPointer(
                tip = lerp(startLocal, targetLocal, progress.value),
                pressProgress = press.value,
                rippleProgress = ripple.value,
            )
        }
    }
}

/** White touch_app glyph + green halo + expanding click ripple, touch point landing on [tip] (local px). */
@Composable
private fun TutorialFingerPointer(
    tip: Offset,
    pressProgress: Float,
    rippleProgress: Float,
) {
    val green = AppThemeTokens.colors.green
    val glyphSize = 44.dp
    val glyphPx = with(LocalDensity.current) { glyphSize.toPx() }
    val tipFracX = 0.45f
    val tipFracY = 0.22f

    Box(
        modifier = Modifier.offset {
            IntOffset(
                (tip.x - glyphPx * tipFracX).roundToInt(),
                (tip.y - glyphPx * tipFracY).roundToInt(),
            )
        },
    ) {
        Canvas(modifier = Modifier.size(glyphSize)) {
            val center = Offset(size.width * tipFracX, size.height * tipFracY)
            drawCircle(green.copy(alpha = 0.22f), radius = size.minDimension * 0.30f, center = center)
            if (rippleProgress > 0f) {
                val rMin = 6.dp.toPx()
                val rMax = 26.dp.toPx()
                drawCircle(
                    color = green.copy(alpha = (1f - rippleProgress) * 0.7f),
                    radius = rMin + (rMax - rMin) * rippleProgress,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx()),
                )
            }
        }
        Icon(
            imageVector = Icons.Filled.TouchApp,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(glyphSize)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(tipFracX, tipFracY)
                    val s = 1f - 0.12f * pressProgress
                    scaleX = s
                    scaleY = s
                    translationY = 4.dp.toPx() * pressProgress
                },
        )
    }
}
