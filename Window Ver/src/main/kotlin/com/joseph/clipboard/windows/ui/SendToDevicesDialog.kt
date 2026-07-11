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
import androidx.compose.material.icons.filled.Smartphone
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
import androidx.compose.ui.graphics.Color
import com.joseph.clipboard.windows.storage.TranslationManager

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
    onDismiss: () -> Unit,
    onGoToPairing: () -> Unit
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

    // Prune vanished devices from the selection so Send can't target an offline node
    // while the empty state says nothing is connected (Android prunes the same way).
    LaunchedEffect(devices) {
        selectedRecipients.retainAll(devices.toSet())
    }

    // Debounced pairing mode. Flipping the bottom button to the always-enabled "Go to
    // Pairing" the instant the device list drains raced the user's click: one expired
    // discovery entry while the pointer was already over "Send" landed the click on
    // onGoToPairing(), which navigates away and disposes the picked files. A drained
    // list must stay empty for 2 s (peers broadcast every ~3 s) before the button
    // converts; during the gap it is a disabled "Send", so a stray click is a no-op.
    // A list that was never populated (dialog opened with nothing online) converts as
    // soon as the skeletons clear, as before — there is no click to race.
    var sawDevices by remember { mutableStateOf(false) }
    var pairingMode by remember { mutableStateOf(false) }
    LaunchedEffect(devices.isEmpty(), showSkeleton) {
        if (devices.isNotEmpty()) {
            sawDevices = true
            pairingMode = false
        } else if (!showSkeleton) {
            if (sawDevices) delay(2000)
            pairingMode = true
        }
    }

    BackdropEffect()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colors.background,
            elevation = Dimens.elevModal,
            modifier = Modifier.width(500.dp).height(600.dp).modalPopIn()
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
                    TranslationManager.getString("selected_files_title").takeIf { it != "selected_files_title" } ?: "Selected Files",
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

                // Device list header — discovery keeps polling while the list is empty, so the
                // spinner says "still looking" next to the empty state (System Status pairing).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = Dimens.spaceSm)
                ) {
                    Text(
                        TranslationManager.getString("select_trusted_recipients_title").takeIf { it != "select_trusted_recipients_title" } ?: "Connected Devices",
                        style = MaterialTheme.typography.subtitle1,
                        color = MaterialTheme.colors.onBackground
                    )
                    if (devices.isEmpty() && !showSkeleton) {
                        Spacer(modifier = Modifier.width(Dimens.spaceLg))
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colors.primary,
                            strokeWidth = 2.dp
                        )
                    }
                }
                // Three exclusive states — first-poll skeletons, empty state, device list — all on
                // a Send-Files-style tray surface (rounded surface box, Android parity).
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dimens.radiusLg))
                        .background(MaterialTheme.colors.surface)
                        .padding(Dimens.spaceSm)
                ) {
                    when {
                        showSkeleton && devices.isEmpty() -> Column(
                            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)
                        ) {
                            repeat(2) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(Dimens.radiusSm))
                                        .shimmer()
                                )
                            }
                        }
                        devices.isEmpty() -> Box(
                            // System-Status-style empty state: no paired device is online. The "go pair"
                            // action lives on the bottom button (it becomes "Go to Pairing"), not here.
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            EmptyState(
                                icon = Icons.Filled.Smartphone,
                                title = TranslationManager.getString("send_no_devices_title").takeIf { it != "send_no_devices_title" } ?: "No connected devices",
                                subtitle = TranslationManager.getString("send_no_devices_hint").takeIf { it != "send_no_devices_hint" } ?: "Pair with a nearby device to send files to it."
                            )
                        }
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)
                        ) {
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
                }

                // Bottom button. With no device connected (empty state, debounced above) it becomes
                // "Go to Pairing"; otherwise it's the send action, enabled iff ≥ 1 recipient is checked.
                val isPairingMode = pairingMode && devices.isEmpty()
                val canSend = selectedRecipients.isNotEmpty()
                val buttonEnabled = if (isPairingMode) true else canSend
                Button(
                    onClick = {
                        if (isPairingMode) {
                            onGoToPairing()
                        } else if (canSend) {
                            onSend(selectedRecipients.toList())
                        }
                    },
                    enabled = buttonEnabled,
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = Dimens.spaceLg),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.primary,
                        disabledBackgroundColor = Color.Gray
                    ),
                    shape = RoundedCornerShape(Dimens.radiusSm)
                ) {
                    val label = if (isPairingMode) {
                        TranslationManager.getString("action_go_to_pairing").takeIf { it != "action_go_to_pairing" } ?: "Go to Pairing"
                    } else {
                        TranslationManager.getString("action_send").takeIf { it != "action_send" } ?: "Send"
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.button,
                        color = if (buttonEnabled) MaterialTheme.colors.onPrimary else Color.LightGray
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
