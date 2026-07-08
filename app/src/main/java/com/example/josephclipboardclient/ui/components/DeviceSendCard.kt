package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

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
            modifier = Modifier.weight(1f)
        )
        Box {
            when {
                isSending -> {
                    Text(
                        stringResource(R.string.action_sending),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.info,
                        fontWeight = FontWeight.Bold
                    )
                }
                isPaired -> {
                    Text(
                        stringResource(R.string.action_send),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.green,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onSend() }
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.action_pair),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onPair() }
                    )
                }
            }
        }
    }
}
