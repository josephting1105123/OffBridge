package com.joseph.clipboard.windows.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.joseph.clipboard.windows.theme.GreenPrimary
import com.joseph.clipboard.windows.theme.Dimens
import com.joseph.clipboard.windows.theme.InfoColor
import com.joseph.clipboard.windows.theme.ErrorColor
import com.joseph.clipboard.windows.storage.SecurityVault
import java.awt.Desktop
import java.io.File

import com.joseph.clipboard.windows.TransferRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.joseph.clipboard.windows.storage.TranslationManager

@Composable
fun TransferHistoryDialog(
    historyList: List<TransferRecord>,
    initialSelectedSessionId: String? = null,
    tutorialMode: Boolean = false,
    onDelete: (Set<String>) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onResumeTransfer: (TransferRecord) -> Unit,
    isDeviceConnected: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedSessionId by remember { mutableStateOf(initialSelectedSessionId) }
    val selectedForDeletion = remember { mutableStateListOf<String>() }
    var currentFilter by remember { mutableStateOf("All") }
    val filterOptions = listOf("All", "Sent", "Received", "Completed", "Pending", "Cancelled")
    var showTutorialOverlay by remember { mutableStateOf(tutorialMode) }
    val tutorialBounds = remember { mutableStateMapOf<String, Rect>() }
    // Clicks outside the dialog window advance the tutorial instead of closing it
    var tutorialAdvanceRequest by remember { mutableStateOf(0) }

    // Fake records shown during the tutorial (mirrors Android's TransferHistoryBottomSheet)
    val fakeRecords = remember {
        listOf(
            TransferRecord(
                "tutorial_to", "Android Phone", "outgoing",
                listOf("file 1.mp4", "file 2.pdf", "file 3.jpg"),
                15L * 1024 * 1024, System.currentTimeMillis(), "paused",
                0, 3, listOf(8L * 1024 * 1024, 5L * 1024 * 1024, 2L * 1024 * 1024), 0L
            ),
            TransferRecord(
                "tutorial_from", "Android Phone", "incoming",
                listOf("file 1.mp4", "file 2.pdf", "file 3.jpg"),
                15L * 1024 * 1024, System.currentTimeMillis(), "completed",
                3, 3, listOf(8L * 1024 * 1024, 5L * 1024 * 1024, 2L * 1024 * 1024), 0L
            )
        )
    }
    val selectedItemDetails =
        (if (tutorialMode) historyList + fakeRecords else historyList).find { it.sessionId == selectedSessionId }

    BackdropEffect()
    Dialog(onDismissRequest = {
        if (showTutorialOverlay) {
            // Don't let outside-clicks kill the tutorial mid-flow — treat them as "Next"
            tutorialAdvanceRequest++
        } else {
            onDismiss()
        }
    }) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colors.background,
            elevation = Dimens.elevModal,
            modifier = Modifier.width(500.dp).height(600.dp).modalPopIn()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().padding(Dimens.spaceXl)) {
                    // Header
                if (isSelectionMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.spaceLg),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isSelectionMode = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = MaterialTheme.colors.onBackground)
                        }
                        Text(
                            "${selectedForDeletion.size} selected",
                            style = MaterialTheme.typography.subtitle1,
                            color = MaterialTheme.colors.onBackground,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = {
                                onDelete(selectedForDeletion.toSet())
                                selectedForDeletion.clear()
                                isSelectionMode = false
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Delete", style = MaterialTheme.typography.button, color = Color.White)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.spaceLg)
                            .onGloballyPositioned { coords ->
                                tutorialBounds["headerRow"] = coords.boundsInWindow()
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            TranslationManager.getString("title_transfer_history").takeIf { it != "title_transfer_history" } ?: "Transfer History",
                            style = MaterialTheme.typography.h6,
                            color = MaterialTheme.colors.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            // Step 11 spotlights the checkbox AND the "Select" label as one rectangle, so
                            // bounds are captured on this wrapping Row (after the trailing gap padding).
                            modifier = Modifier.padding(end = 18.dp) // Adjusted gap
                                .onGloballyPositioned { coords ->
                                    tutorialBounds["btnSelect"] = coords.boundsInWindow()
                                }
                        ) {
                            Checkbox(
                                checked = isSelectionMode,
                                onCheckedChange = {
                                    isSelectionMode = it
                                    if (!it) selectedForDeletion.clear()
                                },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colors.primary)
                            )
                            Text(TranslationManager.getString("action_select").takeIf { it != "action_select" } ?: "Select", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f))
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.onGloballyPositioned { coords -> 
                                tutorialBounds["btnHistoryClose"] = coords.boundsInWindow() 
                            }
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colors.onBackground)
                        }
                    }
                }

                // Filter Capsules
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Dimens.spaceMd)
                        .horizontalScroll(rememberScrollState())
                        .onGloballyPositioned { coords ->
                            tutorialBounds["filterRow"] = coords.boundsInWindow()
                        },
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)
                ) {
                    filterOptions.forEach { option ->
                        val isSelected = currentFilter == option
                        Surface(
                            shape = RoundedCornerShape(Dimens.radiusPill),
                            color = if (isSelected) InfoColor else MaterialTheme.colors.surface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Dimens.radiusPill))
                                .clickable { currentFilter = option }
                                .height(32.dp)
                                .onGloballyPositioned { coords ->
                                    if (option == "All") tutorialBounds["btnFilterAll"] = coords.boundsInWindow()
                                    if (option == "Cancelled") tutorialBounds["btnFilterCancelled"] = coords.boundsInWindow()
                                }
                        ) {
                            // Centered in the fixed 32.dp pill with no vertical padding —
                            // 13.sp text + 6.dp×2 padding exceeded 32.dp and clipped descenders.
                            Box(
                                modifier = Modifier.fillMaxHeight().padding(horizontal = Dimens.spaceLg),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = option,
                                    style = MaterialTheme.typography.body2,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.White else MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                val historyToShow = when (currentFilter) {
                    "Sent"      -> historyList.filter { it.direction == "outgoing" }
                    "Received"  -> historyList.filter { it.direction == "incoming" }
                    "Completed" -> historyList.filter { it.status == "completed" }
                    "Pending"   -> historyList.filter { it.status in listOf("pending", "transferring") }
                    "Cancelled" -> historyList.filter { it.status in listOf("cancelled", "failed", "declined") }
                    else        -> historyList
                }

                // The tutorial always replaces the visible list with the fake records (Android parity)
                val displayList = if (showTutorialOverlay) fakeRecords else historyToShow

                // List
                Box(modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        tutorialBounds["historyList"] = coords.boundsInWindow()
                    }
                ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)
                ) {
                    if (displayList.isEmpty()) {
                        item {
                            Text(TranslationManager.getString("no_transfer_history_yet").takeIf { it != "no_transfer_history_yet" } ?: "No transfer history yet.", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(Dimens.spaceLg))
                        }
                    } else {
                        items(displayList.size) { index ->
                            val record = displayList[index]
                            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(record.timestamp))
                            val title = if (record.fileNames.size == 1) record.fileNames[0] else "${record.fileNames.size} files"
                            val subtitle = "${if(record.direction == "incoming") "From" else "To"} ${record.deviceName} • $timeStr"
                            val statusColor = when (record.status) {
                                "completed" -> GreenPrimary
                                "transferring" -> InfoColor
                                "cancelled", "failed", "declined" -> ErrorColor
                                else -> MaterialTheme.colors.onBackground.copy(alpha = 0.7f)
                            }
                            // Icon glyph stays in code; the word comes from the translation file
                            // (mirrors the Android per-locale status_* strings).
                            fun tr(key: String, fallback: String): String =
                                TranslationManager.getString(key)
                                    .takeIf { it != key } ?: fallback
                            val statusLabel = when (record.status) {
                                "completed" -> "✓ " + tr("status_done_with_icon", "Done")
                                "transferring" -> "↕ " + tr("status_transferring", "Transferring")
                                "cancelled" -> "✗ " + tr("status_cancelled", "Cancelled")
                                "failed" -> "✗ " + tr("status_failed", "Failed")
                                "declined" -> "✗ " + tr("status_declined", "Declined")
                                "pending" -> "⏳ " + tr("status_pending", "Pending")
                                else -> record.status.replaceFirstChar { it.uppercase() }
                            }

                            Box(modifier = Modifier.onGloballyPositioned { coords ->
                                if (index == 0) tutorialBounds["historyItem"] = coords.boundsInWindow()
                                if (index == displayList.size - 1) tutorialBounds["historyItemLast"] = coords.boundsInWindow()
                            }) {
                                TransferHistoryItem(
                                    isSelectionMode = isSelectionMode,
                                    isSelected = selectedForDeletion.contains(record.sessionId),
                                    onToggleSelection = { selected ->
                                        if (selected) selectedForDeletion.add(record.sessionId) else selectedForDeletion.remove(record.sessionId)
                                    },
                                    title = title,
                                    subtitle = subtitle,
                                    status = statusLabel,
                                    statusColor = statusColor,
                                    onClick = { selectedSessionId = record.sessionId }
                                )
                            }
                        }
                    }
                }
                } // end historyList Box
            }

            // Tutorial Overlay
            if (showTutorialOverlay) {
                TutorialOverlay(
                    steps = listOf(
                        // Step 9: spotlight spans the whole history view — header + filter bar + the entire
                        // list box (union of the header row and the list container). Unioning to the full
                        // list (not just the last card) keeps the lower half lit instead of dimmed, so the
                        // tooltip drops over empty list space rather than hiding the bottom of the dialog.
                        TutorialStep(
                            "headerRow",
                            "Here you can see all your recent file transfers.",
                            unionTargetKey = "historyList"
                        ),
                        // Filter capsules — new in 2.2.1, first tutorial coverage
                        TutorialStep(
                            "filterRow",
                            "Filter by Incoming, Outgoing, Paused or Cancelled — tap a capsule."
                        ),
                        // Step 11: spotlight only the Select checkbox (anchored on the checkbox itself).
                        TutorialStep(
                            "btnSelect",
                            "Tap 'Select' to manage and delete transfer records."
                        ),
                        // Step 12: spotlight just the cards (first→last union), tooltip BELOW the cards.
                        TutorialStep(
                            "historyItem",
                            "Tap on any transfer record to see its details and progress.",
                            unionTargetKey = "historyItemLast"
                        ),
                        // Step 13: animate a finger tapping the received card AT ITS REAL LIST POSITION
                        // (continues from step 12 — last/incoming card), then reveal its detail view and
                        // spotlight the Open button. No NFC; replica only.
                        TutorialStep(
                            "historyItem",
                            "Press open button to open received file.",
                            hideSkipButton = true,
                            // Drop the tooltip into the detail's empty middle band (below the file rows /
                            // spotlighted Open button, above the bottom "Open Folder"), so it doesn't cover
                            // the highlighted Open button once the detail opens.
                            tooltipYOffset = 290.dp,
                            fullScreenContent = { TutorialRevealOpenFile(receivedCardWindowBounds = tutorialBounds["historyItemLast"]) }
                        )
                    ),
                    totalSteps = 13,
                    stepOffset = 8,
                    boundsMap = tutorialBounds,
                    onDismiss = { showTutorialOverlay = false; selectedSessionId = null; onDismiss() },
                    onComplete = { showTutorialOverlay = false; selectedSessionId = null; onDismiss() },
                    advanceTrigger = tutorialAdvanceRequest
                )
            }

            // Details Overlay
            if (selectedItemDetails != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colors.background)
                        .padding(Dimens.spaceXl)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.spaceLg),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { selectedSessionId = null },
                            modifier = Modifier.offset(x = (-12).dp).padding(end = Dimens.spaceMd)
                        ) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colors.onBackground)
                        }

                        Text(
                            if (selectedItemDetails?.direction == "incoming") "From : ${selectedItemDetails?.deviceName}" else "To : ${selectedItemDetails?.deviceName}",
                            style = MaterialTheme.typography.subtitle1,
                            color = MaterialTheme.colors.onBackground,
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = {
                                val sessionId = selectedItemDetails?.sessionId
                                if (sessionId != null) {
                                    onDelete(setOf(sessionId))
                                    selectedSessionId = null
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                            shape = RoundedCornerShape(Dimens.radiusLg),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Delete", style = MaterialTheme.typography.button, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Body Container
                    Column(
                        modifier = Modifier.fillMaxSize().weight(1f)
                    ) {
                        val record = selectedItemDetails!!
                        val fullDateStr = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(record.timestamp))
                        val totalMb = String.format("%.1f", record.totalBytes / (1024f * 1024f))
                        Text(
                            "$fullDateStr • ${record.fileNames.size} files ($totalMb MB)",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = Dimens.spaceLg)
                        )

                        // File List
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)
                        ) {
                            items(record.fileNames.size, key = { i -> "${record.sessionId}:$i" }) { i ->
                                val fileName = record.fileNames[i]
                                val fileSize = if (i < record.fileSizes.size) record.fileSizes[i] else 0L
                                val fileMb = String.format("%.1f", fileSize / (1024f * 1024f))
                                val isCompleted = i < record.filesCompleted
                                val isCurrent = i == record.filesCompleted
                                // Clamp: currentFileOffset can exceed fileSize (e.g. stale/over-reported
                                // offset), which made LinearProgressIndicator mis-render and pct print >100%.
                                // Count the displayed bytes toward each ~100ms offset update instead of stepping,
                                // so the bar and the pct/MB text flow (mirrors Android's DetailFileItem).
                                val targetBytes = if (isCompleted) fileSize
                                    else if (isCurrent) record.currentFileOffset.coerceIn(0L, fileSize)
                                    else 0L
                                val animBytes by animateFloatAsState(
                                    targetValue = targetBytes.toFloat(),
                                    animationSpec = tween(durationMillis = 500, easing = LinearEasing),
                                )
                                val progress = (animBytes / fileSize.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Dimens.spaceSm),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(Dimens.iconLg))
                                    Spacer(modifier = Modifier.width(Dimens.spaceLg))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(fileName, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onBackground)
                                        Spacer(modifier = Modifier.height(Dimens.spaceXs))
                                        LinearProgressIndicator(
                                            progress = progress,
                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                            color = GreenPrimary,
                                            backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.2f)
                                        )
                                        Spacer(modifier = Modifier.height(Dimens.spaceXs))
                                        val currentMb = String.format("%.1f", animBytes / (1024f * 1024f))
                                        val pct = (progress * 100).toInt()
                                        Text("$pct% ($currentMb MB / $fileMb MB)", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f))
                                    }

                                    Spacer(modifier = Modifier.width(Dimens.spaceLg))
                                    Button(
                                        onClick = {
                                            try {
                                                if (Desktop.isDesktopSupported()) {
                                                    val saveDir = File(SecurityVault.getSaveLocation())
                                                    val file = File(saveDir, fileName)
                                                    if (file.exists()) {
                                                        Desktop.getDesktop().open(file)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        },
                                        enabled = isCompleted,
                                        colors = ButtonDefaults.buttonColors(
                                            backgroundColor = InfoColor,
                                            disabledBackgroundColor = Color.Gray,
                                            disabledContentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(Dimens.radiusMd),
                                        modifier = Modifier.height(36.dp).onGloballyPositioned { coords ->
                                            if (i == 0) tutorialBounds["btnOpen"] = coords.boundsInWindow()
                                        }
                                    ) {
                                        Text("Open", style = MaterialTheme.typography.button, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Bottom Action Button
                        if (record.status == "transferring" || record.status == "pending") {
                            Button(
                                onClick = { onCancelTransfer(record.sessionId) },
                                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                                shape = RoundedCornerShape(Dimens.radiusMd),
                                modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = Dimens.spaceLg)
                            ) {
                                Text("Cancel", style = MaterialTheme.typography.subtitle1, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        } else if (record.status == "completed") {
                            Button(
                                onClick = {
                                    try {
                                        if (Desktop.isDesktopSupported()) {
                                            val saveDir = File(SecurityVault.getSaveLocation())
                                            if (saveDir.exists()) {
                                                Desktop.getDesktop().open(saveDir)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF333333)),
                                shape = RoundedCornerShape(Dimens.radiusMd),
                                modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = Dimens.spaceLg)
                            ) {
                                Text("Open Folder", style = MaterialTheme.typography.subtitle1, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        } else if (record.status == "cancelled" || record.status == "declined") {
                            Button(
                                onClick = { selectedSessionId = null },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF333333)),
                                shape = RoundedCornerShape(Dimens.radiusMd),
                                modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = Dimens.spaceLg)
                            ) {
                                Text("Close", style = MaterialTheme.typography.subtitle1, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        } else if (record.status == "failed") {
                            val connected = isDeviceConnected(record.deviceName)
                            Button(
                                onClick = { onResumeTransfer(record) },
                                enabled = connected,
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = MaterialTheme.colors.primary,
                                    disabledBackgroundColor = Color.Gray
                                ),
                                shape = RoundedCornerShape(Dimens.radiusMd),
                                modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = Dimens.spaceLg)
                            ) {
                                Text(
                                    if (connected) "Resume" else "Resume when connected",
                                    style = MaterialTheme.typography.subtitle1,
                                    color = if (connected) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
fun TransferHistoryItem(
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    title: String,
    subtitle: String,
    status: String,
    statusColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.radiusLg))
            .background(MaterialTheme.colors.surface)
            .clickable {
                if (isSelectionMode) {
                    onToggleSelection(!isSelected)
                } else {
                    onClick()
                }
            }
            .padding(Dimens.spaceLg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(Dimens.iconLg)
        )
        Spacer(modifier = Modifier.width(Dimens.spaceLg))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.onSurface)
            Text(subtitle, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
        }

        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection(it) },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colors.primary)
            )
        } else {
            Text(
                text = status,
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }
    }
}
