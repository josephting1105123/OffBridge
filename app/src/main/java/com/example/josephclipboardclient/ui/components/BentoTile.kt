package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/**
 * Modular bento tile — an accent icon chip over a big [value] and a small [label]. Sits in a
 * responsive grid on the Send / System-status dashboards. Optional [onClick] makes it tappable.
 *
 * Height is content-driven (no fixed height) so it stays readable at large Dynamic Type sizes.
 */
@Composable
fun BentoTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = AppThemeTokens.colors.green,
    valueColor: Color = AppThemeTokens.colors.textPrimary,
    onClick: (() -> Unit)? = null,
) {
    GlassCard(modifier = modifier, onClick = onClick) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(AppThemeTokens.dimens.radiusSm))
                    .background(AppThemeTokens.colors.accentMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(AppThemeTokens.dimens.iconMd),
                )
            }
            Spacer(Modifier.height(AppThemeTokens.dimens.spaceMd))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(AppThemeTokens.dimens.spaceXxs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = AppThemeTokens.colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
