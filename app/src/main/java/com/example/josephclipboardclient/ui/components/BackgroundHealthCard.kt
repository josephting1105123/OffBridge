package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.BackgroundIssue
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/**
 * Names the reason the background service won't stay up, with the button that fixes it.
 *
 * Deliberately a warning, not an error: none of these states break the app in front of the user,
 * they break it an hour later on the other device — so the card explains the consequence rather
 * than just flagging a setting. Renders nothing when [issues] is empty; a permanently-present
 * "everything is fine" panel would train people to stop reading it.
 *
 * [vendorLabel] fills the OEM row's heading ("Xiaomi controls autostart") and is only read for
 * [BackgroundIssue.OEM_AUTOSTART_UNKNOWN], which is never emitted without one.
 */
@Composable
fun BackgroundHealthCard(
    issues: List<BackgroundIssue>,
    vendorLabel: String?,
    onFix: (BackgroundIssue) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (issues.isEmpty()) return
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.radiusLg))
            .background(colors.surfaceElevated)
            .border(BorderStroke(1.dp, colors.warning), RoundedCornerShape(dimens.radiusLg))
            .padding(dimens.spaceLg),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceMd),
    ) {
        Text(
            stringResource(R.string.bg_health_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.warning,
        )

        issues.forEach { issue ->
            val heading = when (issue) {
                BackgroundIssue.BATTERY_OPTIMIZED -> stringResource(R.string.bg_health_battery_heading)
                BackgroundIssue.OEM_AUTOSTART_UNKNOWN ->
                    stringResource(R.string.bg_health_oem_heading, vendorLabel.orEmpty())
                BackgroundIssue.NOTIFICATIONS_BLOCKED -> stringResource(R.string.bg_health_notifications_heading)
                BackgroundIssue.SERVICE_STOPPED -> stringResource(R.string.bg_health_stopped_heading)
            }
            val body = when (issue) {
                BackgroundIssue.BATTERY_OPTIMIZED -> stringResource(R.string.bg_health_battery_body)
                BackgroundIssue.OEM_AUTOSTART_UNKNOWN -> stringResource(R.string.bg_health_oem_body)
                BackgroundIssue.NOTIFICATIONS_BLOCKED -> stringResource(R.string.bg_health_notifications_body)
                BackgroundIssue.SERVICE_STOPPED -> stringResource(R.string.bg_health_stopped_body)
            }
            val action = when (issue) {
                BackgroundIssue.BATTERY_OPTIMIZED -> stringResource(R.string.bg_health_battery_action)
                BackgroundIssue.OEM_AUTOSTART_UNKNOWN -> stringResource(R.string.bg_health_oem_action)
                BackgroundIssue.NOTIFICATIONS_BLOCKED -> stringResource(R.string.bg_health_notifications_action)
                BackgroundIssue.SERVICE_STOPPED -> stringResource(R.string.bg_health_stopped_action)
            }

            Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
                Text(
                    heading,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onFix(issue) },
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.greenText),
                    ) {
                        Text(action, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
