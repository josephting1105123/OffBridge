package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun DeviceSendCard(
    name: String,
    isWindows: Boolean,
    isPaired: Boolean,
    isSending: Boolean,
    onPair: () -> Unit,
    onSend: () -> Unit
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.radiusLg))
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.divider, RoundedCornerShape(dimens.radiusLg))
            .padding(dimens.spaceLg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(if (isWindows) R.drawable.ic_computer else R.drawable.ic_device),
            contentDescription = null,
            tint = colors.green,
            modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg)
        )
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = dimens.spaceSm)
        )
        Box(contentAlignment = Alignment.CenterEnd) {
            when {
                // In flight: plain text, no button.
                isSending -> {
                    Text(
                        stringResource(R.string.action_sending),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.info,
                        fontWeight = FontWeight.Bold
                    )
                }
                // Paired → "Send", unpaired → "Pair": both the same capsule as DeviceCardRow's Pair.
                isPaired -> CapsuleAction(stringResource(R.string.action_send), onSend)
                else -> CapsuleAction(stringResource(R.string.action_pair), onPair)
            }
        }
    }
}

/** Transparent, hairline-outlined green capsule — same design as DeviceCardRow's Pair/Unpair button. */
@Composable
private fun CapsuleAction(label: String, onClick: () -> Unit) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(dimens.radiusPill),
        border = BorderStroke(dimens.hairlineWidth, colors.divider),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(horizontal = dimens.spaceLg, vertical = dimens.spaceSm),
    ) {
        Text(label, color = colors.green, fontWeight = FontWeight.Bold)
    }
}
