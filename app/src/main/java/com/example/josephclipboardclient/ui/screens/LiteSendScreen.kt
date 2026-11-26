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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.TransferItem
import com.EntropicSoftwareLab.ClipboardShare.ui.components.DeviceSendCard
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

@Composable
fun LiteSendScreen(
    selectedFiles: List<TransferItem>,
    totalSizeBytes: Long,
    onSelectFile: () -> Unit,
    onSelectMedia: () -> Unit,
    onSelectText: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onMoveFile: (Int, Int) -> Unit,
    onPreviewFile: (TransferItem) -> Unit,
    selectionMode: Boolean,
    selectedIndices: Set<Int>,
    onToggleSelectMode: (Boolean) -> Unit,
    onToggleSelection: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onSendViaChoose: (String) -> Unit
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 72.dp, bottom = dimens.spaceXl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_hdr_send),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg)
                )
                Text(
                    stringResource(R.string.lite_send_files),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd)
            ) {
                TypeButton(R.drawable.ic_file, stringResource(R.string.type_file), onSelectFile, Modifier.weight(1f))
                TypeButton(R.drawable.ic_media, stringResource(R.string.type_media), onSelectMedia, Modifier.weight(1f))
                TypeButton(R.drawable.ic_clipboard_light, stringResource(R.string.type_text), onSelectText, Modifier.weight(1f))
            }

            // "Selected files" section — identical to Pro (title + total size + Select, or the
            // selection action bar; grid on the rounded bottom-sheet tray with drag-reorder).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = dimens.spaceXl, bottom = dimens.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    Text(
                        stringResource(R.string.format_selected_count, selectedIndices.size),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val allSelected = selectedFiles.isNotEmpty() && selectedIndices.size == selectedFiles.size
                    CapsuleButton(stringResource(R.string.action_select_all), filled = allSelected, fill = colors.green, textColor = if (allSelected) Color.Black else colors.textPrimary, onClick = onSelectAll)
                    Spacer(Modifier.width(dimens.spaceSm))
                    CapsuleButton(stringResource(R.string.action_delete), filled = true, enabled = selectedIndices.isNotEmpty(), fill = colors.error, textColor = Color.White, onClick = onDeleteSelected)
                    Spacer(Modifier.width(dimens.spaceSm))
                    CapsuleButton(stringResource(R.string.action_cancel), filled = false, fill = colors.card, textColor = colors.textPrimary, onClick = { onToggleSelectMode(false) })
                } else {
                    Text(
                        stringResource(R.string.selected_files_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(dimens.spaceSm))
                    TotalSizeBox(totalSizeBytes)
                    Spacer(Modifier.weight(1f))
                    CapsuleButton(stringResource(R.string.action_edit), filled = false, enabled = selectedFiles.isNotEmpty(), fill = colors.card, textColor = colors.textPrimary, onClick = { onToggleSelectMode(true) })
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(dimens.radiusLg))
                    .background(colors.bottomSheet)
                    .padding(dimens.spaceSm),
            ) {
                if (selectedFiles.isEmpty()) {
                    Text(
                        stringResource(R.string.selected_files_empty_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    SelectedFilesGrid(
                        selectedFiles = selectedFiles,
                        selectionMode = selectionMode,
                        selectedIndices = selectedIndices,
                        onRemoveFile = onRemoveFile,
                        onMoveFile = onMoveFile,
                        onPreviewFile = onPreviewFile,
                        onToggleSelection = onToggleSelection,
                    )
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
                    .clickable(enabled = hasFiles) { onSendViaChoose("devices_lite_0") },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.action_next),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hasFiles) Color.Black else colors.textSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun LiteSendToScreen(
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    devices: List<SendDeviceUi>,
    loading: Boolean,
    sendingIp: String?,
    onSendToDevice: (String) -> Unit,
    onPairDevice: (ip: String, name: String) -> Unit,
    onSendViaQr: () -> Unit,
    onGoToPairing: () -> Unit,
    onDone: () -> Unit
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val focusManager = LocalFocusManager.current
    // Local field state (initialised once) so a background device-list refresh can't reset the
    // cursor mid-edit; each change is persisted through onDeviceNameChange.
    var nameField by remember { mutableStateOf(deviceName) }

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
            // Header — matches the other Lite screens (send icon + "Send Files" title + QR action).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 72.dp, bottom = dimens.spaceXl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_hdr_send),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg)
                )
                Text(
                    stringResource(R.string.lite_send_files),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                val qrEnabled = sendingIp == null
                val qrContentColor = if (qrEnabled) colors.textPrimary else colors.textSecondary
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(dimens.radiusLg))
                        .background(colors.card)
                        .clickable(enabled = qrEnabled) { onSendViaQr() }
                        .padding(horizontal = dimens.spaceMd, vertical = dimens.spaceSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_code),
                        contentDescription = null,
                        tint = qrContentColor,
                        modifier = Modifier.size(dimens.iconMd)
                    )
                    Spacer(Modifier.width(dimens.spaceSm))
                    Text(
                        stringResource(R.string.offgrid_send_title_qr),
                        style = MaterialTheme.typography.labelLarge,
                        color = qrContentColor,
                        maxLines = 1
                    )
                }
            }

            // Editable device-name field — same design as System Status (Pro) "Device name".
            OutlinedTextField(
                value = nameField,
                onValueChange = { nameField = it; onDeviceNameChange(it) },
                label = { Text(stringResource(R.string.device_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = dimens.spaceXl),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.green,
                    cursorColor = colors.green,
                    focusedLabelColor = colors.green,
                )
            )

            Text(
                stringResource(R.string.lite_select_devices_label),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = dimens.spaceMd)
            )

            // Device list on a tray surface, matching the Pro selected-files grid.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(dimens.radiusLg))
                    .background(colors.bottomSheet)
                    .padding(dimens.spaceSm)
            ) {
                when {
                    devices.isEmpty() && loading -> Unit
                    devices.isEmpty() && sendingIp == null -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(dimens.spaceLg),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                stringResource(R.string.status_no_devices_found),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(dimens.spaceLg))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(dimens.radiusLg))
                                    .background(colors.green)
                                    .clickable { onGoToPairing() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.action_go_to_pairing),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)
                        ) {
                            items(devices) { device ->
                                // Per-device tri-state: Pair (unpaired) / Send (paired, idle) / Sending… (in flight).
                                // Only one send runs at a time, so while any device is in flight every row's
                                // action is disabled — the screen stays put either way, exited via Done.
                                DeviceSendCard(
                                    name = device.name,
                                    isWindows = device.isWindows,
                                    isPaired = device.isPaired,
                                    isSending = sendingIp == device.ip,
                                    sendEnabled = sendingIp == null,
                                    onPair = { onPairDevice(device.ip, device.name) },
                                    onSend = { onSendToDevice(device.ip) }
                                )
                            }
                        }
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
                    .clickable { onDone() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.action_done),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
