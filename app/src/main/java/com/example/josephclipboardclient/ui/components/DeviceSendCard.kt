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

/**
 * One target device row: name plus a Pair / Send / Sending capsule.
 *
 * [isSending] marks THIS device as the one in flight; [sendEnabled] is false whenever any send is in
 * flight (only one runs at a time), so the other rows' buttons go inert instead of silently
 * swallowing taps.
 */
@Composable
fun DeviceSendCard(
    name: String,
    isWindows: Boolean,
    isPaired: Boolean,
    isSending: Boolean,
    sendEnabled: Boolean = true,
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
                // In flight: the capsule stays put (the row must not reflow mid-send) but goes inert
                // and swaps to the "Sending" label. textPrimary reads as white on the dark theme, as
                // specified, without vanishing on the light one the way a literal Color.White would.
                isSending -> CapsuleAction(
                    label = stringResource(R.string.action_sending),
                    labelColor = colors.textPrimary,
                    enabled = false,
                    onClick = {},
                )
                // Paired → "Send", unpaired → "Pair": both the same capsule as DeviceCardRow's Pair.
                isPaired -> CapsuleAction(
                    label = stringResource(R.string.action_send),
                    labelColor = colors.info,
                    enabled = sendEnabled,
                    onClick = onSend,
                )
                // Pair stays live even mid-send: it just opens PairingActivity and has nothing to
                // do with the transfer holding the send lock.
                else -> CapsuleAction(
                    label = stringResource(R.string.action_pair),
                    labelColor = colors.green,
                    enabled = true,
                    onClick = onPair,
                )
            }
        }
    }
}

/** Transparent, hairline-outlined capsule — same design as DeviceCardRow's Pair/Unpair button. */
@Composable
private fun CapsuleAction(
    label: String,
    labelColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(dimens.radiusPill),
        border = BorderStroke(dimens.hairlineWidth, colors.divider),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
        ),
        contentPadding = PaddingValues(horizontal = dimens.spaceLg, vertical = dimens.spaceSm),
    ) {
        // Colour the label explicitly rather than leaning on the button's content colour, so the
        // disabled "Sending" state stays fully legible instead of fading to M3's 38% alpha.
        Text(label, color = labelColor, fontWeight = FontWeight.Bold)
    }
}
