package com.joseph.clipboard.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.joseph.clipboard.windows.ClipboardAppController
import com.joseph.clipboard.windows.Screen
import com.joseph.clipboard.windows.network.DiscoveredDevice
import com.joseph.clipboard.windows.storage.SecurityVault
import com.joseph.clipboard.windows.theme.Dimens
import com.joseph.clipboard.windows.theme.LocalExtraColors
import java.net.InetAddress
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

@Composable
fun SystemStatusScreen(appController: ClipboardAppController, onTutorialChainNext: (Screen) -> Unit = {}) {
    val deviceName by appController.deviceName.collectAsState()
    val isDiscoverable by appController.isDiscoverable.collectAsState()
    val discoveredDevices by appController.discoveredDevices.collectAsState()
    val trustedNodes by appController.trustedNodes.collectAsState()
    val currentLanguage by com.joseph.clipboard.windows.storage.TranslationManager.currentLanguageState
    
    // Simple local IP retrieval
    val localIp = remember { 
        try {
            InetAddress.getLocalHost().hostAddress
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    var showTutorial by remember { mutableStateOf(false) }
    var showPairedDialog by remember { mutableStateOf(false) }
    val tutorialBounds = remember { mutableStateMapOf<String, Rect>() }

    val focusManager = LocalFocusManager.current
    val extra = LocalExtraColors.current
    var localDeviceName by remember { mutableStateOf(deviceName) }
    
    // Sync external changes to local state
    LaunchedEffect(deviceName) {
        localDeviceName = deviceName
    }
    
    // 5-second debounce
    LaunchedEffect(localDeviceName) {
        if (localDeviceName != deviceName) {
            delay(5000)
            // H-q: validate before saving so a blank/whitespace name is never persisted,
            // and do not clearFocus() here — clearing focus mid-edit interrupts the user.
            if (localDeviceName.isNotBlank()) {
                appController.updateDeviceName(localDeviceName.trim())
            }
        }
    }

    // Honest transient "scanning" indicator: shown briefly on entry and after each manual rescan,
    // instead of a spinner that ran forever regardless of state. Discovery itself is continuous.
    var scanTick by remember { mutableStateOf(0) }
    var isScanning by remember { mutableStateOf(true) }
    LaunchedEffect(scanTick) {
        isScanning = true
        delay(2500)
        isScanning = false
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colors.background)
        .pointerInput(Unit) {
            detectTapGestures(onTap = { focusManager.clearFocus() })
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimens.spaceXl)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.spaceHuge, bottom = Dimens.spaceXl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colors.primary,
                    modifier = Modifier.padding(end = Dimens.spaceLg).size(Dimens.iconLg)
                )
                Text(
                    com.joseph.clipboard.windows.storage.TranslationManager.getString("system_status_title").takeIf { it != "system_status_title" } ?: "System Status",
                    style = MaterialTheme.typography.h5,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.weight(1f)
                )
                HowToUseChip(onClick = { showTutorial = true })
            }

            // At-a-glance overview — gives the screen a focal hierarchy instead of a flat stack, and
            // relocates the port from the header subtitle into an intentional, labelled tile.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.spaceLg),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd)
            ) {
                BentoTile(
                    value = discoveredDevices.size.toString(),
                    label = "Nearby",
                    icon = Icons.Filled.Wifi,
                    modifier = Modifier.weight(1f)
                )
                BentoTile(
                    value = trustedNodes.size.toString(),
                    label = "Paired",
                    icon = Icons.Filled.Link,
                    onClick = { showPairedDialog = true },
                    modifier = Modifier.weight(1f)
                )
                BentoTile(
                    value = "Active",
                    label = "Status",
                    icon = Icons.Filled.Public,
                    valueColor = extra.greenText,
                    modifier = Modifier.weight(1f)
                )
            }

            // Device Name — OutlinedTextField with floating label + green focus (matches Android exactly).
            // Placed ABOVE the Discoverable toggle to match the Android pairing screen order.
            Box(modifier = Modifier.onGloballyPositioned { coords ->
                tutorialBounds["deviceNameField"] = coords.boundsInWindow()
            }) {
                OutlinedTextField(
                    value = localDeviceName,
                    onValueChange = { localDeviceName = it },
                    label = {
                        Text(com.joseph.clipboard.windows.storage.TranslationManager.getString("device_name_label").takeIf { it != "device_name_label" } ?: "Device Name")
                    },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                        appController.updateDeviceName(localDeviceName)
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.spaceLg, bottom = Dimens.spaceLg)
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused && localDeviceName != deviceName) {
                                appController.updateDeviceName(localDeviceName)
                            }
                        }
                        .onKeyEvent {
                            if (it.key == Key.Enter && it.type == KeyEventType.KeyUp) {
                                appController.updateDeviceName(localDeviceName)
                                focusManager.clearFocus()
                                true
                            } else {
                                false
                            }
                        },
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = MaterialTheme.colors.primary,
                        unfocusedBorderColor = extra.divider,
                        cursorColor = MaterialTheme.colors.primary,
                        focusedLabelColor = MaterialTheme.colors.primary
                    )
                )
            } // end deviceNameField Box

            // Discoverable toggle — shared GlassToggleRow (white thumb + theme-green track), light & dark.
            Box(modifier = Modifier
                .padding(top = Dimens.spaceSm, bottom = Dimens.spaceLg)
                .onGloballyPositioned { coords ->
                    tutorialBounds["discoverableSwitch"] = coords.boundsInWindow()
                }) {
                GlassToggleRow(
                    label = com.joseph.clipboard.windows.storage.TranslationManager.getString("discoverable_to_other_devices").takeIf { it != "discoverable_to_other_devices" } ?: "Discoverable to other devices",
                    checked = isDiscoverable,
                    onCheckedChange = { appController.updateDiscoverable(it) }
                )
            } // end discoverableSwitch Box

            GlassDivider(modifier = Modifier.padding(bottom = Dimens.spaceLg))

            // Paired and Nearby Devices
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.spaceLg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    com.joseph.clipboard.windows.storage.TranslationManager.getString("select_trusted_recipients_title"),
                    style = MaterialTheme.typography.h6,
                    color = MaterialTheme.colors.onBackground
                )
                if (isScanning) {
                    Spacer(modifier = Modifier.width(Dimens.spaceLg))
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colors.primary,
                        strokeWidth = 2.dp
                    )
                }
            }

            // Device list area — a centered empty-state when nothing is found (so the text can't be
            // clipped by the scroll viewport), or a scrollable list of device cards.
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = Dimens.spaceLg)) {
                if (discoveredDevices.isEmpty()) {
                    // Still the tutorial anchor: the spotlight + placeholder fake device land here.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coords -> tutorialBounds["deviceCard"] = coords.boundsInWindow() },
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyState(
                            icon = Icons.Filled.Wifi,
                            title = com.joseph.clipboard.windows.storage.TranslationManager.getString("no_devices_found").takeIf { it != "no_devices_found" } ?: "No devices found yet",
                            subtitle = com.joseph.clipboard.windows.storage.TranslationManager.getString("no_devices_found_hint").takeIf { it != "no_devices_found_hint" } ?: "Make sure the other device is on the same Wi-Fi or hotspot and is discoverable.",
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)
                    ) {
                        discoveredDevices.forEachIndexed { index, device ->
                            val isPaired = trustedNodes.contains(device.ip)
                            Box(modifier = Modifier.onGloballyPositioned { coords ->
                                if (index == 0) tutorialBounds["deviceCard"] = coords.boundsInWindow()
                            }) {
                                DiscoveredDeviceItem(device = device, isPaired = isPaired, appController = appController)
                            }
                        }
                    }
                }
            }

            // Rescan — hairline pill (matches the app's CapsuleButton language) instead of a lone
            // borderless circle; also re-triggers the transient "scanning" indicator above.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Dimens.radiusPill))
                        .background(MaterialTheme.colors.surface)
                        .border(Dimens.hairlineWidth, extra.divider, RoundedCornerShape(Dimens.radiusPill))
                        .clickable {
                            appController.clearNetworkCache()
                            scanTick++
                        }
                        .padding(horizontal = Dimens.spaceXl, vertical = Dimens.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colors.onSurface,
                        modifier = Modifier.size(Dimens.iconSm)
                    )
                    Text(
                        com.joseph.clipboard.windows.storage.TranslationManager.getString("action_rescan").takeIf { it != "action_rescan" } ?: "Rescan",
                        style = MaterialTheme.typography.button,
                        color = MaterialTheme.colors.onSurface
                    )
                }
            }
        }

        if (showPairedDialog) {
            PairedDevicesDialog(
                trustedNodes = trustedNodes,
                discoveredDevices = discoveredDevices,
                onDismiss = { showPairedDialog = false }
            )
        }

        if (showTutorial) {
            val devicesEmpty = discoveredDevices.isEmpty()
            TutorialOverlay(
                steps = listOf(
                    TutorialStep("deviceNameField", "Tap here to change your device's display name for easier identification."),
                    TutorialStep(
                        "discoverableSwitch",
                        "Turn this off when you don't want to be discoverable by other devices.",
                        // Tighten onto the discoverable row (the capture box includes extra padding)
                        spotlightVerticalInset = 5.dp
                    ),
                    TutorialStep(
                        "deviceCard",
                        "Tap 'Pair' on a nearby device to pair with it and start transferring files.",
                        placeholderContent = if (devicesEmpty) {
                            { TutorialPlaceholderDevice() }
                        } else null,
                        minSpotlightHeight = if (devicesEmpty) 100.dp else null,
                        spotlightYOffset = if (devicesEmpty) (-5).dp else 0.dp,
                        placeholderYOffset = if (devicesEmpty) 15.dp else 0.dp
                    ),
                    TutorialStep(
                        "deviceCard",
                        "Tap the Pair button to connect to this device and start sharing files.",
                        hideSkipButton = true,
                        placeholderContent = if (devicesEmpty) {
                            { TutorialPlaceholderDevice(showPairButton = true) }
                        } else null,
                        minSpotlightHeight = if (devicesEmpty) 100.dp else null,
                        spotlightYOffset = if (devicesEmpty) (-5).dp else 0.dp,
                        placeholderYOffset = if (devicesEmpty) 15.dp else 0.dp
                    )
                ),
                totalSteps = 4,
                stepOffset = 0,
                boundsMap = tutorialBounds,
                onDismiss = { showTutorial = false },
                onComplete = {
                    showTutorial = false
                }
            )
        }
    }
}

@Composable
fun DiscoveredDeviceItem(device: DiscoveredDevice, isPaired: Boolean, appController: ClipboardAppController) {
    val extra = LocalExtraColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(Dimens.radiusLg))
            .padding(Dimens.spaceLg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Smartphone,
            contentDescription = null,
            tint = MaterialTheme.colors.primary,
            modifier = Modifier.padding(end = Dimens.spaceSm).size(Dimens.headerIcon)
        )
        Spacer(modifier = Modifier.width(Dimens.spaceLg))
        Column(modifier = Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.subtitle1, color = MaterialTheme.colors.onSurface)
            Text("${device.os}", style = MaterialTheme.typography.caption, color = extra.textSecondary)
        }

        // Transparent, hairline-outlined pill — green "Pair" / red "Unpair" label (Android parity).
        val pairText = com.joseph.clipboard.windows.storage.TranslationManager.getString("action_pair")
        val unpairText = com.joseph.clipboard.windows.storage.TranslationManager.getString("action_unpair")
        CapsuleButton(
            text = if (isPaired) unpairText else pairText,
            onClick = {
                if (!isPaired) appController.initiatePairing(device.ip) else appController.unpairDevice(device.ip)
            },
            contentColor = if (isPaired) extra.errorText else extra.greenText,
            fill = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.widthIn(min = 96.dp)
        )
    }
}

/**
 * Popup listing every paired (trusted) device with a live online/offline dot. Names come from the
 * discovery list when the peer is currently reachable; otherwise the IP is shown as a fallback.
 */
@Composable
private fun PairedDevicesDialog(
    trustedNodes: Set<String>,
    discoveredDevices: List<DiscoveredDevice>,
    onDismiss: () -> Unit,
) {
    val extra = LocalExtraColors.current
    val rows = trustedNodes
        .map { ip ->
            val d = discoveredDevices.find { it.ip == ip }
            (d?.name ?: SecurityVault.getPairedDeviceName(ip) ?: ip) to (d != null)
        }
        .sortedByDescending { it.second }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colors.background,
            modifier = Modifier.width(380.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(Dimens.spaceXl)) {
                Text(
                    "Paired Devices",
                    style = MaterialTheme.typography.h6,
                    color = MaterialTheme.colors.onBackground
                )
                Spacer(Modifier.height(Dimens.spaceLg))
                if (rows.isEmpty()) {
                    Text(
                        "No paired devices yet.",
                        style = MaterialTheme.typography.body2,
                        color = extra.textSecondary
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)
                    ) {
                        rows.forEach { (name, online) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (online) extra.green else extra.divider)
                                )
                                Spacer(Modifier.width(Dimens.spaceMd))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.subtitle1, color = MaterialTheme.colors.onSurface)
                                    Text(
                                        if (online) "Online" else "Offline",
                                        style = MaterialTheme.typography.caption,
                                        color = if (online) extra.greenText else extra.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Dimens.spaceXl))
                AppButton(text = "Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
