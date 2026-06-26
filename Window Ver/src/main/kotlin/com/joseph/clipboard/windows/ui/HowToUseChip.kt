package com.joseph.clipboard.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.joseph.clipboard.windows.theme.Dimens
import com.joseph.clipboard.windows.theme.LocalExtraColors

/**
 * The single "How to use" chip used across every screen — one definition so its size, padding and
 * feedback can't drift between screens (previously Send/System/History each used different padding
 * and only History had a ripple). Faded-green pill, fixed 32dp tall, leading help glyph, ripple.
 */
@Composable
fun HowToUseChip(
    onClick: () -> Unit,
    text: String = "How to use",
) {
    val extra = LocalExtraColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.radiusSm))
            .background(extra.accentMuted)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(color = extra.green),
            ) { onClick() }
            .height(32.dp)
            .padding(horizontal = Dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        Icon(
            imageVector = Icons.Outlined.HelpOutline,
            contentDescription = null,
            tint = extra.greenText,
            modifier = Modifier.size(Dimens.iconSm),
        )
        Text(text = text, color = extra.greenText, style = MaterialTheme.typography.button)
    }
}
