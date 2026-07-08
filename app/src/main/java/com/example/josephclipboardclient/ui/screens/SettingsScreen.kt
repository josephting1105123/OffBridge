package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppColors
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.Dimens

/**
 * Compose Settings tab. Pure presentation: all state (theme/language labels, save-location text,
 * the three toggles) is hoisted into [SettingsFragment], which keeps the SAF launcher, the
 * theme/language/privacy AlertDialogs and the hotspot creds logic. Mirrors the GENERAL / DEVICE /
 * OTHERS sections of the old XML layout.
 */
@Composable
fun SettingsScreen(
    themeLabel: String,
    languageLabel: String,
    saveLocationLabel: String,
    saveMediaLabel: String,
    hotspotAutoConnect: Boolean,
    acceptClipboard: Boolean,
    discoverable: Boolean,
    onChangeTheme: () -> Unit,
    onChangeLanguage: () -> Unit,
    onChangeSaveLocation: () -> Unit,
    onChangeSaveMedia: () -> Unit,
    onHotspotAutoConnectChange: (Boolean) -> Unit,
    onAcceptClipboardChange: (Boolean) -> Unit,
    onDiscoverableChange: (Boolean) -> Unit,
    onDonate: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onConnectionDiagnosis: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(dimens.spaceXl),
        ) {
            // Header: icon + title (no How-to-use chip on this screen, matching the old layout).
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 72.dp, bottom = dimens.spaceXl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_hdr_settings),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg),
                )
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }

            // ===================== GENERAL =====================
            SectionLabel(stringResource(R.string.settings_section_general), colors, dimens)

            SettingPillRow(
                label = stringResource(R.string.theme_setting),
                pillText = themeLabel,
                colors = colors,
                dimens = dimens,
                onClick = onChangeTheme,
            )
            SettingPillRow(
                label = stringResource(R.string.language_setting),
                pillText = languageLabel,
                colors = colors,
                dimens = dimens,
                onClick = onChangeLanguage,
            )
            SettingPillRow(
                label = stringResource(R.string.save_file_to_setting),
                pillText = saveLocationLabel,
                colors = colors,
                dimens = dimens,
                onClick = onChangeSaveLocation,
            )
            SettingPillRow(
                label = stringResource(R.string.save_media_to_setting),
                pillText = saveMediaLabel,
                colors = colors,
                dimens = dimens,
                onClick = onChangeSaveMedia,
                bottomPadding = dimens.spaceLg,
            )

            // History backup note
            Text(
                stringResource(R.string.settings_history_backup_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = dimens.spaceLg),
            )

            Divider(colors, dimens)

            // ===================== DEVICE =====================
            SectionLabel(stringResource(R.string.settings_section_device), colors, dimens)

            SettingSwitchRow(
                label = stringResource(R.string.settings_hotspot_label),
                checked = hotspotAutoConnect,
                onCheckedChange = onHotspotAutoConnectChange,
                colors = colors,
                dimens = dimens,
            )
            SettingSwitchRow(
                label = stringResource(R.string.accept_incoming_clipboard),
                checked = acceptClipboard,
                onCheckedChange = onAcceptClipboardChange,
                colors = colors,
                dimens = dimens,
            )
            SettingSwitchRow(
                label = stringResource(R.string.discoverable_to_devices),
                checked = discoverable,
                onCheckedChange = onDiscoverableChange,
                colors = colors,
                dimens = dimens,
            )

            Spacer(Modifier.height(dimens.spaceMd))
            Divider(colors, dimens)

            // ===================== OTHERS =====================
            SectionLabel(stringResource(R.string.settings_section_others), colors, dimens)

            SettingButtonRow(
                label = stringResource(R.string.support_setting),
                buttonText = stringResource(R.string.action_donate),
                buttonBackground = colors.green,
                buttonTextColor = Color.Black,
                colors = colors,
                dimens = dimens,
                onClick = onDonate,
            )
            SettingButtonRow(
                label = stringResource(R.string.dialog_title_privacy_policy),
                buttonText = stringResource(R.string.action_read_privacy_policy),
                buttonBackground = colors.card,
                buttonTextColor = colors.textPrimary,
                colors = colors,
                dimens = dimens,
                onClick = onPrivacyPolicy,
            )

            // ===================== DEBUGGING =====================
            Divider(colors, dimens)
            SectionLabel(stringResource(R.string.settings_section_debugging), colors, dimens)

            SettingButtonRow(
                label = stringResource(R.string.settings_connection_diagnosis),
                buttonText = stringResource(R.string.action_open),
                buttonBackground = colors.card,
                buttonTextColor = colors.textPrimary,
                colors = colors,
                dimens = dimens,
                bottomPadding = dimens.spaceXl,
                onClick = onConnectionDiagnosis,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, colors: AppColors, dimens: Dimens) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = colors.textSecondary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = dimens.spaceLg),
    )
}

@Composable
private fun Divider(colors: AppColors, dimens: Dimens) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimens.hairlineWidth)
            .background(colors.divider),
    )
    Spacer(Modifier.height(dimens.spaceLg))
}

/** Label on the left, a tappable rounded pill on the right (theme / language / save-location). */
@Composable
private fun SettingPillRow(
    label: String,
    pillText: String,
    colors: AppColors,
    dimens: Dimens,
    onClick: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = dimens.spaceMd,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .widthIn(min = 126.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(dimens.radiusSm))
                .background(colors.card)
                .clickable { onClick() }
                .padding(horizontal = dimens.spaceMd),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                pillText,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Label on the left, a coloured action button on the right (donate / privacy policy). */
@Composable
private fun SettingButtonRow(
    label: String,
    buttonText: String,
    buttonBackground: Color,
    buttonTextColor: Color,
    colors: AppColors,
    dimens: Dimens,
    onClick: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = dimens.spaceMd,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .widthIn(min = 126.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(dimens.radiusSm))
                .background(buttonBackground)
                .clickable { onClick() }
                .padding(horizontal = dimens.spaceMd),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                buttonText,
                style = MaterialTheme.typography.bodyMedium,
                color = buttonTextColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** Full-width switch row: label takes the slack, the Compose [Switch] sits on the trailing edge. */
@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    colors: AppColors,
    dimens: Dimens,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.touchTarget)
            // The whole row is one toggle target (Role.Switch) so TalkBack announces label + on/off
            // as a single control; the Switch below mirrors state with onCheckedChange = null.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(bottom = dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f).padding(end = dimens.spaceMd),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.green,
            ),
        )
    }
}
