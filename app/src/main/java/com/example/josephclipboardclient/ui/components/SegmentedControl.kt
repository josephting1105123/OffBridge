package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/**
 * Pill segmented control — a hairline-bordered track with a green-filled selected segment.
 * Use for mode/filter switching (e.g. the History filter capsules). Segments size to content.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val pill = RoundedCornerShape(dimens.radiusPill)
    Row(
        modifier
            .clip(pill)
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.hairline, pill)
            .padding(dimens.spaceXxs)
            // Announce as one tab group so TalkBack reads "N of M" and the selected state.
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceXxs),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(pill)
                    .background(if (selected) colors.green else Color.Transparent)
                    // selectable (not clickable) so the segment exposes its selected state + Tab role.
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = dimens.spaceMd, vertical = dimens.spaceSm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.White else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}
