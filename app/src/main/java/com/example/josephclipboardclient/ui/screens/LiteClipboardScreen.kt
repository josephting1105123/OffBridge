package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

@Composable
fun LiteClipboardScreen(
    history: List<String>,
    pairedDevices: List<SendDeviceUi>,
    acceptSync: Boolean,
    onAcceptSyncChange: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (String) -> Unit,
    onPair: (String) -> Unit,
    onUnpair: (String) -> Unit
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    var detailEntry by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(dimens.spaceXl)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 72.dp, bottom = dimens.spaceXl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_hdr_history),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg)
                )
                Text(
                    stringResource(R.string.lite_clipboard),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = acceptSync,
                    onCheckedChange = onAcceptSyncChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = colors.green
                    )
                )
            }

            // Recent Copies Tray
            Text(
                stringResource(R.string.recent_copies_tap_hint),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = dimens.spaceMd)
            )

            val minCardHeight = 90.dp
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // allow to share space with devices tray
                    .heightIn(min = minCardHeight * 1.5f),
                verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)
            ) {
                if (history.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.history_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(dimens.spaceMd)
                        )
                    }
                } else {
                    items(history, key = { it }) { entry ->
                        ClipboardCard(entry = entry, colors = colors, dimens = dimens, onOpen = { detailEntry = it }, onDelete = onDelete)
                    }
                }
            }

            Spacer(Modifier.height(dimens.spaceLg))

            // Connected Devices Tray
            Text(
                stringResource(R.string.connected_devices_label),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = dimens.spaceMd)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = minCardHeight * 2f),
                verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)
            ) {
                if (pairedDevices.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.paired_devices_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(dimens.spaceMd)
                        )
                    }
                } else {
                    items(pairedDevices) { device ->
                        DeviceCardRow(
                            name = device.name,
                            isWindows = device.isWindows,
                            isPaired = device.isPaired,
                            onPair = { onPair(device.ip) },
                            onUnpair = { onUnpair(device.ip) }
                        )
                    }
                }
            }
        }

        detailEntry?.let { entry ->
            ClipboardDetail(
                entry = entry,
                colors = colors,
                dimens = dimens,
                onBack = { detailEntry = null },
                onCopy = {
                    onCopy(entry)
                    detailEntry = null
                }
            )
        }
    }
}
