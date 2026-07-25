package com.joseph.clipboard.windows.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.joseph.clipboard.windows.storage.TranslationManager
import com.joseph.clipboard.windows.theme.GreenPrimary
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private fun tr(key: String, fallback: String): String =
    TranslationManager.getString(key).takeIf { it != key } ?: fallback

/**
 * A single tutorial step definition. Ports the Android TutorialStep
 * (TutorialOverlayView.kt) to Compose Desktop.
 */
data class TutorialStep(
    val targetKey: String,
    val text: String,
    /** Override the Next/Done button label. Null = auto ("Done" on last step). */
    val buttonLabel: String? = null,
    /** Hide the Skip button for this step. */
    val hideSkipButton: Boolean = false,
    /** Always place the tooltip above the spotlight. */
    val forceTooltipAbove: Boolean = false,
    /** Minimum spotlight height (extends downwards). */
    val minSpotlightHeight: Dp? = null,
    /** Fraction of spotlight height to shift vertically (+ve = down, -ve = up). */
    val spotlightYOffsetFraction: Float = 0f,
    /** Fraction of spotlight height to trim from the bottom edge. */
    val spotlightBottomTrimFraction: Float = 0f,
    /** Absolute vertical shift of the spotlight. */
    val spotlightYOffset: Dp = 0.dp,
    /** Absolute trim from the right edge of the spotlight (reduces width). */
    val spotlightWidthTrim: Dp = 0.dp,
    /** Absolute trim from the left edge of the spotlight (moves the left edge inward/right). */
    val spotlightLeftTrim: Dp = 0.dp,
    /** Shrinks the spotlight by this amount on both the top and bottom edges. */
    val spotlightVerticalInset: Dp = 0.dp,
    /** Absolute trim from the bottom edge of the spotlight. */
    val spotlightBottomTrim: Dp = 0.dp,
    /** Scales the spotlight around its center (e.g. 0.95f = 5% smaller). */
    val spotlightScale: Float = 1f,
    /** Forces the spotlight to a square (side = the larger of width/height) around its center. */
    val spotlightSquare: Boolean = false,
    /** Re-centers the spotlight horizontally within the overlay (pairs with [fixedSpotlightWidth]). */
    val centerHorizontally: Boolean = false,
    /** Overrides the spotlight width to an absolute size (centered when [centerHorizontally]). */
    val fixedSpotlightWidth: Dp? = null,
    /** A second target whose bounds are unioned with [targetKey] so one spotlight spans both. */
    val unionTargetKey: String? = null,
    /** Vertical nudge applied to the tooltip after normal positioning (-ve = up). */
    val tooltipYOffset: Dp = 0.dp,
    /** Absolute vertical shift of the placeholder content. */
    val placeholderYOffset: Dp = 0.dp,
    /** Alignment of the placeholder content within the spotlight box (default centred). */
    val placeholderAlignment: Alignment = Alignment.Center,
    /** Fake UI rendered inside the spotlight (replaces Android's placeholderLayoutId). */
    val placeholderContent: (@Composable () -> Unit)? = null,
    /**
     * Full-screen replica of a real destination (Connected Devices / QR), drawn over the scrim instead
     * of the spotlight treatment. When set, the spotlight hole is suppressed, the content fills the
     * screen, a transparent tap-catcher keeps tap-to-advance working over the replica's clickables, and
     * the tooltip centres near the bottom. Mutually exclusive with [placeholderContent].
     */
    val fullScreenContent: (@Composable () -> Unit)? = null,
    /** Called when this step becomes active. */
    val onShow: (() -> Unit)? = null,
    /** Called when this step is leaving (next step shown or tutorial dismissed). */
    val onHide: (() -> Unit)? = null,
    /** Milliseconds to wait after onShow before showing the spotlight/tooltip. */
    val delayMs: Long = 0L,
)

/**
 * Full-screen overlay that dims the background 60% and punches a spotlight hole
 * over the target bounds, then shows a tooltip bubble beside it.
 *
 * Target bounds are window coordinates (boundsInWindow) collected by the host
 * screen; the overlay captures its own window bounds and translates them into
 * local space — the Compose analogue of Android's getLocationInWindow pairing.
 */
@Composable
fun TutorialOverlay(
    steps: List<TutorialStep>,
    totalSteps: Int,
    stepOffset: Int = 0,
    boundsMap: Map<String, Rect>,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    /** Increment this counter to advance the tutorial from outside (e.g. dialog outside-clicks). */
    advanceTrigger: Int = 0
) {
    if (steps.isEmpty()) return
    var currentIndex by remember { mutableStateOf(0) }
    val step = steps[currentIndex]
    val isLastStep = currentIndex == steps.size - 1
    val density = LocalDensity.current

    var overlayWindowBounds by remember { mutableStateOf(Rect.Zero) }
    var stepReady by remember(currentIndex) { mutableStateOf(step.delayMs == 0L) }

    // onShow when the step becomes active; onHide on advance AND on overlay removal.
    DisposableEffect(currentIndex) {
        step.onShow?.invoke()
        onDispose { step.onHide?.invoke() }
    }
    LaunchedEffect(currentIndex) {
        if (step.delayMs > 0L) {
            delay(step.delayMs)
            stepReady = true
        }
    }

    // Full-screen replica steps suppress the spotlight entirely (plain scrim, no hole, bottom tooltip).
    val spotlight: Rect? = if (stepReady && step.fullScreenContent == null) {
        val primaryRect = boundsMap[step.targetKey]
        val unionRect = step.unionTargetKey?.let { boundsMap[it] }
        val target = if (primaryRect != null && primaryRect != Rect.Zero &&
            unionRect != null && unionRect != Rect.Zero
        ) {
            Rect(
                minOf(primaryRect.left, unionRect.left),
                minOf(primaryRect.top, unionRect.top),
                maxOf(primaryRect.right, unionRect.right),
                maxOf(primaryRect.bottom, unionRect.bottom),
            )
        } else primaryRect
        computeSpotlightRect(target, overlayWindowBounds, step, density)
    } else null

    // --- entrance motion (parity with Android): glide the hole, fade + slide the content ---
    var fromRect by remember { mutableStateOf<Rect?>(null) }
    var toRect by remember { mutableStateOf<Rect?>(null) }
    val morph = remember { Animatable(1f) }
    LaunchedEffect(spotlight) {
        val target = spotlight ?: return@LaunchedEffect
        val current = toRect
        if (current == null) {
            fromRect = target; toRect = target; morph.snapTo(1f)        // first reveal: snap
        } else if (target != current) {
            fromRect = lerpRect(fromRect ?: current, current, morph.value)
            toRect = target
            morph.snapTo(0f)
            morph.animateTo(1f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
        }
    }
    val hole: Rect? = run {
        val f = fromRect; val t = toRect
        if (f != null && t != null) lerpRect(f, t, morph.value) else spotlight
    }
    val contentAlpha = remember { Animatable(0f) }
    LaunchedEffect(currentIndex, stepReady) {
        if (stepReady) { contentAlpha.snapTo(0f); contentAlpha.animateTo(1f, animationSpec = tween(220)) }
    }
    val slidePx = with(density) { 10.dp.toPx() }

    // Entrance: fade the scrim in as one unit so the overlay never pops on. (Unlike Android, the
    // scrim stays dim-only at 60% — Compose 1.6 has no content-capture API, so a blurred backdrop
    // can't keep the punched hole sharp; the heavier dim carries the layer separation instead.)
    val scrimAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scrimAlpha.animateTo(1f, animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing))
    }

    fun advance() {
        if (isLastStep) onComplete() else currentIndex++
    }

    // External advance requests (e.g. clicks outside a Dialog window)
    LaunchedEffect(advanceTrigger) {
        if (advanceTrigger > 0) advance()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(200f)
            .onGloballyPositioned { overlayWindowBounds = it.boundsInWindow() }
            // Tapping the dim background advances the tutorial (Android parity)
            .pointerInput(currentIndex) {
                detectTapGestures { advance() }
            }
    ) {
        // Dim + spotlight hole
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = scrimAlpha.value
                }
        ) {
            drawRect(Color.Black.copy(alpha = 0.6f)) // 60% black, matches Android 0x99000000
            if (stepReady && hole != null) {
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(hole.left, hole.top),
                    size = Size(hole.width, hole.height),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    blendMode = BlendMode.Clear
                )
            }
        }

        // Placeholder fake UI positioned exactly over the spotlight
        if (spotlight != null && step.placeholderContent != null) {
            val placeholderYOffsetPx = with(density) { step.placeholderYOffset.toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            spotlight.left.roundToInt(),
                            (spotlight.top + placeholderYOffsetPx).roundToInt()
                        )
                    }
                    .size(
                        with(density) { spotlight.width.toDp() },
                        with(density) { spotlight.height.toDp() }
                    )
                    .graphicsLayer {
                        alpha = contentAlpha.value
                        translationY = (1f - contentAlpha.value) * slidePx
                    },
                contentAlignment = step.placeholderAlignment
            ) {
                step.placeholderContent.invoke()
            }
        }

        // Full-screen replica (Connected Devices / QR): drawn over the scrim with a transparent
        // tap-catcher above it so taps over the replica's own clickables still advance the tour.
        if (stepReady && step.fullScreenContent != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = contentAlpha.value
                        translationY = (1f - contentAlpha.value) * slidePx
                    }
            ) {
                step.fullScreenContent.invoke()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(currentIndex) { detectTapGestures { advance() } }
            )
        }

        // Tooltip — measured and placed in a single layout pass
        if (stepReady) {
            TooltipPositioner(
                spotlight = spotlight,
                forceAbove = step.forceTooltipAbove,
                yOffset = step.tooltipYOffset
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1C1C1E),
                    elevation = 24.dp,
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = contentAlpha.value
                            translationY = (1f - contentAlpha.value) * slidePx
                            // Scale 0.95 → 1 so the bubble reads as placed in front, not teleported in.
                            val scale = 0.95f + 0.05f * contentAlpha.value
                            scaleX = scale
                            scaleY = scale
                        }
                        // Consume taps inside the bubble so they don't advance the step
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            "${currentIndex + 1 + stepOffset} / $totalSteps",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            step.text,
                            fontSize = 15.sp,
                            color = Color.White,
                            lineHeight = 22.sp,
                            modifier = Modifier.padding(bottom = 20.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!step.hideSkipButton) {
                                TextButton(onClick = onDismiss) {
                                    Text(tr("tutorial_skip", "Skip"), color = Color.Gray, fontSize = 13.sp)
                                }
                            } else {
                                Spacer(Modifier.width(1.dp))
                            }
                            Button(
                                onClick = { advance() },
                                colors = ButtonDefaults.buttonColors(backgroundColor = GreenPrimary),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    step.buttonLabel ?: if (isLastStep) tr("tutorial_done", "Done") else tr("tutorial_next", "Next"),
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Computes the spotlight rect in overlay-local coordinates, porting Android's
 * updateSpotlightForView math (padding, min height, offsets, trim, clamping).
 * Returns null when bounds are not yet available.
 */
private fun computeSpotlightRect(
    targetWindow: Rect?,
    overlayWindow: Rect,
    step: TutorialStep,
    density: Density
): Rect? {
    if (targetWindow == null || targetWindow == Rect.Zero || overlayWindow == Rect.Zero) return null
    with(density) {
        val pad = 12.dp.toPx()
        var left = targetWindow.left - overlayWindow.left - pad
        var top = targetWindow.top - overlayWindow.top - pad
        var right = targetWindow.right - overlayWindow.left + pad
        var bottom = targetWindow.bottom - overlayWindow.top + pad

        step.minSpotlightHeight?.let { minH ->
            val minHeightPx = minH.toPx()
            if ((bottom - top) < minHeightPx) {
                bottom = top + minHeightPx
            }
        }

        val spotlightH = bottom - top
        if (step.spotlightYOffsetFraction != 0f) {
            val offset = spotlightH * step.spotlightYOffsetFraction
            top += offset
            bottom += offset
        }
        if (step.spotlightYOffset != 0.dp) {
            val offsetPx = step.spotlightYOffset.toPx()
            top += offsetPx
            bottom += offsetPx
        }
        if (step.spotlightBottomTrimFraction != 0f) {
            bottom -= spotlightH * step.spotlightBottomTrimFraction
        }
        if (step.spotlightVerticalInset != 0.dp) {
            val insetPx = step.spotlightVerticalInset.toPx()
            top += insetPx
            bottom -= insetPx
        }
        if (step.spotlightBottomTrim != 0.dp) {
            bottom -= step.spotlightBottomTrim.toPx()
        }
        if (step.spotlightWidthTrim != 0.dp) {
            right -= step.spotlightWidthTrim.toPx()
        }
        if (step.spotlightLeftTrim != 0.dp) {
            left += step.spotlightLeftTrim.toPx()
        }

        // Fixed/centered width: pin the spotlight to an absolute width, centered in the overlay when
        // requested (used by the Send-Files "selected files" demo so all 3 fake cards fit and centre).
        step.fixedSpotlightWidth?.let { fixed ->
            val w = fixed.toPx()
            if (step.centerHorizontally) {
                val cx = overlayWindow.width / 2f
                left = cx - w / 2f
                right = cx + w / 2f
            } else {
                right = left + w
            }
        }

        // Square: force a square spotlight (side = larger edge) around the current centre — used to
        // frame a single round/icon target (e.g. the transfer-history icon button).
        if (step.spotlightSquare) {
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            val side = maxOf(right - left, bottom - top)
            left = cx - side / 2f
            right = cx + side / 2f
            top = cy - side / 2f
            bottom = cy + side / 2f
        }

        // Vertical inset + bottom/width trims on thin anchors can push
        // bottom above top (or right left of left), yielding a degenerate,
        // negative-size Rect that mis-places the tooltip or draws nothing.
        // Clamp so the Rect is always non-negative in size.
        bottom = bottom.coerceAtLeast(top)
        right = right.coerceAtLeast(left)

        // Clamp to overlay bounds
        var rect = Rect(
            left,
            top,
            right.coerceAtMost(overlayWindow.width),
            bottom.coerceAtMost(overlayWindow.height)
        )
        if (step.spotlightScale != 1f) {
            val halfW = rect.width / 2f * step.spotlightScale
            val halfH = rect.height / 2f * step.spotlightScale
            rect = Rect(
                rect.center.x - halfW,
                rect.center.y - halfH,
                rect.center.x + halfW,
                rect.center.y + halfH
            )
        }
        return rect
    }
}

/**
 * Measures the tooltip and places it adjacent to the spotlight in a single
 * layout pass: below the spotlight if it fits, otherwise above; horizontally
 * centered on the spotlight and clamped to 16dp margins. Ports Android's
 * positionTooltip().
 */
@Composable
private fun TooltipPositioner(
    spotlight: Rect?,
    forceAbove: Boolean,
    yOffset: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = 16.dp.roundToPx()
        val yOffsetPx = yOffset.toPx()
        val maxTooltipWidth = minOf(400.dp.roundToPx(), (constraints.maxWidth - 2 * margin).coerceAtLeast(1))
        val placeable = measurables.first().measure(
            Constraints(maxWidth = maxTooltipWidth, maxHeight = constraints.maxHeight)
        )

        layout(constraints.maxWidth, constraints.maxHeight) {
            val maxLeft = (constraints.maxWidth - placeable.width - margin).toFloat()
            val maxTop = (constraints.maxHeight - placeable.height - margin).toFloat()

            if (spotlight == null) {
                // No spotlight (full-screen replica step): place the tooltip near the TOP so it never
                // covers the centered replica dialog's bottom action button. A per-step yOffset can push
                // it lower (e.g. the open-file detail drops it into the empty middle, off the Open button);
                // clamp so a large offset can't push it past the bottom margin.
                val left = ((constraints.maxWidth - placeable.width) / 2f)
                    .coerceIn(margin.toFloat(), maxOf(margin.toFloat(), maxLeft))
                val top = (margin.toFloat() + yOffsetPx)
                    .coerceIn(margin.toFloat(), maxOf(margin.toFloat(), maxTop))
                placeable.place(left.roundToInt(), top.roundToInt())
                return@layout
            }

            val topIfBelow = spotlight.bottom + margin
            val topIfAbove = spotlight.top - placeable.height - margin
            // Prefer BELOW, near the spotlight; go above only when the spotlight sits low.
            val belowFits = topIfBelow + placeable.height <= constraints.maxHeight - margin
            val top = when {
                forceAbove -> topIfAbove
                belowFits -> topIfBelow
                spotlight.top >= (placeable.height + 2 * margin).toFloat() -> topIfAbove
                else -> topIfBelow
            }.coerceIn(margin.toFloat(), maxOf(margin.toFloat(), maxTop))

            val left = (spotlight.center.x - placeable.width / 2f)
                .coerceIn(margin.toFloat(), maxOf(margin.toFloat(), maxLeft))

            // Per-step nudge applied after clamping (full manual control)
            placeable.place(left.roundToInt(), (top + yOffsetPx).roundToInt())
        }
    }
}

/** Linearly interpolates between two rects (used to glide the spotlight between steps). */
private fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)
