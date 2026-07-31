package com.EntropicSoftwareLab.ClipboardShare.sendvia

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.formatTotalSize
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/** Transport choices delivered by [SendViaSheet]'s onChoose. */
object SendViaChoice {
    const val DEVICES = "devices"
    const val QR = "qr"
    const val NFC = "nfc"
}

/** Compose "Send Via" chooser: three options (Connected Devices / QR / NFC) as icon-above-label boxes. */
@Composable
fun SendViaSheet(
    nfcUnavailable: Boolean,
    totalSizeBytes: Long,
    onChoose: (String) -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val shape = RoundedCornerShape(dimens.radiusXl)
    Column(
        modifier = Modifier
            // Floating island: side/bottom margins keep the frosted backdrop visible between the card
            // and the nav bar, so the two surfaces never share an edge (the dark-mode seam fix).
            // widthIn must precede fillMaxWidth or the tablet/landscape cap is ignored.
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .padding(start = dimens.spaceMd, end = dimens.spaceMd, bottom = dimens.spaceMd)
            .clip(shape)
            .background(colors.bottomSheet)
            .border(dimens.hairlineWidth, colors.hairline, shape)
            .padding(dimens.spaceLg),
    ) {
        Text(
            stringResource(R.string.send_via_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            stringResource(R.string.send_via_size_selected, formatTotalSize(totalSizeBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = dimens.spaceXs, bottom = dimens.spaceLg),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd),
        ) {
            SendViaOption(
                label = stringResource(R.string.send_via_connected_devices),
                onClick = { onChoose(SendViaChoice.DEVICES) },
            ) {
                Icon(
                    painterResource(R.drawable.ic_device),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.iconLg),
                )
            }
            SendViaOption(
                label = stringResource(R.string.send_via_qr),
                onClick = { onChoose(SendViaChoice.QR) },
            ) {
                Icon(
                    painterResource(R.drawable.ic_qr_frame),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.iconLg),
                )
            }
            SendViaOption(
                label = stringResource(R.string.send_via_nfc),
                enabled = !nfcUnavailable,
                onClick = { onChoose(SendViaChoice.NFC) },
            ) {
                Icon(
                    painterResource(R.drawable.ic_nfc),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.iconLg),
                )
            }
        }
        if (nfcUnavailable) {
            Text(
                stringResource(R.string.send_via_nfc_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = dimens.spaceMd),
            )
        }
    }
}

@Composable
private fun RowScope.SendViaOption(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val shape = RoundedCornerShape(dimens.radiusLg)
    Column(
        modifier = Modifier
            .weight(1f)
            .height(104.dp)
            .clip(shape)
            .alpha(if (enabled) 1f else 0.45f)
            .background(colors.surfaceElevated)
            .border(dimens.hairlineWidth, colors.divider, shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(dimens.spaceMd),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(dimens.iconLg), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(dimens.spaceSm))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
