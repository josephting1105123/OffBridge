package com.joseph.clipboard.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Message
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Desktop
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import com.joseph.clipboard.windows.ClipboardAppController
import com.joseph.clipboard.windows.Screen
import com.joseph.clipboard.windows.network.DiscoveredDevice
import com.joseph.clipboard.windows.os.FileDialogHelper
import com.joseph.clipboard.windows.theme.GreenPrimary
import com.joseph.clipboard.windows.theme.Dimens
import java.io.File

@Composable
fun FileTransferScreen(
    appController: ClipboardAppController,
    onTutorialChainNext: (Screen) -> Unit = {},
    tutorialTrigger: Int = 0,
    onNavigateToPairing: () -> Unit = {},
    onTransferHistoryVisibility: (Boolean) -> Unit = {}
) {
    var selectAllFiles by remember { mutableStateOf(false) }
    var showSendVia by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showSendToDevices by remember { mutableStateOf(false) }
    var showTransferHistory by remember { mutableStateOf(false) }
    var showTransferHistoryInTutorialMode by remember { mutableStateOf(false) }
    var preselectedSessionId by remember { mutableStateOf<String?>(null) }
    var showTextDialog by remember { mutableStateOf(false) }
    var textToSend by remember { mutableStateOf("") }
    var textCounter by remember { mutableStateOf(1) }
    var showTutorial by remember { mutableStateOf(false) }
    var tutorialForceSend by remember { mutableStateOf(false) }
    val tutorialBounds = remember { mutableStateMapOf<String, Rect>() }
    val currentLanguage by com.joseph.clipboard.windows.storage.TranslationManager.currentLanguageState

    LaunchedEffect(tutorialTrigger) {
        if (tutorialTrigger > 0) showTutorial = true
    }
    
    // The global receiving pill hides while the transfer-history dialog is on screen. The flag
    // lives here and this screen is disposed on nav-away, so report every change AND reset on
    // dispose — otherwise a stale "visible" would keep the pill hidden from another screen.
    DisposableEffect(showTransferHistory) {
        onTransferHistoryVisibility(showTransferHistory)
        onDispose { onTransferHistoryVisibility(false) }
    }

    // Issue 4: Observe navigation event from controller (auto-open transfer history on accept)
    val navigateToSession by appController.navigateToTransferSession.collectAsState()
    LaunchedEffect(navigateToSession) {
        navigateToSession?.let { sessionId ->
            preselectedSessionId = sessionId
            showTransferHistory = true
            appController.clearNavigateToTransferSession()
        }
    }
    
    val discoveredDevices by appController.discoveredDevices.collectAsState()
    val trustedNodes by appController.trustedNodes.collectAsState()
    val pairedDevices = discoveredDevices.filter { trustedNodes.contains(it.ip) }
    
    val coroutineScope = rememberCoroutineScope()
    
    var chosenFiles by remember { mutableStateOf(listOf<File>()) }
    val selectedFiles = remember { mutableStateListOf<File>() }

    // Total bytes queued (folders walked recursively), computed off the UI thread per list change.
    val totalSizeBytes by produceState(0L, chosenFiles) {
        value = withContext(Dispatchers.IO) { totalSizeOf(chosenFiles) }
    }

    // #1: Keep the "select all" checkbox in sync with the actual selection.
    // chosenFiles can change after select-all (add/remove/reorder/delete),
    // so recompute selectAllFiles from the current state instead of leaving
    // a stale `true` that no longer reflects what's selected.
    LaunchedEffect(chosenFiles, selectedFiles.toList()) {
        selectAllFiles = chosenFiles.isNotEmpty() && selectedFiles.containsAll(chosenFiles)
    }

    val sendFilesTitle = com.joseph.clipboard.windows.storage.TranslationManager.getString("send_files_title")
    val fileText = com.joseph.clipboard.windows.storage.TranslationManager.getString("type_file")
    val folderText = com.joseph.clipboard.windows.storage.TranslationManager.getString("type_folder")
    val selectedFilesTitle = com.joseph.clipboard.windows.storage.TranslationManager.getString("selected_files_title")
    val selectAllText = com.joseph.clipboard.windows.storage.TranslationManager.getString("action_select_all")
    val sendText = com.joseph.clipboard.windows.storage.TranslationManager.getString("action_send")
    val cancelText = com.joseph.clipboard.windows.storage.TranslationManager.getString("action_cancel")

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp) // Leave space for sticky bottom button
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.spaceXl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Upload,
                    contentDescription = null,
                    tint = GreenPrimary,
                    modifier = Modifier.padding(end = Dimens.spaceLg).size(28.dp)
                )
                Text(
                    sendFilesTitle,
                    style = MaterialTheme.typography.h5,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.weight(1f)
                )
                HowToUseChip(onClick = { showTutorial = true })
                // Capture bounds on the IconButton itself (after its start padding) so the square
                // spotlight centres on the icon, not on the padded wrapper.
                IconButton(
                    onClick = { showTransferHistory = true },
                    modifier = Modifier
                        .padding(start = Dimens.spaceSm)
                        .onGloballyPositioned { coords ->
                            tutorialBounds["historyButton"] = coords.boundsInWindow()
                        }
                ) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = "Transfer History",
                        tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            // Selection Panel
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.spaceXl),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                modifier = Modifier.padding(bottom = Dimens.spaceXl).onGloballyPositioned { coords ->
                    tutorialBounds["selectionButtons"] = coords.boundsInWindow()
                }
            ) {
                item {
                    SelectionButton(
                        title = fileText, 
                        icon = Icons.Filled.Description,
                        onClick = {
                            // H-r: run the native file picker off the Compose UI thread
                            // (like the folder picker below) so the blocking dialog
                            // doesn't freeze the UI while it's open.
                            coroutineScope.launch(Dispatchers.IO) {
                                val newFiles = FileDialogHelper.openMultipleFilesPicker("Select Files")
                                if (newFiles.isNotEmpty()) {
                                    chosenFiles = chosenFiles + newFiles
                                }
                            }
                        }
                    )
                }
                item { 
                    SelectionButton(
                        title = folderText, 
                        icon = Icons.Filled.Folder,
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                val newFolder = FileDialogHelper.openDirectoryPicker("Select Folder")
                                if (newFolder != null) {
                                    chosenFiles = chosenFiles + newFolder
                                }
                            }
                        }
                    ) 
                }
                item {
                    SelectionButton(
                        title = "Text", 
                        icon = Icons.Filled.Message,
                        onClick = {
                            showTextDialog = true
                        }
                    )
                }
            }

            // Selected Files Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.spaceXl, vertical = Dimens.spaceSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    selectedFilesTitle,
                    style = MaterialTheme.typography.subtitle1,
                    color = MaterialTheme.colors.onBackground
                )
                Spacer(modifier = Modifier.width(Dimens.spaceSm))
                // Pill box with the queued total ("0 MB" when nothing is queued) — matches Android.
                Box(
                    modifier = Modifier
                        .glassSurface(RoundedCornerShape(Dimens.radiusPill))
                        .padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXxs)
                ) {
                    Text(
                        formatTotalSize(totalSizeBytes),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Checkbox(
                    checked = selectAllFiles,
                    onCheckedChange = { isChecked ->
                        selectAllFiles = isChecked
                        selectedFiles.clear()
                        if (isChecked) {
                            selectedFiles.addAll(chosenFiles)
                        }
                    },
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colors.primary)
                )
                Text(selectAllText, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.body1)
            }

            // Selected Files List
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.spaceXl),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                modifier = Modifier.height(120.dp).padding(bottom = Dimens.spaceXl).onGloballyPositioned { coords ->
                    tutorialBounds["selectedFilesRow"] = coords.boundsInWindow()
                }
            ) {
                if (chosenFiles.isEmpty()) {
                    item {
                        Text(com.joseph.clipboard.windows.storage.TranslationManager.getString("no_files_selected_hint").takeIf { it != "no_files_selected_hint" } ?: "No files selected. Browse to add files.", style = MaterialTheme.typography.body1, color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
                    }
                } else {
                    items(chosenFiles.size) { index ->
                        val file = chosenFiles[index]
                        val isSelected = selectedFiles.contains(file)
                        SelectedFileItem(
                            file = file,
                            isSelected = isSelected,
                            onToggle = { 
                                if (it) selectedFiles.add(file) else selectedFiles.remove(file) 
                            },
                            onMove = { direction ->
                                val newIndex = index + direction
                                if (newIndex in chosenFiles.indices) {
                                    val list = chosenFiles.toMutableList()
                                    val temp = list[index]
                                    list[index] = list[newIndex]
                                    list[newIndex] = temp
                                    chosenFiles = list
                                }
                            }
                        )
                    }
                }
            }

            // Invisible anchor below the files row: tutorial steps 5–7 render
            // their fake Send Via sheet / device card / QR teaser here.
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Dimens.spaceXl)
                .onGloballyPositioned { coords ->
                    tutorialBounds["deviceList"] = coords.boundsInWindow()
                })
        }

        // Sticky Bottom Send/Delete Button
        if (selectedFiles.isNotEmpty()) {
            AppDestructiveButton(
                text = "Delete Selected",
                onClick = {
                    chosenFiles = chosenFiles - selectedFiles.toSet()
                    selectedFiles.clear()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(Dimens.spaceXl)
            )
        } else {
            val canSend = chosenFiles.isNotEmpty()
            // Tutorial step 4 forces the green "active" look without enabling real sends
            val sendActive = canSend || tutorialForceSend
            Box(modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(Dimens.spaceXl)
                .height(64.dp)
                .onGloballyPositioned { coords ->
                    tutorialBounds["sendButton"] = coords.boundsInWindow()
                }
            ) {
                // Green when active; grey (surface) when disabled — matches the Android Send button.
                // Theme-aware green so light & dark mode match the Android client.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(Dimens.radiusLg))
                        .background(if (sendActive) MaterialTheme.colors.primary else MaterialTheme.colors.surface)
                        .clickable(enabled = canSend) { showSendVia = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        sendText,
                        style = MaterialTheme.typography.subtitle1,
                        color = if (sendActive) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        if (showTutorial) {
            TutorialOverlay(
                steps = listOf(
                    TutorialStep(
                        "selectionButtons",
                        "Select the file type to send: File, Folder, or Text.",
                        // The selection row's content padding sits INSIDE its captured bounds, so the
                        // spotlight's left edge bled off-window. Trim both edges by the content padding
                        // (24dp) for a symmetric ~12dp rounded inset on the left AND right.
                        spotlightLeftTrim = 24.dp,
                        spotlightWidthTrim = 24.dp
                    ),
                    TutorialStep(
                        "selectedFilesRow",
                        "Your selected files will appear here.",
                        // When real files are queued, don't overlay fakes — spotlight the real row.
                        placeholderContent = if (chosenFiles.isEmpty()) {
                            { TutorialPlaceholderFiles(selected = false, alignStart = true) }
                        } else null,
                        // Demo (no files): a fixed-width spotlight pinned to the LEFT, where real files
                        // appear. leftTrim 36 = content padding (24) + spotlight pad (12), so the band's
                        // left edge lands on the first real card. With real files: hug the real row.
                        fixedSpotlightWidth = if (chosenFiles.isEmpty()) 348.dp else null,
                        spotlightLeftTrim = if (chosenFiles.isEmpty()) 36.dp else 24.dp,
                        spotlightWidthTrim = if (chosenFiles.isEmpty()) 0.dp else 24.dp,
                        spotlightBottomTrimFraction = 0.1f
                    ),
                    TutorialStep(
                        "selectedFilesRow",
                        "Click a file to select it, then delete the selected files. Double-click a file to preview it.",
                        placeholderContent = if (chosenFiles.isEmpty()) {
                            { TutorialPlaceholderFiles(selected = true, alignStart = true) }
                        } else null,
                        fixedSpotlightWidth = if (chosenFiles.isEmpty()) 348.dp else null,
                        spotlightLeftTrim = if (chosenFiles.isEmpty()) 36.dp else 24.dp,
                        spotlightWidthTrim = if (chosenFiles.isEmpty()) 0.dp else 24.dp,
                        spotlightBottomTrimFraction = 0.1f
                    ),
                    // Step 4: Send now opens the "Send Via" chooser
                    TutorialStep(
                        "sendButton",
                        "With files selected, tap Send to choose how to send.",
                        onShow = { tutorialForceSend = true },
                        onHide = { tutorialForceSend = false }
                    ),
                    // Step 5: Send Via chooser — drawn as a CENTERED modal replica (matches the real
                    // Windows SendViaDialog) instead of a placeholder pinned over the device-list anchor.
                    // QR is desktop's off-grid path (no NFC).
                    TutorialStep(
                        "deviceList",
                        "Connected Devices uses your Wi-Fi network. QR lets a nearby Android phone scan and receive — no pairing needed.",
                        fullScreenContent = { TutorialReplicaSendVia() }
                    ),
                    // Step 6: replay the Send-Via sheet, tap "Connected Devices", reveal the dialog replica.
                    TutorialStep(
                        "deviceList",
                        "Pick one or more paired devices, then hit Send. If no devices are connected, tap Go to Pairing to pair one first.",
                        fullScreenContent = { TutorialRevealConnectedDevices() }
                    ),
                    // Step 7: replay the Send-Via sheet, tap "QR", reveal the QR dialog replica (a nearby
                    // Android phone scans it; no NFC on desktop).
                    TutorialStep(
                        "deviceList",
                        "Choose QR to show a code — a nearby Android phone scans it and receives your files over the local network.",
                        fullScreenContent = { TutorialRevealQr() }
                    ),
                    // Step 8: square spotlight centered on the round history icon button, shrunk 10%.
                    TutorialStep("historyButton", "Tap here to view your transfer history and track progress.", buttonLabel = "Next", spotlightSquare = true, spotlightScale = 0.9f)
                ),
                totalSteps = 13,
                stepOffset = 0,
                boundsMap = tutorialBounds,
                onDismiss = { showTutorial = false },
                onComplete = {
                    showTutorial = false
                    showTransferHistoryInTutorialMode = true
                    showTransferHistory = true
                }
            )
        }
    }

    if (showSendVia) {
        SendViaDialog(
            totalSizeBytes = totalSizeBytes,
            onConnectedDevices = {
                showSendVia = false
                showSendToDevices = true
            },
            onQr = {
                showSendVia = false
                showQr = true
            },
            onDismiss = { showSendVia = false }
        )
    }

    if (showQr) {
        ShowQrDialog(
            files = chosenFiles,
            appController = appController,
            onDismiss = { showQr = false }
        )
    }

    if (showSendToDevices) {
        SendToDevicesDialog(
            files = chosenFiles,
            devices = pairedDevices,
            onSend = { recipients ->
                var firstSessionId: String? = null
                for (recipient in recipients) {
                    val sessionId = appController.sendFiles(chosenFiles, recipient.ip)
                    if (firstSessionId == null) firstSessionId = sessionId
                }
                chosenFiles = emptyList()
                selectedFiles.clear()
                showSendToDevices = false
                // Jump straight to the (first) outgoing record's history detail so the sender
                // watches its progress there. Additional recipients are one Back away.
                if (firstSessionId != null) {
                    preselectedSessionId = firstSessionId
                    showTransferHistory = true
                }
            },
            onDismiss = { showSendToDevices = false },
            // Close the dialog and jump to the Pairing screen. Leaving this screen disposes
            // its remember state (Main.kt renders screens in a Crossfade), so the file queue
            // does not survive the trip — the same cost as any nav-rail switch today.
            onGoToPairing = {
                showSendToDevices = false
                onNavigateToPairing()
            }
        )
    }

    val transferHistory by appController.transferHistory.collectAsState()
    if (showTransferHistory) {
        TransferHistoryDialog(
            historyList = transferHistory,
            initialSelectedSessionId = preselectedSessionId,
            tutorialMode = showTransferHistoryInTutorialMode,
            onDelete = { sessionIds -> appController.deleteMultipleTransferRecords(sessionIds) },
            onCancelTransfer = { sessionId -> appController.cancelTransfer(sessionId) },
            isDeviceConnected = { deviceName -> discoveredDevices.any { it.name == deviceName } },
            onResumeTransfer = { record ->
                if (record.direction == "incoming") {
                    appController.resumeIncomingFileTransfer(record.sessionId)
                }
                // Outgoing resume must be initiated from the receiving device.
            },
            onDismiss = {
                showTransferHistory = false
                showTransferHistoryInTutorialMode = false
                preselectedSessionId = null
            }
        )
    }

    if (showTextDialog) {
        BackdropEffect()
        androidx.compose.ui.window.Dialog(onDismissRequest = { showTextDialog = false }) {
            Surface(
                shape = RoundedCornerShape(Dimens.radiusLg),
                color = MaterialTheme.colors.background,
                elevation = Dimens.elevModal,
                modifier = Modifier.width(360.dp).padding(Dimens.spaceLg).modalPopIn()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(Dimens.spaceXl)
                ) {
                    Text(
                        "Add Text",
                        style = MaterialTheme.typography.h6,
                        color = MaterialTheme.colors.onBackground
                    )
                    Spacer(modifier = Modifier.height(Dimens.spaceLg))
                    OutlinedTextField(
                        value = textToSend,
                        onValueChange = { textToSend = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Type something to send...") },
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = MaterialTheme.colors.onBackground,
                            cursorColor = GreenPrimary,
                            focusedBorderColor = GreenPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(Dimens.spaceXl))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showTextDialog = false; textToSend = "" }) {
                            Text("Cancel", color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f))
                        }
                        Spacer(modifier = Modifier.width(Dimens.spaceSm))
                        Button(
                            onClick = { 
                                // Create a temp .txt file and add it to chosenFiles
                                try {
                                    val tempDir = File(System.getProperty("java.io.tmpdir"), "ClipboardShareTexts")
                                    if (!tempDir.exists()) tempDir.mkdirs()
                                    val textFileName = "Text ${textCounter}.txt"
                                    val textFile = File(tempDir, textFileName)
                                    textFile.writeText(textToSend)
                                    chosenFiles = chosenFiles + textFile
                                    textCounter++
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                showTextDialog = false
                                textToSend = ""
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
                            enabled = textToSend.isNotBlank()
                        ) {
                            Text("Add", color = MaterialTheme.colors.onPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SelectionButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .size(120.dp, 80.dp)
            .glassSurface(RoundedCornerShape(Dimens.radiusMd))
            .clickable { onClick() }
            .padding(Dimens.spaceSm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.size(Dimens.iconLg)
        )
        Spacer(modifier = Modifier.height(Dimens.spaceSm))
        Text(title, style = MaterialTheme.typography.body1, color = MaterialTheme.colors.onSurface)
    }
}

@Composable
fun DeviceListItem(device: DiscoveredDevice, isSelected: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(Dimens.radiusSm))
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colors.primary else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(Dimens.radiusSm)
            )
            .clickable { onToggle(!isSelected) }
            .padding(Dimens.spaceLg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Smartphone,
            contentDescription = null,
            tint = GreenPrimary,
            modifier = Modifier.padding(end = Dimens.spaceSm).size(Dimens.iconMd)
        )
        Spacer(modifier = Modifier.width(Dimens.spaceLg))
        Column(modifier = Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.subtitle1, color = MaterialTheme.colors.onSurface)
            Text(device.os, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle(it) },
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colors.primary)
        )
    }
}

@Composable
fun SelectedFileItem(file: File, isSelected: Boolean, onToggle: (Boolean) -> Unit, onMove: (Int) -> Unit) {
    var offsetX by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // Long-press lift: the card shakes (settles to a slight tilt), pops out (scale + shadow) and follows
    // the finger. Shake is one-shot on pickup; the lift tracks [dragging].
    val shake = remember { Animatable(0f) }
    LaunchedEffect(dragging) {
        if (dragging) {
            shake.snapTo(0f)
            shake.animateTo(-5f, tween(60))
            shake.animateTo(5f, tween(80))
            shake.animateTo(-2.5f, tween(70))
            shake.animateTo(2.5f, tween(70))
        } else {
            shake.animateTo(0f, tween(120))
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .zIndex(if (dragging || offsetX != 0f) 1f else 0f)
            .graphicsLayer {
                this.shape = RoundedCornerShape(Dimens.radiusSm)
                val s = if (dragging) 1.08f else 1f
                scaleX = s
                scaleY = s
                rotationZ = shake.value
                shadowElevation = if (dragging) 12.dp.toPx() else 0f
            }
            .size(100.dp, 100.dp)
            .pointerInput(isSelected) {
                detectTapGestures(
                    onTap = { onToggle(!isSelected) },
                    onDoubleTap = {
                        try {
                            if (Desktop.isDesktopSupported()) {
                                Desktop.getDesktop().open(file)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offsetX = 0f; dragging = true },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        // Calculate if we moved past an item (100.dp item + 12.dp spacing)
                        val itemWidth = 112.dp.toPx()
                        if (offsetX > itemWidth) {
                            onMove(1)
                            offsetX -= itemWidth
                        } else if (offsetX < -itemWidth) {
                            onMove(-1)
                            offsetX += itemWidth
                        }
                    },
                    onDragEnd = { offsetX = 0f; dragging = false },
                    onDragCancel = { offsetX = 0f; dragging = false }
                )
            }
            .glassSurface(RoundedCornerShape(Dimens.radiusSm)),
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
                Text(file.nameWithoutExtension, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface)
            }

            if (isSelected) {
                Box(modifier = Modifier.fillMaxSize().background(GreenPrimary.copy(alpha = 0.2f)))
            }
    }
}

