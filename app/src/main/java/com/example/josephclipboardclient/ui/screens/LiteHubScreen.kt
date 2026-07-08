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
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

@Composable
fun LiteHubScreen(
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    onClipboardClick: () -> Unit
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
                .padding(dimens.spaceXl),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceXl)
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.padding(top = 72.dp, bottom = dimens.spaceXxl),
                fontWeight = FontWeight.Bold
            )

            HubButton(
                text = stringResource(R.string.lite_send_files),
                iconRes = R.drawable.ic_hdr_send,
                onClick = onSendClick
            )
            HubButton(
                text = stringResource(R.string.lite_receive_files),
                iconRes = R.drawable.ic_hdr_receive,
                onClick = onReceiveClick
            )
            HubButton(
                text = stringResource(R.string.lite_clipboard),
                iconRes = R.drawable.ic_hdr_history,
                onClick = onClipboardClick
            )
        }
    }
}

@Composable
private fun HubButton(text: String, iconRes: Int, onClick: () -> Unit) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimens.buttonHeight * 1.4f)
            .clip(RoundedCornerShape(dimens.radiusLg))
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.divider, RoundedCornerShape(dimens.radiusLg))
            .clickable { onClick() }
            .padding(horizontal = dimens.spaceXl),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = colors.green,
            modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg)
        )
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}
