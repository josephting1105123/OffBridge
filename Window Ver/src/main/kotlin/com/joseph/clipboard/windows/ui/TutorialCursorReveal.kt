package com.joseph.clipboard.windows.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.joseph.clipboard.windows.theme.GreenPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/*
 * Steps 6-7: replay the centered "Send Via" sheet, animate a finger-tap pointer onto the tapped tile
 * (Connected Devices / QR), then crossfade into the centered full-screen replica of that real dialog.
 * Used as a TutorialStep.fullScreenContent. No NFC on desktop.
 */
@Composable
internal fun TutorialRevealConnectedDevices() =
    SendViaReveal(SendViaTarget.CONNECTED_DEVICES) { TutorialReplicaConnectedDevices() }

@Composable
internal fun TutorialRevealQr() =
    SendViaReveal(SendViaTarget.QR) { TutorialReplicaQr() }

/**
 * Step 13: replay the received history card AT ITS REAL LIST POSITION (translated from the live
 * [receivedCardWindowBounds] captured on the real card), animate a finger-tap onto it, then crossfade
 * into the full-area detail replica and spotlight + tap the per-file "Open" button. Used as a
 * TutorialStep.fullScreenContent; mirrors [SendViaReveal] but taps a real-positioned card in place.
 */
@Composable
internal fun TutorialRevealOpenFile(receivedCardWindowBounds: Rect? = null) {
    val density = LocalDensity.current
    var revealed by remember { mutableStateOf(false) }
    var containerOrigin by remember { mutableStateOf<Offset?>(null) }
    var openButtonRect by remember { mutableStateOf<Rect?>(null) }
    val progress = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }
    val ripple = remember { Animatable(0f) }
    val press2 = remember { Animatable(0f) }
    val ripple2 = remember { Animatable(0f) }

    // Real received-card rect translated into this overlay's local coordinates so the replica overlays
    // the dimmed real card exactly (same position step 12 spotlights), instead of floating centred.
    val cardLocal: Rect? = run {
        val o = containerOrigin
        val b = receivedCardWindowBounds
        if (o != null && b != null) Rect(b.left - o.x, b.top - o.y, b.right - o.x, b.bottom - o.y) else null
    }
    val cardCenter = cardLocal?.center

    LaunchedEffect(Unit) {
        withTimeoutOrNull(1500) {
            snapshotFlow { cardCenter != null }.first { it }
        }
        delay(300)
        progress.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        delay(120)
        launch { ripple.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        press.animateTo(1f, tween(110, easing = FastOutSlowInEasing))
        press.animateTo(0f, tween(160))
        delay(260)
        revealed = true
        // Phase 2: once the detail's Open button is positioned, pulse a finger-tap on it.
        withTimeoutOrNull(1500) {
            snapshotFlow { openButtonRect != null }.first { it }
        }
        delay(360)
        launch { ripple2.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        press2.animateTo(1f, tween(110, easing = FastOutSlowInEasing))
        press2.animateTo(0f, tween(160))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { containerOrigin = it.boundsInWindow().topLeft }
    ) {
        Crossfade(targetState = revealed, animationSpec = tween(280), modifier = Modifier.fillMaxSize()) { isRevealed ->
            if (isRevealed) {
                TutorialReplicaOpenFileDetail(
                    openButtonModifier = Modifier.onGloballyPositioned {
                        val o = containerOrigin
                        if (o != null) {
                            val w = it.boundsInWindow()
                            openButtonRect = Rect(w.left - o.x, w.top - o.y, w.right - o.x, w.bottom - o.y)
                        }
                    }
                )
            } else if (cardLocal != null) {
                TutorialReplicaReceivedCard(
                    modifier = Modifier
                        .offset { IntOffset(cardLocal.left.roundToInt(), cardLocal.top.roundToInt()) }
                        .width(with(density) { cardLocal.width.toDp() })
                )
            } else {
                // Fallback when the real card bounds aren't available yet: centred fixed-width card.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TutorialReplicaReceivedCard(modifier = Modifier.width(420.dp))
                }
            }
        }

        // Phase 1 finger: glide onto the received card and tap.
        if (!revealed && cardCenter != null) {
            val startLocal = Offset(
                cardCenter.x + with(density) { 44.dp.toPx() },
                cardCenter.y + with(density) { 92.dp.toPx() }
            )
            TutorialFingerPointer(
                tip = lerp(startLocal, cardCenter, progress.value),
                pressProgress = press.value,
                rippleProgress = ripple.value
            )
        }

        // Phase 2: dim the detail and punch a hole around the "Open" button, then tap it.
        val obr = openButtonRect
        if (revealed && obr != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            ) {
                drawRect(Color.Black.copy(alpha = 0.6f))
                val pad = 8.dp.toPx()
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(obr.left - pad, obr.top - pad),
                    size = Size(obr.width + pad * 2, obr.height + pad * 2),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    blendMode = BlendMode.Clear
                )
            }
            TutorialFingerPointer(
                tip = obr.center,
                pressProgress = press2.value,
                rippleProgress = ripple2.value
            )
        }
    }
}

private enum class SendViaTarget { CONNECTED_DEVICES, QR }

@Composable
private fun SendViaReveal(
    target: SendViaTarget,
    reveal: @Composable () -> Unit
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
            .onGloballyPositioned { containerOrigin = it.boundsInWindow().topLeft }
    ) {
        Crossfade(targetState = revealed, animationSpec = tween(280), modifier = Modifier.fillMaxSize()) { isRevealed ->
            if (isRevealed) {
                reveal()
            } else {
                // Same centered card as the static step 5, so the Send-Via sheet sits in the
                // real dialog position before the finger-tap reveal animation plays.
                val probe = Modifier.onGloballyPositioned { buttonCenter = it.boundsInWindow().center }
                TutorialReplicaSendVia(
                    qrButtonModifier = if (target == SendViaTarget.QR) probe else Modifier,
                    connectedButtonModifier = if (target == SendViaTarget.CONNECTED_DEVICES) probe else Modifier
                )
            }
        }

        val origin = containerOrigin
        val center = buttonCenter
        if (!revealed && origin != null && center != null) {
            val targetLocal = center - origin
            val startLocal = Offset(
                targetLocal.x + with(density) { 44.dp.toPx() },
                targetLocal.y + with(density) { 92.dp.toPx() }
            )
            TutorialFingerPointer(
                tip = lerp(startLocal, targetLocal, progress.value),
                pressProgress = press.value,
                rippleProgress = ripple.value
            )
        }
    }
}

/** White touch_app glyph + green halo + expanding click ripple, touch point landing on [tip] (local px). */
@Composable
private fun TutorialFingerPointer(
    tip: Offset,
    pressProgress: Float,
    rippleProgress: Float
) {
    val glyphSize = 44.dp
    val glyphPx = with(LocalDensity.current) { glyphSize.toPx() }
    val tipFracX = 0.45f
    val tipFracY = 0.22f

    Box(
        modifier = Modifier.offset {
            IntOffset(
                (tip.x - glyphPx * tipFracX).roundToInt(),
                (tip.y - glyphPx * tipFracY).roundToInt()
            )
        }
    ) {
        Canvas(modifier = Modifier.size(glyphSize)) {
            val center = Offset(size.width * tipFracX, size.height * tipFracY)
            drawCircle(GreenPrimary.copy(alpha = 0.22f), radius = size.minDimension * 0.30f, center = center)
            if (rippleProgress > 0f) {
                val rMin = 6.dp.toPx()
                val rMax = 26.dp.toPx()
                drawCircle(
                    color = GreenPrimary.copy(alpha = (1f - rippleProgress) * 0.7f),
                    radius = rMin + (rMax - rMin) * rippleProgress,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx())
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
                }
        )
    }
}
