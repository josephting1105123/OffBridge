package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/** One tile in Lite's Send grid; paired tiles send on tap, unpaired tiles pair. */
@Composable
fun DeviceSendTile(
    name: String,
    isWindows: Boolean,
    isPaired: Boolean,
    isSending: Boolean,
    sendEnabled: Boolean,
    onPair: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    Column(
        modifier = modifier
            .width(88.dp)
            .clip(RoundedCornerShape(dimens.radiusMd))
            .clickable(enabled = !isPaired || (sendEnabled && !isSending)) {
                if (isPaired) onSend() else onPair()
            }
            .padding(vertical = dimens.spaceSm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val circleModifier = if (isPaired && !isSending && !sendEnabled) {
            Modifier.size(72.dp).alpha(0.5f)
        } else {
            Modifier.size(72.dp)
        }
        Box(circleModifier, contentAlignment = Alignment.Center) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = colors.green,
                    strokeWidth = 3.dp
                )
            }
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(if (isPaired) colors.green.copy(alpha = 0.15f) else colors.card)
                    .border(dimens.hairlineWidth, colors.divider, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(if (isWindows) R.drawable.ic_computer else R.drawable.ic_device),
                    contentDescription = null,
                    tint = if (isPaired) colors.green else colors.textSecondary,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        Spacer(Modifier.height(dimens.spaceSm))

        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        val (statusRes, statusColor) = when {
            !isPaired -> R.string.action_pair to colors.green
            isSending -> R.string.action_sending to colors.textPrimary
            sendEnabled -> R.string.action_send to colors.info
            else -> R.string.action_send to colors.textSecondary
        }
        Text(
            text = stringResource(statusRes),
            style = MaterialTheme.typography.labelMedium,
            color = statusColor,
            fontWeight = FontWeight.Bold
        )
    }
}
