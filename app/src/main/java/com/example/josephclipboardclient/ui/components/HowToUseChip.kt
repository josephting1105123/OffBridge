package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/**
 * The single "How to use" chip used across every screen — one definition so its size, padding and
 * feedback can't drift between the four interfaces (the inconsistency this redesign fixes).
 *
 * Faded-green pill, fixed 32dp tall, leading help glyph, ripple feedback. Text/icon use the
 * WCAG-safe [greenText][com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppColors.greenText];
 * the fill is the brand green at low alpha.
 */
@Composable
fun HowToUseChip(
    onClick: () -> Unit,
    text: String = stringResource(R.string.action_how_to_use),
    modifier: Modifier = Modifier,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(dimens.radiusSm))
            .background(colors.green.copy(alpha = 0.15f))
            .clickable(role = Role.Button) { onClick() }
            .height(32.dp)
            .padding(horizontal = dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceXs),
    ) {
        Icon(
            imageVector = Icons.Outlined.HelpOutline,
            contentDescription = null,
            tint = colors.greenText,
            modifier = Modifier.size(dimens.iconSm),
        )
        Text(text = text, color = colors.greenText, style = MaterialTheme.typography.labelLarge)
    }
}
