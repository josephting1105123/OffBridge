package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.hypot
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

@Composable
fun LiteHubScreen(
    onSendClick: (Float, Float) -> Unit,
    onReceiveClick: (Float, Float) -> Unit,
    onClipboardClick: (Float, Float) -> Unit
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = dimens.spaceXl)
        ) {
            Spacer(Modifier.height(96.dp))

            // Conversational Greeting Header
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(dimens.spaceXs))
            Text(
                stringResource(R.string.lite_hub_prompt),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textSecondary
            )

            Spacer(Modifier.height(64.dp))

            // Send action card
            LiteActionCard(
                title = stringResource(R.string.lite_send_files),
                subtitle = stringResource(R.string.lite_hub_send_subtitle),
                iconRes = R.drawable.ic_hdr_send,
                onClick = onSendClick
            )

            Spacer(Modifier.height(dimens.spaceLg))

            // Receive action card
            LiteActionCard(
                title = stringResource(R.string.lite_receive_files),
                subtitle = stringResource(R.string.lite_hub_receive_subtitle),
                iconRes = R.drawable.ic_hdr_receive,
                onClick = onReceiveClick
            )

            Spacer(Modifier.height(dimens.spaceLg))

            // Clipboard action card
            LiteActionCard(
                title = stringResource(R.string.lite_clipboard),
                subtitle = stringResource(R.string.lite_hub_clipboard_subtitle),
                iconRes = R.drawable.ic_hdr_history,
                onClick = onClipboardClick
            )

            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LiteActionCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    onClick: (Float, Float) -> Unit
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val shape = RoundedCornerShape(dimens.radiusXl)

    var arrowCenter by remember { mutableStateOf(Offset.Zero) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.background)
            .border(dimens.hairlineWidth, colors.divider, shape)
            .clickable { onClick(arrowCenter.x, arrowCenter.y) }
            .padding(dimens.spaceLg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon container
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(colors.card)
                .border(dimens.hairlineWidth, colors.divider, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = colors.green,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(Modifier.width(dimens.spaceLg))

        // Text & Subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        }

        Spacer(Modifier.width(dimens.spaceMd))
        
        // Navigation chevron — its centre seeds the circular-reveal origin for the next screen.
        Icon(
            imageVector = Icons.Filled.ArrowForward,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier
                .size(24.dp)
                .onGloballyPositioned { coordinates ->
                    arrowCenter = coordinates.boundsInRoot().center
                }
        )
    }
}
