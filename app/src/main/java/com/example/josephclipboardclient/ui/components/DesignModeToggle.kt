package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

    val accent = selectedColors?.getOrNull(selectedIndex) ?: colors.green
    // Ease the accent (green <-> red) alongside the slide so the colour change isn't a hard cut.
    val thumbColor by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "thumb_color"
    )

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
        val segmentWidth = maxWidth / 2
        val thumbOffset by animateDpAsState(
            targetValue = if (selectedIndex == 1) segmentWidth else 0.dp,
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "thumb_offset"
        )

        // The sliding glass thumb: faint accent tint + accent hairline outline (no fill, no stripes).
        Box(
            modifier = Modifier
                .width(segmentWidth)
                .fillMaxHeight()
                .offset(x = thumbOffset)
                .clip(thumbShape)
                .background(thumbColor.copy(alpha = 0.12f))
                .border(1.5.dp, thumbColor, thumbShape)
        )

        // Tap targets + optional labels; the selected label is in the accent colour.
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, text ->
                val isSelected = index == selectedIndex
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
                                onClick = { onSelect(index) }
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
