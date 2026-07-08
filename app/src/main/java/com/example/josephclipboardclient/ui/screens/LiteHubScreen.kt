package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
                .navigationBarsPadding()
                .padding(horizontal = dimens.spaceXl)
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 72.dp, bottom = dimens.spaceXxl)
            )

            // Space between the title and the bottom bar; the tile grid fills ~80% of it (centered),
            // leaving breathing room above and below. The 3 tiles sit inside a tray — a hairline
            // rounded frame, same grouping the power menu uses (menu_group_frame).
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.8f)
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(dimens.radiusXl))
                        .border(dimens.hairlineWidth, colors.divider, RoundedCornerShape(dimens.radiusXl))
                        .padding(dimens.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(dimens.spaceLg)
                ) {
                    // Top row: Send (left) + Receive (right). Twice the height of the Clipboard tile
                    // below, so Clipboard : Receive = 1 : 2.
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(2f),
                        horizontalArrangement = Arrangement.spacedBy(dimens.spaceLg)
                    ) {
                        HubTile(
                            text = stringResource(R.string.lite_send_files),
                            iconRes = R.drawable.ic_hdr_send,
                            onClick = onSendClick,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        HubTile(
                            text = stringResource(R.string.lite_receive_files),
                            iconRes = R.drawable.ic_hdr_receive,
                            onClick = onReceiveClick,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    // Bottom row: Clipboard, full width, half the height of the top row (1:2).
                    HubTile(
                        text = stringResource(R.string.lite_clipboard),
                        iconRes = R.drawable.ic_hdr_history,
                        onClick = onClipboardClick,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }

            // Reserve for the lite_nav_bar + power FAB (excluded from the 80% grid area above).
            Spacer(Modifier.height(96.dp))
        }
    }
}

/** Hub tile in the power-menu button style: centered icon (green) above a label, on a bordered card. */
@Composable
private fun HubTile(
    text: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val shape = RoundedCornerShape(dimens.radiusLg)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.divider, shape)
            .clickable { onClick() },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = colors.green,
            modifier = Modifier.size(dimens.iconLg)
        )
        Spacer(Modifier.height(dimens.spaceSm))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}
