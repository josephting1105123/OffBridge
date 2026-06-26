package com.joseph.clipboard.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Message
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.joseph.clipboard.windows.network.DiscoveredDevice
import com.joseph.clipboard.windows.theme.Dimens
import com.joseph.clipboard.windows.theme.GreenPrimary
import kotlinx.coroutines.delay
import java.io.File

/**
 * Second step of the "Connected Devices" send flow: read-only preview of the
 * chosen files (drag/delete gestures off), the paired-device list with
 * checkboxes and a bottom send button enabled iff ≥ 1 recipient is checked.
 *
 * While the dialog mounts and the first device poll has not yet returned,
 * shimmer placeholder rows stand in for the device list (min 250 ms to avoid
 * a flash-of-skeleton).
 */
@Composable
fun SendToDevicesDialog(
    files: List<File>,
    devices: List<DiscoveredDevice>,
    onSend: (recipients: List<DiscoveredDevice>) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedRecipients = remember { mutableStateListOf<DiscoveredDevice>() }
    var showSkeleton by remember { mutableStateOf(true) }

    // Skeletons display for a minimum of 250 ms; after that they yield to the
    // real list as soon as a device appears, or to the empty state after 3 s.
    LaunchedEffect(Unit) {
        delay(250)
        if (devices.isNotEmpty()) {
            showSkeleton = false
        } else {
            delay(2750)
            showSkeleton = false
        }
    }
    LaunchedEffect(devices.size) {
        if (devices.isNotEmpty()) {
            delay(250)
            showSkeleton = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colors.background,
            elevation = Dimens.elevModal,
            modifier = Modifier.width(500.dp).height(600.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(Dimens.spaceXl)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.spaceLg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.offset(x = (-12).dp)) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colors.onBackground)
                    }
                    Text(
                        "Send to Devices",
                        style = MaterialTheme.typography.h5,
                        color = MaterialTheme.colors.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Read-only file previews
                Text(
                    com.joseph.clipboard.windows.storage.TranslationManager.getString("selected_files_title").takeIf { it != "selected_files_title" } ?: "Selected Files",
                    style = MaterialTheme.typography.subtitle1,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.padding(bottom = Dimens.spaceSm)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                    modifier = Modifier.height(110.dp).padding(bottom = Dimens.spaceLg)
                ) {
                    items(files.size) { index ->
                        ReadOnlyFileItem(files[index])
                    }
                }

                // Device list
                Text(
                    com.joseph.clipboard.windows.storage.TranslationManager.getString("select_trusted_recipients_title").takeIf { it != "select_trusted_recipients_title" } ?: "Connected Devices",
                    style = MaterialTheme.typography.subtitle1,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.padding(bottom = Dimens.spaceSm)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)
                ) {
                    if (showSkeleton && devices.isEmpty()) {
                        items(2) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(Dimens.radiusSm))
                                    .shimmer()
                            )
                        }
                    } else if (devices.isEmpty()) {
                        item {
                            Text(
                                "Scanning for devices…",
                                style = MaterialTheme.typography.body1,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                                modifier = Modifier.padding(Dimens.spaceSm)
                            )
                        }
                    } else {
                        items(devices.size) { index ->
                            val device = devices[index]
                            val isSelected = selectedRecipients.contains(device)
                            DeviceListItem(
                                device = device,
                                isSelected = isSelected,
                                onToggle = {
                                    if (it) selectedRecipients.add(device) else selectedRecipients.remove(device)
                                }
                            )
                        }
                    }
                }

                // Bottom send button — enabled iff ≥ 1 recipient
                val canSend = selectedRecipients.isNotEmpty()
                Button(
                    onClick = {
                        if (canSend) {
                            onSend(selectedRecipients.toList())
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = Dimens.spaceLg),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.primary,
                        disabledBackgroundColor = androidx.compose.ui.graphics.Color.Gray
                    ),
                    shape = RoundedCornerShape(Dimens.radiusSm)
                ) {
                    Text(
                        com.joseph.clipboard.windows.storage.TranslationManager.getString("action_send").takeIf { it != "action_send" } ?: "Send",
                        style = MaterialTheme.typography.button,
                        color = if (canSend) MaterialTheme.colors.onPrimary else androidx.compose.ui.graphics.Color.LightGray
                    )
                }
            }
        }
    }
}

/** [SelectedFileItem] without the tap-to-select and long-press-drag gestures. */
@Composable
private fun ReadOnlyFileItem(file: File) {
    Box(
        modifier = Modifier
            .size(100.dp, 100.dp)
            .clip(RoundedCornerShape(Dimens.radiusSm))
            .background(MaterialTheme.colors.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Dimens.spaceSm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val isTextItem = file.name.matches(Regex("Text \\d+\\.txt"))
            Icon(
                if (isTextItem) Icons.Filled.Message else Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(Dimens.spaceSm))
            Text(
                file.nameWithoutExtension,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
