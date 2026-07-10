package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/**
 * Two-option slider (e.g. Lite/Pro, On/Off): a track with one thumb that SLIDES between the two
 * positions in the pairing-button GLASS language — near-transparent fill, a hairline accent-coloured
 * outline, and the selected label in the accent colour. No stripes, no sheen.
 *
 * @param selectedColors per-option accent (outline + label + faint fill) used when that option is
 *   selected; defaults to green for both. Pass WCAG-safe text colours (e.g. greenText / error) so the
 *   selected label stays readable on the glass.
 * @param showLabels when false, only the sliding glass thumb is drawn — a compact switch whose meaning
 *   is carried by position + colour (used where a translated On/Off word wouldn't fit).
 * @param interactive when false, the halves aren't clickable; a parent (e.g. a toggleable row) owns the
 *   tap and this is a pure visual indicator that still animates from [selectedIndex].
 * @param height track height; defaults to 56.dp (the mode-toggle size). Compact switches pass less.
 */
@Composable
fun DesignModeToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedColors: List<Color>? = null,
    showLabels: Boolean = true,
    interactive: Boolean = true,
    height: Dp = 56.dp,
) {
    if (options.size != 2) return

    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // Thumb position as a horizontal bias (-1 = left option, +1 = right); an Animatable so an
    // active drag can snap it per frame.
    val biasAnimatable = remember { androidx.compose.animation.core.Animatable(if (selectedIndex == 1) 1f else -1f) }
    var isDragging by remember { androidx.compose.runtime.mutableStateOf(false) }

    // The thumb always follows [selectedIndex] (the parent's state is the single source of truth)
    // unless a drag is in flight. Re-running on every external change — even mid-animation — is what
    // keeps the toggle from sticking on the wrong side when the state flips from elsewhere.
    androidx.compose.runtime.LaunchedEffect(selectedIndex, isDragging) {
        if (!isDragging) {
            biasAnimatable.animateTo(
                targetValue = if (selectedIndex == 1) 1f else -1f,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        }
    }

    val trackShape = RoundedCornerShape(dimens.radiusMd)
    val thumbShape = RoundedCornerShape(dimens.radiusSm)

    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .clip(trackShape)
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.divider, trackShape)
            .padding(4.dp)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val bias = biasAnimatable.value
        val view = androidx.compose.ui.platform.LocalView.current
        
        // Interpolate color dynamically based on current drag position (bias)
        val color0 = selectedColors?.getOrNull(0) ?: colors.green
        val color1 = selectedColors?.getOrNull(1) ?: colors.green
        val fraction = ((bias + 1f) / 2f).coerceIn(0f, 1f)
        val thumbColor = androidx.compose.ui.graphics.lerp(color0, color1, fraction)

        // Wrapper Box to handle drag gestures over the entire component
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (interactive) Modifier
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    if (event.changes.any { it.pressed }) {
                                        view.parent?.requestDisallowInterceptTouchEvent(true)
                                    }
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    // Report the side the thumb landed on, then let the sync effect
                                    // settle it onto the (possibly updated) selection.
                                    val chosen = if (biasAnimatable.value > 0f) 1 else 0
                                    if (chosen != selectedIndex) onSelect(chosen)
                                    isDragging = false
                                },
                                onDragCancel = { isDragging = false },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    isDragging = true
                                    val newBias = (biasAnimatable.value + (dragAmount * 4f / widthPx)).coerceIn(-1f, 1f)
                                    coroutineScope.launch {
                                        biasAnimatable.snapTo(newBias)
                                    }
                                }
                            )
                        } else Modifier
                )
        ) {
            // The sliding glass thumb: faint accent tint + accent hairline outline (no fill, no stripes).
            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.BiasAlignment(horizontalBias = bias, verticalBias = 0f))
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight()
                    .clip(thumbShape)
                    .background(thumbColor.copy(alpha = 0.12f))
                    .border(1.5.dp, thumbColor, thumbShape)
            )

            // Tap targets + optional labels; the selected label is in the accent colour.
            Row(modifier = Modifier.fillMaxSize()) {
                options.forEachIndexed { index, text ->
                    // Check selection against the interpolated bias so label colours crossfade with the slide.
                    val isSelected = if (index == 0) bias < 0f else bias > 0f
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(thumbShape)
                            .then(
                                if (interactive) Modifier.clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    onClick = { if (index != selectedIndex) onSelect(index) }
                                ) else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (showLabels) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) thumbColor else colors.textSecondary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}
