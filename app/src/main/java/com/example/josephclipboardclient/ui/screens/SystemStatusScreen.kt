package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.PolishDialogWindow
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.components.BentoTile
import com.EntropicSoftwareLab.ClipboardShare.ui.components.EmptyState
import com.EntropicSoftwareLab.ClipboardShare.ui.components.HowToUseChip
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialOverlay
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialPlaceholderDevice
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialStep
import com.EntropicSoftwareLab.ClipboardShare.ui.modal.backdropSource
import com.EntropicSoftwareLab.ClipboardShare.ui.modal.rememberBackdropState
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.rememberTutorialAnchors
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.tutorialAnchor

/** A discovered device row for the Pairing screen. */
data class DeviceUi(val name: String, val ip: String, val isWindows: Boolean, val isPaired: Boolean)

/** A trusted/paired device shown in the "Paired" popup, with its current online state. */
data class PairedDeviceUi(val name: String, val isOnline: Boolean)

/**
 * Compose rewrite of the Pairing / System Status tab (SystemStatusFragment). The fragment keeps the
 * polling engine + pairing logic and feeds this screen state + callbacks. Device cards use AppColors
 * so they match the real card in light AND dark (item 4c).
 */
@Composable
fun SystemStatusScreen(
    isConnected: Boolean,
    statusText: String,
    discoverable: Boolean,
    onDiscoverableChange: (Boolean) -> Unit,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    devices: List<DeviceUi>,
    pairedDevices: List<PairedDeviceUi> = emptyList(),
    scanning: Boolean,
    onPair: (DeviceUi) -> Unit,
    onUnpair: (DeviceUi) -> Unit,
    onActionButton: () -> Unit,
    showTutorial: Boolean,
    onShowTutorial: () -> Unit,
    onTutorialDismiss: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val anchors = rememberTutorialAnchors()
    val backdrop = rememberBackdropState()
    val focusManager = LocalFocusManager.current
    var showPairedDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
    ) {
        Column(modifier = Modifier.fillMaxSize().backdropSource(backdrop).padding(dimens.spaceXl)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 72.dp, bottom = dimens.spaceXl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_hdr_status),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg),
                )
                Text(
                    stringResource(R.string.system_status_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                HowToUseChip(onClick = onShowTutorial)
            }

            // At-a-glance overview — gives the screen a focal hierarchy instead of a flat stack, and
            // surfaces the port as an intentional tile (desktop parity).
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = dimens.spaceLg),
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd),
            ) {
                BentoTile(
                    label = stringResource(R.string.bento_nearby),
                    value = devices.size.toString(),
                    icon = ImageVector.vectorResource(R.drawable.ic_device),
                    modifier = Modifier.weight(1f),
                )
                BentoTile(
                    label = stringResource(R.string.bento_paired),
                    value = pairedDevices.size.toString(),
                    icon = ImageVector.vectorResource(R.drawable.ic_link),
                    onClick = { showPairedDialog = true },
                    modifier = Modifier.weight(1f),
                )
                BentoTile(
                    label = stringResource(R.string.bento_status),
                    value = stringResource(if (isConnected) R.string.status_value_active else R.string.status_value_offline),
                    icon = ImageVector.vectorResource(R.drawable.ic_hdr_status),
                    accent = if (isConnected) colors.green else colors.error,
                    valueColor = if (isConnected) colors.greenText else colors.error,
                    modifier = Modifier.weight(1f),
                )
            }

            // Device name
            OutlinedTextField(
                value = deviceName,
                onValueChange = onDeviceNameChange,
                label = { Text(stringResource(R.string.device_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = dimens.spaceMd)
                    .tutorialAnchor(anchors, "deviceName"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.green,
                    cursorColor = colors.green,
                    focusedLabelColor = colors.green,
                ),
            )

            // Discoverable toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = dimens.spaceLg)
                    .tutorialAnchor(anchors, "discoverable"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.discoverable_to_devices),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = discoverable,
                    onCheckedChange = onDiscoverableChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.green),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = dimens.spaceLg)
                    .height(dimens.hairlineWidth)
                    .background(colors.divider),
            )

            // Paired & nearby devices
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = dimens.spaceLg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.paired_and_nearby_devices),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                if (isConnected && scanning) {
                    CircularProgressIndicator(
                        color = colors.green,
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(start = dimens.spaceLg).size(20.dp),
                    )
                }
            }

            // Device list / disconnected state
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().tutorialAnchor(anchors, "deviceCard"),
                contentAlignment = Alignment.Center,
            ) {
                if (!isConnected) {
                    DisconnectedState(colors)
                } else if (devices.isEmpty()) {
                    // Real empty-state instead of a blank gap that reads as broken.
                    EmptyState(
                        icon = ImageVector.vectorResource(R.drawable.ic_device),
                        title = stringResource(R.string.status_no_devices_found),
                        subtitle = stringResource(R.string.status_no_devices_found_hint),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(dimens.spaceMd),
                    ) {
                        devices.forEach { device ->
                            DeviceCardRow(
                                name = device.name,
                                isWindows = device.isWindows,
                                isPaired = device.isPaired,
                                onPair = { onPair(device) },
                                onUnpair = { onUnpair(device) },
                            )
                        }
                    }
                }
            }

            // Action (rescan when connected, reboot when not) — hairline pill with icon + label,
            // matching the app's button language instead of a lone borderless circle.
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = dimens.spaceLg, bottom = dimens.spaceXxl)
                    .clip(RoundedCornerShape(dimens.radiusPill))
                    .background(colors.card)
                    .border(dimens.hairlineWidth, colors.divider, RoundedCornerShape(dimens.radiusPill))
                    .clickable { onActionButton() }
                    .padding(horizontal = dimens.spaceXl, vertical = dimens.spaceMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
            ) {
                Icon(
                    painter = painterResource(if (isConnected) R.drawable.ic_retry else R.drawable.ic_power),
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(dimens.iconSm),
                )
                Text(
                    stringResource(if (isConnected) R.string.action_rescan else R.string.action_reboot),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (showTutorial) {
            // A real device card is already on screen — spotlight it directly instead of overlaying a
            // fake one (item 2). The fake card is only for the empty / disconnected teaching state.
            val hasRealDeviceCard = devices.isNotEmpty()
            val steps = listOf(
                TutorialStep(
                    targetKey = "discoverable",
                    text = stringResource(R.string.status_tutorial_discoverable_toggle),
                    spotlightVerticalInset = 5.dp,
                ),
                TutorialStep(targetKey = "deviceName", text = stringResource(R.string.status_tutorial_device_name)),
                TutorialStep(
                    targetKey = "deviceCard",
                    text = stringResource(R.string.status_tutorial_pair_nearby),
                    minSpotlightHeight = 120.dp,
                    // Top-align the fake card so it lands on the real first-card position (the deviceCard
                    // anchor is a weight(1f) box, so a centred card floats far too low).
                    placeholderAlignment = Alignment.TopCenter,
                    placeholderYOffset = 12.dp,
                    placeholderContent = if (hasRealDeviceCard) null else {
                        { TutorialPlaceholderDevice(showPairButton = false) }
                    },
                ),
                TutorialStep(
                    targetKey = "deviceCard",
                    text = stringResource(R.string.status_tutorial_pair_button),
                    minSpotlightHeight = 120.dp,
                    hideSkipButton = true,
                    placeholderAlignment = Alignment.TopCenter,
                    placeholderYOffset = 12.dp,
                    placeholderContent = if (hasRealDeviceCard) null else {
                        { TutorialPlaceholderDevice(showPairButton = true) }
                    },
                ),
            )
            TutorialOverlay(
                steps = steps,
                totalSteps = steps.size,
                boundsMap = anchors,
                onDismiss = onTutorialDismiss,
                onComplete = onTutorialDismiss,
                backdrop = backdrop,
            )
        }

        if (showPairedDialog) {
            PairedDevicesDialog(pairedDevices = pairedDevices, onDismiss = { showPairedDialog = false })
        }
    }
}

/** Popup listing every paired (trusted) device with a live online/offline dot. */
@Composable
private fun PairedDevicesDialog(pairedDevices: List<PairedDeviceUi>, onDismiss: () -> Unit) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val rows = pairedDevices.sortedByDescending { it.isOnline }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done), color = colors.greenText, fontWeight = FontWeight.Bold)
            }
        },
        title = { Text(stringResource(R.string.paired_devices_title), color = colors.textPrimary) },
        text = {
            // Inside a dialog slot so LocalView resolves to the dialog's own window.
            PolishDialogWindow()
            if (rows.isEmpty()) {
                Text(stringResource(R.string.paired_devices_empty), color = colors.textSecondary)
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(dimens.spaceMd),
                ) {
                    rows.forEach { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (device.isOnline) colors.green else colors.divider),
                            )
                            Column(modifier = Modifier.padding(start = dimens.spaceMd).weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                                Text(
                                    stringResource(if (device.isOnline) R.string.status_online else R.string.status_offline),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (device.isOnline) colors.greenText else colors.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = colors.card,
    )
}

@Composable
private fun DisconnectedState(colors: com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppColors) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = colors.error,
            modifier = Modifier.size(48.dp).padding(bottom = 16.dp),
        )
        Text(
            stringResource(R.string.status_network_disconnected),
            color = colors.error,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.press_button_to_reboot),
            color = colors.textSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private val DEVICE_BUTTON_WIDTH = 108.dp
private val DEVICE_BUTTON_HEIGHT = 40.dp

/**
 * Shared device-card row used by the real pairing list AND the tutorial's fake card, so both render
 * at exactly the same size (item 3a). [showButton] is false only for the tutorial's "introduce the
 * card" step; the button footprint is reserved unconditionally so the card keeps its height either
 * way. Pair and Unpair share one transparent, light-grey-outlined design (item 3b) — green text for
 * Pair, red for Unpair — so the two buttons are the same design and size.
 */
@Composable
fun DeviceCardRow(
    name: String,
    isWindows: Boolean,
    isPaired: Boolean,
    showButton: Boolean = true,
    onPair: () -> Unit = {},
    onUnpair: () -> Unit = {},
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(if (isWindows) R.drawable.ic_computer else R.drawable.ic_device),
            contentDescription = null,
            tint = colors.green,
            modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg),
        )
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.size(width = DEVICE_BUTTON_WIDTH, height = DEVICE_BUTTON_HEIGHT)) {
            if (showButton) {
                if (isPaired) {
                    DeviceActionButton(stringResource(R.string.action_unpair), colors.error, onUnpair)
                } else {
                    DeviceActionButton(stringResource(R.string.action_pair), colors.green, onPair)
                }
            }
        }
    }
}

/** Transparent, light-grey-outlined action button shared by Pair (green text) and Unpair (red text). */
@Composable
private fun DeviceActionButton(label: String, textColor: Color, onClick: () -> Unit) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        border = BorderStroke(dimens.hairlineWidth, colors.divider),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(horizontal = dimens.spaceSm),
    ) {
        Text(label, color = textColor, fontWeight = FontWeight.Bold)
    }
}
