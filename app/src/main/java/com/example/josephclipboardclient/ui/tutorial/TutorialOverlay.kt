package com.EntropicSoftwareLab.ClipboardShare.ui.tutorial

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
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
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A single tutorial step. Ported from the Windows Compose overlay (which itself ports the legacy
 * Android TutorialOverlayView). The finger-tap pointer + crossfade-reveal (old steps 6/7) is now
 * self-contained inside [placeholderContent] (see TutorialCursorReveal.kt) — no special step fields.
 */
data class TutorialStep(
    /** Key registered by the target via [Modifier.tutorialAnchor]; resolved through the bounds map. */
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
    /** Shrinks the spotlight by this amount on both the top and bottom edges. */
    val spotlightVerticalInset: Dp = 0.dp,
    /** Absolute trim from the bottom edge of the spotlight. */
    val spotlightBottomTrim: Dp = 0.dp,
    /** Scales the spotlight around its center (e.g. 0.95f = 5% smaller). */
    val spotlightScale: Float = 1f,
    /** Fixes the spotlight height (top edge kept) so it frames a tight band, not the full target. */
    val spotlightHeight: Dp? = null,
    /** Forces the spotlight into a square (side = min(width, height)) centred on the target. */
    val spotlightSquare: Boolean = false,
    /** Vertical nudge applied to the tooltip after normal positioning (-ve = up). */
    val tooltipYOffset: Dp = 0.dp,
    /** Absolute vertical shift of the placeholder content. */
    val placeholderYOffset: Dp = 0.dp,
    /** Alignment of the placeholder content within the spotlight box (default centred). */
    val placeholderAlignment: Alignment = Alignment.Center,
    /** Fake UI rendered inside the spotlight. */
    val placeholderContent: (@Composable () -> Unit)? = null,
    /**
     * Full-screen replica of a real destination screen (Send-Via / Connected Devices / QR / NFC),
     * drawn opaque over the app instead of the spotlight treatment. When set, the spotlight hole is
     * suppressed (plain scrim only), the content fills the screen, a transparent tap-catcher keeps
     * tap-to-advance working over the replica's clickable children, and the tooltip centres near the
     * bottom. Mutually exclusive with [placeholderContent].
     */
    val fullScreenContent: (@Composable () -> Unit)? = null,
    /** Called when this step becomes active. */
    val onShow: (() -> Unit)? = null,
    /** Called when this step is leaving (next step shown or tutorial dismissed). */
    val onHide: (() -> Unit)? = null,
    /** Milliseconds to wait after onShow before showing the spotlight/tooltip. */
    val delayMs: Long = 0L,
)

/** Remembers the anchor-bounds registry a screen shares with its [TutorialOverlay]. */
@Composable
fun rememberTutorialAnchors(): SnapshotStateMap<String, Rect> = remember { mutableStateMapOf() }

/** Tag a composable as a tutorial target; its window bounds are published into [anchors] under [key]. */
fun Modifier.tutorialAnchor(anchors: MutableMap<String, Rect>, key: String): Modifier =
    this.onGloballyPositioned { anchors[key] = it.boundsInWindow() }

/**
 * Full-screen overlay that dims the background 60% and punches a rounded spotlight hole over the
 * target bounds, then shows a tooltip bubble beside it. Tapping the dim area advances; the bubble
 * consumes taps. Bounds are window coordinates collected via [tutorialAnchor]; the overlay captures
 * its own window bounds and translates into local space.
 */
@Composable
fun TutorialOverlay(
    steps: List<TutorialStep>,
    totalSteps: Int,
    stepOffset: Int = 0,
    boundsMap: Map<String, Rect>,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    if (steps.isEmpty()) return
    var currentIndex by remember { mutableStateOf(0) }
    val step = steps[currentIndex]
    val isLastStep = currentIndex == steps.size - 1
    val density = LocalDensity.current

    var overlayWindowBounds by remember { mutableStateOf(Rect.Zero) }
    var stepReady by remember(currentIndex) { mutableStateOf(step.delayMs == 0L) }

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
        computeSpotlightRect(boundsMap[step.targetKey], overlayWindowBounds, step, density)
    } else null

    // Entrance motion: glide the hole, fade + slide the content.
    var fromRect by remember { mutableStateOf<Rect?>(null) }
    var toRect by remember { mutableStateOf<Rect?>(null) }
    val morph = remember { Animatable(1f) }
    LaunchedEffect(spotlight) {
        val target = spotlight ?: return@LaunchedEffect
        val current = toRect
        if (current == null) {
            fromRect = target; toRect = target; morph.snapTo(1f)
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

    fun advance() {
        if (isLastStep) onComplete() else currentIndex++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(200f)
            .onGloballyPositioned { overlayWindowBounds = it.boundsInWindow() }
            .pointerInput(currentIndex) { detectTapGestures { advance() } }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            drawRect(Color.Black.copy(alpha = 0.6f))
            if (stepReady && hole != null) {
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(hole.left, hole.top),
                    size = Size(hole.width, hole.height),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    blendMode = BlendMode.Clear,
                )
            }
        }

        if (spotlight != null && step.placeholderContent != null) {
            val placeholderYOffsetPx = with(density) { step.placeholderYOffset.toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            spotlight.left.roundToInt(),
                            (spotlight.top + placeholderYOffsetPx).roundToInt(),
                        )
                    }
                    .size(
                        with(density) { spotlight.width.toDp() },
                        with(density) { spotlight.height.toDp() },
                    )
                    .graphicsLayer {
                        alpha = contentAlpha.value
                        translationY = (1f - contentAlpha.value) * slidePx
                    },
                contentAlignment = step.placeholderAlignment,
            ) {
                step.placeholderContent.invoke()
            }
        }

        // Full-screen replica (Send-Via / Connected Devices / QR / NFC): drawn opaque over the scrim,
        // with a transparent tap-catcher above it so taps over the replica's own clickables still
        // advance the tour. Rendered before the tooltip so the tooltip (and its Skip/Next) stays on top.
        if (stepReady && step.fullScreenContent != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = contentAlpha.value
                        translationY = (1f - contentAlpha.value) * slidePx
                    },
            ) {
                step.fullScreenContent.invoke()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(currentIndex) { detectTapGestures { advance() } },
            )
        }

        if (stepReady) {
            TooltipPositioner(
                spotlight = spotlight,
                forceAbove = step.forceTooltipAbove,
                yOffset = step.tooltipYOffset,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1C1C1E),
                    shadowElevation = 24.dp,
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = contentAlpha.value
                            translationY = (1f - contentAlpha.value) * slidePx
                        }
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            "${currentIndex + 1 + stepOffset} / $totalSteps",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Text(
                            step.text,
                            fontSize = 15.sp,
                            color = Color.White,
                            lineHeight = 22.sp,
                            modifier = Modifier.padding(bottom = 20.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!step.hideSkipButton) {
                                TextButton(onClick = onDismiss) {
                                    Text("Skip", color = Color.Gray, fontSize = 13.sp)
                                }
                            } else {
                                Spacer(Modifier.width(1.dp))
                            }
                            Button(
                                onClick = { advance() },
                                colors = ButtonDefaults.buttonColors(containerColor = AppThemeTokens.colors.green),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text(
                                    step.buttonLabel ?: if (isLastStep) "Done" else "Next",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Spotlight rect in overlay-local coordinates (padding, min height, offsets, trim, clamping). */
private fun computeSpotlightRect(
    targetWindow: Rect?,
    overlayWindow: Rect,
    step: TutorialStep,
    density: Density,
): Rect? {
    if (targetWindow == null || targetWindow == Rect.Zero || overlayWindow == Rect.Zero) return null
    with(density) {
        val pad = 12.dp.toPx()
        val left = targetWindow.left - overlayWindow.left - pad
        var top = targetWindow.top - overlayWindow.top - pad
        var right = targetWindow.right - overlayWindow.left + pad
        var bottom = targetWindow.bottom - overlayWindow.top + pad

        step.minSpotlightHeight?.let { minH ->
            val minHeightPx = minH.toPx()
            if ((bottom - top) < minHeightPx) bottom = top + minHeightPx
        }
        // Fixed-height band: frame a single content row (e.g. the 3 fake files) instead of the tall grid.
        step.spotlightHeight?.let { h -> bottom = top + h.toPx() }

        val spotlightH = bottom - top
        if (step.spotlightYOffsetFraction != 0f) {
            val offset = spotlightH * step.spotlightYOffsetFraction
            top += offset; bottom += offset
        }
        if (step.spotlightYOffset != 0.dp) {
            val offsetPx = step.spotlightYOffset.toPx()
            top += offsetPx; bottom += offsetPx
        }
        if (step.spotlightBottomTrimFraction != 0f) bottom -= spotlightH * step.spotlightBottomTrimFraction
        if (step.spotlightVerticalInset != 0.dp) {
            val insetPx = step.spotlightVerticalInset.toPx()
            top += insetPx; bottom -= insetPx
        }
        if (step.spotlightBottomTrim != 0.dp) bottom -= step.spotlightBottomTrim.toPx()
        if (step.spotlightWidthTrim != 0.dp) right -= step.spotlightWidthTrim.toPx()

        // Clamp so the Rect is never negative-size on thin anchors.
        bottom = bottom.coerceAtLeast(top)
        right = right.coerceAtLeast(left)

        var rect = Rect(
            left,
            top,
            right.coerceAtMost(overlayWindow.width),
            bottom.coerceAtMost(overlayWindow.height),
        )
        if (step.spotlightScale != 1f) {
            val halfW = rect.width / 2f * step.spotlightScale
            val halfH = rect.height / 2f * step.spotlightScale
            rect = Rect(
                rect.center.x - halfW,
                rect.center.y - halfH,
                rect.center.x + halfW,
                rect.center.y + halfH,
            )
        }
        // Square spotlight centred on the icon: side = the smaller edge, recentred on the target's true
        // centre (not the clamped rect's) and shifted to stay on-screen. Used for the history icon.
        if (step.spotlightSquare) {
            val targetCx = (targetWindow.left + targetWindow.right) / 2f - overlayWindow.left
            val targetCy = (targetWindow.top + targetWindow.bottom) / 2f - overlayWindow.top
            val side = minOf(rect.width, rect.height)
            val sl = (targetCx - side / 2f).coerceIn(0f, (overlayWindow.width - side).coerceAtLeast(0f))
            val st = (targetCy - side / 2f).coerceIn(0f, (overlayWindow.height - side).coerceAtLeast(0f))
            rect = Rect(sl, st, sl + side, st + side)
        }
        return rect
    }
}

/** Places the tooltip adjacent to the spotlight (below if it fits, else above), centered + clamped. */
@Composable
private fun TooltipPositioner(
    spotlight: Rect?,
    forceAbove: Boolean,
    yOffset: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = 16.dp.roundToPx()
        val yOffsetPx = yOffset.toPx()
        val maxTooltipWidth = minOf(400.dp.roundToPx(), (constraints.maxWidth - 2 * margin).coerceAtLeast(1))
        val placeable = measurables.first().measure(
            Constraints(maxWidth = maxTooltipWidth, maxHeight = constraints.maxHeight),
        )

        layout(constraints.maxWidth, constraints.maxHeight) {
            val maxLeft = (constraints.maxWidth - placeable.width - margin).toFloat()
            val maxTop = (constraints.maxHeight - placeable.height - margin).toFloat()

            if (spotlight == null) {
                // No spotlight (full-screen replica step): place the tooltip near the TOP so it never
                // covers the bottom-docked Send-Via sheet or a replica's bottom action button.
                val left = ((constraints.maxWidth - placeable.width) / 2f)
                    .coerceIn(margin.toFloat(), maxOf(margin.toFloat(), maxLeft))
                val top = margin.toFloat()
                placeable.place(left.roundToInt(), (top + yOffsetPx).roundToInt())
                return@layout
            }

            val topIfBelow = spotlight.bottom + margin
            val topIfAbove = spotlight.top - placeable.height - margin
            val belowFits = topIfBelow + placeable.height <= constraints.maxHeight - margin
            val top = when {
                forceAbove -> topIfAbove
                belowFits -> topIfBelow
                spotlight.top >= (placeable.height + 2 * margin).toFloat() -> topIfAbove
                else -> topIfBelow
            }.coerceIn(margin.toFloat(), maxOf(margin.toFloat(), maxTop))

            val left = (spotlight.center.x - placeable.width / 2f)
                .coerceIn(margin.toFloat(), maxOf(margin.toFloat(), maxLeft))

            placeable.place(left.roundToInt(), (top + yOffsetPx).roundToInt())
        }
    }
}

private fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)
