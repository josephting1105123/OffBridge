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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.TransferItem
import com.EntropicSoftwareLab.ClipboardShare.sendvia.SendViaChoice
import com.EntropicSoftwareLab.ClipboardShare.ui.components.DesignModeToggle
import com.EntropicSoftwareLab.ClipboardShare.ui.components.DeviceSendCard
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

@Composable
fun LiteSendScreen(
    selectedFiles: List<TransferItem>,
    onSelectFile: () -> Unit,
    onSelectMedia: () -> Unit,
    onSelectText: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onPreviewFile: (TransferItem) -> Unit,
    onSendViaChoose: (String) -> Unit
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    var currentScreen by remember { mutableStateOf(1) }
    var sendScope by remember { mutableStateOf(0) } // 0 = One Device, 1 = Multiple Device

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(dimens.spaceXl)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 72.dp, bottom = dimens.spaceXl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentScreen == 2) {
                    Box(
                        modifier = Modifier
                            .size(dimens.touchTarget)
                            .clip(RoundedCornerShape(dimens.radiusMd))
                            .clickable { currentScreen = 1 },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.action_close),
                            tint = colors.textPrimary,
                            modifier = Modifier.size(dimens.iconMd)
                        )
                    }
                    Spacer(Modifier.width(dimens.spaceSm))
                }
                Icon(
                    painter = painterResource(R.drawable.ic_hdr_send),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg)
                )
                Text(
                    if (currentScreen == 1) stringResource(R.string.lite_send_files) else stringResource(R.string.lite_send_select_method_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (currentScreen == 1) {
                // Screen 1: File Selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd)
                ) {
                    TypeButton(R.drawable.ic_file, stringResource(R.string.type_file), onSelectFile, Modifier.weight(1f))
                    TypeButton(R.drawable.ic_camera, stringResource(R.string.type_media), onSelectMedia, Modifier.weight(1f))
                    TypeButton(R.drawable.ic_hdr_history, stringResource(R.string.type_text), onSelectText, Modifier.weight(1f))
                }

                Text(
                    stringResource(R.string.lite_selected_files_count, selectedFiles.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = dimens.spaceXxl, bottom = dimens.spaceMd)
                )

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
                    verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)
                ) {
                    itemsIndexed(
                        selectedFiles,
                        key = { index, item -> "${index}_${item.uri}_${item.relativePath}_${item.isFolder}" },
                    ) { index, item ->
                        FilePreviewCard(item = item, onClick = { onPreviewFile(item) }, onRemove = { onRemoveFile(index) })
                    }
                }

                Spacer(Modifier.height(dimens.spaceLg))

                val hasFiles = selectedFiles.isNotEmpty()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(dimens.radiusLg))
                        .background(if (hasFiles) colors.green else colors.card)
                        .clickable(enabled = hasFiles) { currentScreen = 2 },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.action_next),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (hasFiles) Color.Black else colors.textSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                // Screen 2: Network Choice
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(dimens.spaceLg)
                ) {
                    OptionCard(
                        title = stringResource(R.string.net_same_network),
                        onClick = {
                            // Launch SendToDevicesFragment, passing the sendScope so it knows if it's multiple
                            onSendViaChoose("devices_lite_$sendScope")
                        }
                    )
                    OptionCard(
                        title = stringResource(R.string.net_different_network),
                        onClick = { onSendViaChoose(SendViaChoice.QR) }
                    )
                }

                DesignModeToggle(
                    options = listOf(stringResource(R.string.send_scope_one_device), stringResource(R.string.send_scope_multiple_device)),
                    selectedIndex = sendScope,
                    bothGreen = true,
                    onSelect = { sendScope = it }
                )
            }
        }
    }
}

@Composable
fun LiteSendToScreen(
    deviceName: String,
    devices: List<SendDeviceUi>,
    isMultiple: Boolean,
    sendingIp: String?,
    selectedIps: Set<String>,
    onToggleDevice: (String) -> Unit,
    onSendToDevice: (String) -> Unit,
    onSendMultiple: () -> Unit,
    onPairDevice: (ip: String, name: String) -> Unit,
    onDone: () -> Unit
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
                .padding(dimens.spaceXl)
        ) {
            Text(
                "${stringResource(R.string.device_name_label)} $deviceName",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 72.dp, bottom = dimens.spaceXl)
            )

            Text(
                stringResource(R.string.lite_select_devices_label),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = dimens.spaceMd)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)
            ) {
                items(devices) { device ->
                    if (isMultiple) {
                        val isSelected = selectedIps.contains(device.ip)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppThemeTokens.dimens.radiusMd))
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) AppThemeTokens.colors.green else Color.Transparent,
                                    shape = RoundedCornerShape(AppThemeTokens.dimens.radiusMd)
                                )
                                .clickable { onToggleDevice(device.ip) }
                        ) {
                            DeviceCardRow(
                                name = device.name,
                                isWindows = device.isWindows,
                                isPaired = device.isPaired,
                                showButton = false
                            )
                        }
                    } else {
                        // ONE mode: Tri-state DeviceSendCard
                        DeviceSendCard(
                            name = device.name,
                            isWindows = device.isWindows,
                            isPaired = device.isPaired,
                            isSending = sendingIp == device.ip,
                            onPair = { onPairDevice(device.ip, device.name) },
                            onSend = { onSendToDevice(device.ip) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(dimens.spaceLg))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(dimens.radiusLg))
                    .background(colors.green)
                    .clickable {
                        if (isMultiple) onSendMultiple() else onDone()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isMultiple) stringResource(R.string.action_send) else stringResource(R.string.action_done),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun OptionCard(title: String, onClick: () -> Unit) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimens.buttonHeight * 2f)
            .clip(RoundedCornerShape(dimens.radiusLg))
            .background(colors.card)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}
