package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.TransferItem
import com.EntropicSoftwareLab.ClipboardShare.sendvia.SendViaOverlay
import com.EntropicSoftwareLab.ClipboardShare.ui.components.HowToUseChip
import com.EntropicSoftwareLab.ClipboardShare.ui.formatTotalSize
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialOverlay
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialPlaceholderFiles
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialPlaceholderSendVia
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialRevealConnectedDevices
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialRevealNfc
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialRevealQr
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialStep
import com.EntropicSoftwareLab.ClipboardShare.ui.modal.backdropSource
import com.EntropicSoftwareLab.ClipboardShare.ui.modal.rememberBackdropState
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.rememberTutorialAnchors
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.tutorialAnchor
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.geometry.Rect
import androidx.documentfile.provider.DocumentFile

/**
 * Compose Send-Files tab. Pure presentation: it renders the pinned header (send icon + title +
 * how-to-use chip + transfer-history button), the horizontally-scrollable file-type selector, the
 * "Selected files" 3-column grid on the rounded bottom-sheet surface, and the bottom Send button.
 *
 * The picker/send/history logic stays in [com.EntropicSoftwareLab.ClipboardShare.FileTransferFragment];
 * this screen forwards user actions through the callback lambdas. The Send-Files tutorial is now a
 * Compose [TutorialOverlay] hosted here (panel/grid/send/history anchored via [tutorialAnchor]),
 * mirroring the Windows client; the fragment only drives [showTutorial] and the completion chain.
 */
@Composable
fun FileTransferScreen(
    selectedFiles: List<TransferItem>,
    sendEnabled: Boolean,
    totalSizeBytes: Long,
    onSelectFile: () -> Unit,
    onSelectMedia: () -> Unit,
    onSelectFolder: () -> Unit,
    onSelectText: () -> Unit,
    onSelectApp: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onMoveFile: (Int, Int) -> Unit,
    onPreviewFile: (TransferItem) -> Unit,
    selectionMode: Boolean,
    selectedIndices: Set<Int>,
    onToggleSelectMode: (Boolean) -> Unit,
    onToggleSelection: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onSend: () -> Unit,
    onHowToUse: () -> Unit,
    /** Window bounds of the "How to use" chip — the forced first-launch tour spotlights it. */
    onHowToUseChipBounds: (Rect) -> Unit = {},
    onTransferHistory: () -> Unit,
    showTutorial: Boolean,
    onTutorialDismiss: () -> Unit,
    onTutorialComplete: () -> Unit,
    showSendVia: Boolean,
    nfcUnavailable: Boolean,
    onSendViaChoose: (String) -> Unit,
    onSendViaDismiss: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val anchors = rememberTutorialAnchors()
    val backdrop = rememberBackdropState()
    // Step 4 spotlights the Send button; force its enabled (green) look while that step shows so the
    // tutorial reads clearly even with no files queued. Never enables the real tap (gated on sendEnabled).
    var tutorialForceSend by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxSize().backdropSource(backdrop)) {
            // Pinned header — send icon + title + how-to-use chip + transfer-history button.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = dimens.spaceXl,
                        end = dimens.spaceXl,
                        top = 96.dp,
                        bottom = dimens.spaceXl,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_hdr_send),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg),
                )
                Text(
                    stringResource(R.string.send_files_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                HowToUseChip(
                    onClick = onHowToUse,
                    modifier = Modifier.onGloballyPositioned { onHowToUseChipBounds(it.boundsInWindow()) },
                )
                Spacer(Modifier.width(dimens.spaceSm))
                Box(
                    modifier = Modifier
                        .size(dimens.touchTarget)
                        .clip(RoundedCornerShape(dimens.radiusMd))
                        .clickable { onTransferHistory() }
                        .tutorialAnchor(anchors, "history")
                        .padding(dimens.spaceSm),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_transfer_history),
                        contentDescription = stringResource(R.string.send_files_title),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(dimens.iconMd),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = dimens.spaceXl,
                        end = dimens.spaceXl,
                        top = dimens.spaceSm,
                        bottom = dimens.spaceXl,
                    ),
            ) {
                // File-type selector — horizontally-scrollable row of icon-above-label boxes.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = dimens.spaceLg)
                        // Anchor BEFORE horizontalScroll so the tutorial measures the visible viewport
                        // (full width, correct position), not the wider/offset scrollable content.
                        .tutorialAnchor(anchors, "panel")
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd),
                ) {
                    TypeButton(R.drawable.ic_file, stringResource(R.string.type_file), onSelectFile)
                    TypeButton(R.drawable.ic_media, stringResource(R.string.type_media), onSelectMedia)
                    TypeButton(R.drawable.ic_folder, stringResource(R.string.type_folder), onSelectFolder)
                    TypeButton(R.drawable.ic_clipboard_light, stringResource(R.string.type_text), onSelectText)
                    TypeButton(R.drawable.ic_app, stringResource(R.string.type_app), onSelectApp)
                }

                // "Selected files" header: title + Select, or the selection action bar.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = dimens.spaceSm, bottom = dimens.spaceSm),
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

                // Grid fills the remaining height down to the Send button, on the rounded
                // bottom-sheet surface.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(dimens.radiusLg))
                        .background(colors.bottomSheet)
                        .padding(dimens.spaceSm)
                        .tutorialAnchor(anchors, "grid"),
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
            }

            // Bottom Send button.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = dimens.spaceXl,
                        end = dimens.spaceXl,
                        bottom = dimens.spaceXl,
                    )
                    .height(64.dp)
                    .clip(RoundedCornerShape(dimens.radiusLg))
                    .background(if (sendEnabled || tutorialForceSend) colors.green else colors.card)
                    .tutorialAnchor(anchors, "send")
                    .clickable(enabled = sendEnabled) { onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.action_send),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (sendEnabled || tutorialForceSend) Color.Black else Color(0xFF808080),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Send-Via chooser overlay: scrim + card sliding up from behind the bottom nav bar. Rendered
        // before the tutorial block so the tutorial overlay keeps top z-order.
        SendViaOverlay(
            visible = showSendVia,
            nfcUnavailable = nfcUnavailable,
            totalSizeBytes = totalSizeBytes,
            onChoose = onSendViaChoose,
            onDismiss = onSendViaDismiss,
            backdrop = backdrop,
        )

        if (showTutorial) {
            val steps = listOf(
                TutorialStep(
                    targetKey = "panel",
                    text = stringResource(R.string.transfer_tut_file_type),
                    spotlightWidthTrim = 10.dp,
                ),
                TutorialStep(
                    targetKey = "grid",
                    text = stringResource(R.string.transfer_tut_files_appear),
                    // When real files are queued, don't overlay fakes — spotlight the real grid.
                    placeholderContent = if (selectedFiles.isEmpty()) {
                        { TutorialPlaceholderFiles(selected = false) }
                    } else null,
                    // Tight band centred on the 3 fake files; with real files, spotlight the grid as before.
                    spotlightHeight = if (selectedFiles.isEmpty()) 150.dp else null,
                    spotlightBottomTrimFraction = if (selectedFiles.isEmpty()) 0f else 0.1f,
                ),
                TutorialStep(
                    targetKey = "grid",
                    text = stringResource(R.string.transfer_tut_long_press),
                    placeholderContent = if (selectedFiles.isEmpty()) {
                        { TutorialPlaceholderFiles(selected = true) }
                    } else null,
                    spotlightHeight = if (selectedFiles.isEmpty()) 150.dp else null,
                    spotlightBottomTrimFraction = if (selectedFiles.isEmpty()) 0f else 0.1f,
                ),
                TutorialStep(
                    targetKey = "send",
                    text = stringResource(R.string.transfer_tut_send_button),
                    forceTooltipAbove = true,
                    onShow = { tutorialForceSend = true },
                    onHide = { tutorialForceSend = false },
                ),
                // Step 5 — Send-Via sheet docked at the bottom, full width (like the real bottom sheet).
                TutorialStep(
                    targetKey = "grid",
                    text = stringResource(R.string.transfer_tut_send_via),
                    fullScreenContent = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            // With real files queued, the fake sheet shows their real total size.
                            if (selectedFiles.isEmpty()) TutorialPlaceholderSendVia()
                            else TutorialPlaceholderSendVia(totalSizeBytes = totalSizeBytes)
                        }
                    },
                    forceTooltipAbove = true,
                ),
                // Steps 6-8 — replay the Send-Via sheet, tap the tile, then reveal the full-screen replica.
                TutorialStep(
                    targetKey = "grid",
                    text = stringResource(R.string.transfer_tut_pick_devices),
                    fullScreenContent = { TutorialRevealConnectedDevices() },
                ),
                TutorialStep(
                    targetKey = "grid",
                    text = stringResource(R.string.transfer_tut_offgrid_code),
                    fullScreenContent = { TutorialRevealQr() },
                ),
                TutorialStep(
                    targetKey = "grid",
                    text = stringResource(R.string.transfer_tut_nfc),
                    fullScreenContent = { TutorialRevealNfc() },
                ),
                TutorialStep(
                    targetKey = "history",
                    text = stringResource(R.string.transfer_tut_history),
                    buttonLabel = stringResource(R.string.transfer_tut_next),
                    // Square spotlight snug around the (square) history icon, not a wide rounded rect.
                    spotlightSquare = true,
                ),
            )
            // 14 = 9 send steps + the 5 history-sheet steps the completion chains into.
            TutorialOverlay(
                steps = steps,
                totalSteps = 14,
                stepOffset = 0,
                boundsMap = anchors,
                onDismiss = onTutorialDismiss,
                onComplete = onTutorialComplete,
                backdrop = backdrop,
            )
        }
    }
}

@Composable
internal fun TypeButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val shape = RoundedCornerShape(dimens.radiusLg)
    Column(
        modifier = modifier
            .width(120.dp)
            .height(80.dp)
            .clip(shape)
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.divider, shape)
            .clickable { onClick() },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(dimens.iconLg),
        )
        Spacer(Modifier.height(dimens.spaceSm))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
        )
    }
}

/**
 * 3-column grid of selected-file cards. Long-press a card then drag it onto another to reorder
 * (mirrors the old ItemTouchHelper drag-reorder); each card has a top-right remove (X) button.
 */
@Composable
internal fun SelectedFilesGrid(
    selectedFiles: List<TransferItem>,
    selectionMode: Boolean,
    selectedIndices: Set<Int>,
    onRemoveFile: (Int) -> Unit,
    onMoveFile: (Int, Int) -> Unit,
    onPreviewFile: (TransferItem) -> Unit,
    onToggleSelection: (Int) -> Unit,
) {
    val dimens = AppThemeTokens.dimens
    val gridState = rememberLazyGridState()
    val dragState = remember { GridDragState() }

    LazyVerticalGrid(
        // Fixed-ish card width: 3 fit on a phone, more on a tablet (never one huge card per third).
        columns = GridCells.Adaptive(minSize = SELECTED_CARD_MIN_WIDTH),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            // Drag-reorder only in normal mode; in selection mode taps toggle selection instead.
            .then(if (selectionMode) Modifier else Modifier.dragReorder(gridState, dragState, onMoveFile)),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
    ) {
        itemsIndexed(
            selectedFiles,
            // Index-prefixed so duplicate picks (same uri twice) can't collide -> no "key already
            // used" crash; the grid is always re-driven from the backing list on remove/reorder.
            key = { index, item -> "${index}_${item.uri}_${item.relativePath}_${item.isFolder}" },
        ) { index, item ->
            FilePreviewCard(
                item = item,
                isDragging = dragState.draggingIndex == index,
                dragOffset = dragState.dragOffset,
                selectionMode = selectionMode,
                selected = index in selectedIndices,
                onClick = { if (selectionMode) onToggleSelection(index) else onPreviewFile(item) },
                onRemove = { onRemoveFile(index) },
            )
        }
    }
}

@Composable
internal fun FilePreviewCard(
    item: TransferItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    isDragging: Boolean = false,
    dragOffset: Offset = Offset.Zero,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    val context = LocalContext.current
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val shape = RoundedCornerShape(dimens.radiusMd)

    // Long-press lift: the card shakes (settles to a slight tilt), pops out of the sunken sink (scale +
    // shadow) and follows the finger. Shake is one-shot on pickup; the lift tracks [isDragging].
    val shake = remember { Animatable(0f) }
    LaunchedEffect(isDragging) {
        if (isDragging) {
            shake.snapTo(0f)
            shake.animateTo(-5f, tween(60))
            shake.animateTo(5f, tween(80))
            shake.animateTo(-2.5f, tween(70))
            shake.animateTo(2.5f, tween(70))
        } else {
            shake.animateTo(0f, tween(120))
        }
    }

    // Resolve mime type from the content resolver (folders can't be resolved).
    val mimeType = remember(item.uri, item.isFolder) {
        if (item.isFolder) "inode/directory"
        else runCatching { context.contentResolver.getType(item.uri) }.getOrNull()
            ?: "application/octet-stream"
    }
    val isImage = mimeType.startsWith("image/")
    val displayPath = item.relativePath ?: remember(item.uri) { queryDisplayName(context, item.uri) }
    val isTextItem = remember(displayPath) { displayPath.matches(Regex("Text \\d+")) }

    val imageBitmap = remember(item.uri, isImage) {
        if (!isImage) null
        else runCatching {
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }

    val borderColor = if (selected) colors.green else colors.divider
    val borderWidth = if (selected) 2.dp else dimens.hairlineWidth
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                this.shape = shape
                translationX = if (isDragging) dragOffset.x else 0f
                translationY = if (isDragging) dragOffset.y else 0f
                val s = if (isDragging) 1.08f else 1f
                scaleX = s
                scaleY = s
                rotationZ = shake.value
                shadowElevation = if (isDragging) 12.dp.toPx() else 0f
            }
            .clip(shape)
            .background(colors.card)
            .border(borderWidth, borderColor, shape)
            .clickable { onClick() },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(dimens.spaceSm),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (isImage && imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(dimens.radiusSm)),
                    )
                } else if (item.isFolder) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(dimens.iconLg),
                    )
                } else if (isTextItem) {
                    Icon(
                        painter = painterResource(R.drawable.ic_text),
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(dimens.iconLg),
                    )
                } else {
                    Text("📄", style = MaterialTheme.typography.headlineSmall)
                }
            }
            Spacer(Modifier.height(dimens.spaceXs))
            Text(
                shortName(if (item.isFolder) folderName(context, item.uri) else displayPath),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (selectionMode) {
            // Selection indicator (top-right): filled green check when selected, empty circle otherwise.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(dimens.spaceXxs)
                    .size(SELECTION_BADGE_SIZE)
                    .clip(CircleShape)
                    .background(if (selected) colors.green else Color(0x80000000)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(SELECTION_CHECK_ICON),
                    )
                }
            }
        } else {
            // Quick-delete X button (top-right).
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(dimens.spaceXxs)
                    .size(24.dp)
                    .clip(RoundedCornerShape(dimens.radiusSm))
                    .background(Color(0x80000000))
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = Color.White,
                    modifier = Modifier.size(dimens.iconSm),
                )
            }
        }
    }
}

private val SELECTED_CARD_MIN_WIDTH = 96.dp

// Selection checkmark badge, shrunk 5% from the old 24/18 so it sits less cramped in the corner.
// Shared with the tutorial's fake card (TutorialPlaceholders) so both render identically.
internal val SELECTION_BADGE_SIZE = 22.8.dp   // 24 × 0.95
internal val SELECTION_CHECK_ICON = 17.1.dp   // 18 × 0.95

private class GridDragState {
    var draggingIndex: Int? by mutableStateOf(null)
    var dragOffset: Offset by mutableStateOf(Offset.Zero)
}

/**
 * Long-press-drag reorder for the [LazyVerticalGrid]. On long-press it captures the item under the
 * finger; as the finger moves over another item it calls [onMove], which moves (insert/shift) the
 * dragged item into that slot — preserving the relative order of the items it passes over.
 */
private fun Modifier.dragReorder(
    gridState: LazyGridState,
    dragState: GridDragState,
    onMove: (Int, Int) -> Unit,
): Modifier = this.then(
    Modifier.androidxDragReorder(gridState, dragState, onMove),
)

private fun Modifier.androidxDragReorder(
    gridState: LazyGridState,
    dragState: GridDragState,
    onMove: (Int, Int) -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            dragState.draggingIndex = itemIndexAt(gridState, offset)
            dragState.dragOffset = Offset.Zero
        },
        onDragEnd = {
            dragState.draggingIndex = null
            dragState.dragOffset = Offset.Zero
        },
        onDragCancel = {
            dragState.draggingIndex = null
            dragState.dragOffset = Offset.Zero
        },
        onDrag = { change, dragAmount ->
            change.consume()
            val from = dragState.draggingIndex ?: return@detectDragGesturesAfterLongPress
            dragState.dragOffset += dragAmount
            val over = itemIndexAt(gridState, change.position)
            if (over != null && over != from) {
                onMove(from, over)
                dragState.draggingIndex = over
                // The card has physically re-slotted into the finger's cell — drop the residual offset
                // so it doesn't leap on the next frame.
                dragState.dragOffset = Offset.Zero
            }
        },
    )
}

/** Resolves which grid item index sits under [offset] (in the grid's local coordinates). */
private fun itemIndexAt(
    gridState: LazyGridState,
    offset: Offset,
): Int? {
    val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        val x = info.offset.x
        val y = info.offset.y
        offset.x.toInt() in x..(x + info.size.width) &&
            offset.y.toInt() in y..(y + info.size.height)
    }
    return item?.index
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = runCatching {
            context.contentResolver.query(uri, null, null, null, null)
        }.getOrNull()
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) result = result?.substring(cut + 1)
    }
    return result ?: "Unknown File"
}

private fun folderName(context: Context, uri: Uri): String {
    val docFile = runCatching {
        DocumentFile.fromTreeUri(context, uri)
    }.getOrNull()
    return docFile?.name ?: "Folder"
}

/** Shortens a display path to "first/.../last". */
private fun shortName(displayPath: String): String {
    val parts = displayPath.split("/")
    return if (parts.size > 1) "${parts[0]}/.../${parts.last()}" else parts.last()
}

/** Pill box beside the "Selected files" title showing the queued total ("0 MB" when nothing is queued). */
@Composable
internal fun TotalSizeBox(totalSizeBytes: Long) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val shape = RoundedCornerShape(dimens.radiusPill)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.divider, shape)
            .padding(horizontal = dimens.spaceSm, vertical = dimens.spaceXxs),
    ) {
        Text(
            formatTotalSize(totalSizeBytes),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
internal fun CapsuleButton(
    label: String,
    filled: Boolean,
    enabled: Boolean = true,
    fill: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    val dimens = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val shape = RoundedCornerShape(dimens.radiusPill)
    val bg = if (!enabled) colors.divider else if (filled) fill else colors.card
    val border = if (filled) bg else colors.divider
    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(dimens.hairlineWidth, border, shape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = dimens.spaceLg, vertical = dimens.spaceSm),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) textColor else colors.textSecondary, fontWeight = FontWeight.Bold)
    }
}
