package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.components.DesignModeToggle
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun LiteClipboardScreen(
    history: List<String>,
    pairedDevices: List<SendDeviceUi>,
    acceptSync: Boolean,
    onAcceptSyncChange: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (String) -> Unit,
    onPair: (String) -> Unit,
    onUnpair: (String) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit
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
            // Header: title + sync toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 72.dp, bottom = dimens.spaceLg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(dimens.headerIcon)
                        .padding(end = dimens.spaceLg)
                        .clip(RoundedCornerShape(dimens.radiusMd))
                        .clickable { onBack() },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = stringResource(R.string.action_close),
                        tint = colors.textPrimary,
                        modifier = Modifier.size(dimens.iconMd)
                    )
                }
                Text(
                    stringResource(R.string.clipboard_autosync_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                // Glass On/Off slider: off = red (left), on = green (right). No labels — the word
                // "On"/"Off" wouldn't fit a compact control across all 10 locales; position + colour say it.
                DesignModeToggle(
                    options = listOf("off", "on"),
                    selectedIndex = if (acceptSync) 1 else 0,
                    onSelect = { onAcceptSyncChange(it == 1) },
                    selectedColors = listOf(colors.error, colors.greenText),
                    showLabels = false,
                    height = 32.dp,
                    modifier = Modifier.width(56.dp)
                )
            }

            // Status strip — a single line summarising sync state + peer count, tinted by state.
            val syncActive = acceptSync
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(dimens.radiusLg))
                    .background(if (syncActive) colors.accentMuted else colors.card)
                    .border(dimens.hairlineWidth, colors.divider, RoundedCornerShape(dimens.radiusLg))
                    .padding(horizontal = dimens.spaceLg, vertical = dimens.spaceMd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(dimens.spaceMd)
                        .clip(RoundedCornerShape(dimens.radiusPill))
                        .background(if (syncActive) colors.green else colors.textSecondary)
                )
                Spacer(Modifier.size(dimens.spaceSm))
                Text(
                    // Only actually-paired devices sync; the list also holds nearby-unpaired rows.
                    text = if (syncActive)
                        stringResource(R.string.lite_clip_sync_on, pairedDevices.count { it.isPaired })
                    else stringResource(R.string.lite_clip_sync_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (syncActive) colors.greenText else colors.textSecondary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(dimens.spaceXl))

            SectionLabel(stringResource(R.string.recent_copies_tap_hint), history.size)
            Spacer(Modifier.height(dimens.spaceSm))

            // Recent copies tray — the weighted primary, so it absorbs vertical squeeze while the
            // bounded devices tray below stays visible.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(dimens.radiusLg))
                    .background(colors.bottomSheet)
                    .padding(dimens.spaceSm)
            ) {
                if (history.isEmpty()) {
                    EmptyTray(stringResource(R.string.history_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)
                    ) {
                        // Index in the key so a (theoretically) duplicate copy string can't crash the list.
                        itemsIndexed(history, key = { i, entry -> "$i:$entry" }) { _, entry ->
                            LiteCopyCard(
                                entry = entry,
                                onOpen = { detailEntry = entry },
                                onCopy = { onCopy(entry) },
                                onDelete = { onDelete(entry) }
                            )
                        }
                    }
                }
            }

            // Add bottom padding so the history tray isn't blocked by the slide-up tray's peek height
            Spacer(Modifier.height(88.dp))
        }

        // Custom Slide-Up Tray for Connected Devices
        val peekHeightDp = 88.dp
        val expandedHeightDp = 350.dp
        val density = LocalDensity.current
        val maxOffsetPx = with(density) { (expandedHeightDp - peekHeightDp).toPx() }
        
        val trayOffset = remember { Animatable(maxOffsetPx) }
        val coroutineScope = rememberCoroutineScope()
        // Drag sets this; the bounce hint only runs while the user hasn't touched the tray, and stops
        // for good once they open it. Keying the effect on a stable Boolean (not trayOffset.targetValue,
        // which changes every drag frame) stops the per-frame restart churn the first pass had.
        var trayTouched by remember { mutableStateOf(false) }

        // Bounce hint: nudge the collapsed tray up ~40dp a few times so it's discoverable, then rest.
        LaunchedEffect(trayTouched) {
            if (trayTouched) return@LaunchedEffect
            repeat(3) {
                delay(1500)
                if (trayTouched) return@LaunchedEffect
                trayOffset.animateTo(
                    targetValue = maxOffsetPx - with(density) { 40.dp.toPx() },
                    animationSpec = tween(250, easing = FastOutSlowInEasing)
                )
                trayOffset.animateTo(
                    targetValue = maxOffsetPx,
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth() // Edge-to-edge slide-up tray
                .height(expandedHeightDp)
                .offset { IntOffset(0, trayOffset.value.toInt()) }
                .clip(RoundedCornerShape(topStart = dimens.radiusXl, topEnd = dimens.radiusXl))
                .background(colors.background) // Slide-up uses main background color
                .border(
                    width = dimens.hairlineWidth,
                    color = colors.divider,
                    shape = RoundedCornerShape(topStart = dimens.radiusXl, topEnd = dimens.radiusXl)
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Draggable Header Area
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { trayTouched = true },
                                onDragEnd = {
                                    coroutineScope.launch {
                                        val target = if (trayOffset.value < maxOffsetPx / 2) 0f else maxOffsetPx
                                        trayOffset.animateTo(target, tween(300))
                                    }
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    coroutineScope.launch {
                                        val newValue = (trayOffset.value + dragAmount).coerceIn(0f, maxOffsetPx)
                                        trayOffset.snapTo(newValue)
                                    }
                                }
                            )
                        }
                        .padding(top = dimens.spaceMd, bottom = dimens.spaceLg),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Drag Handle Pill
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(colors.textSecondary.copy(alpha = 0.5f))
                    )
                    Spacer(Modifier.height(dimens.spaceMd))
                    SectionLabel(stringResource(R.string.lite_paired_nearby_devices), pairedDevices.size)
                }

                // Device list (no inner tray surface — the slide-up panel is the surface).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = dimens.spaceXl)
                        .padding(bottom = dimens.spaceMd)
                ) {
                    if (pairedDevices.isEmpty()) {
                        Column {
                            EmptyTray(stringResource(R.string.paired_devices_empty))
                            RescanPill(onRescan)
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(dimens.spaceMd),
                            contentPadding = PaddingValues(bottom = 120.dp)
                        ) {
                            itemsIndexed(pairedDevices) { index, device ->
                                DeviceCardRow(
                                    name = device.name,
                                    isWindows = device.isWindows,
                                    isPaired = device.isPaired,
                                    onPair = { onPair(device.ip) },
                                    onUnpair = { onUnpair(device.ip) }
                                )
                                if (index < pairedDevices.size - 1) {
                                    Spacer(Modifier.height(dimens.spaceMd))
                                }
                            }
                            item { RescanPill(onRescan) }
                        }
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

/** Hairline capsule with a refresh glyph — flushes discovery and repopulates the device list. */
@Composable
private fun RescanPill(onRescan: () -> Unit) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .padding(top = dimens.spaceLg, bottom = dimens.spaceXxl)
                .clip(RoundedCornerShape(dimens.radiusPill))
                .background(colors.card)
                .border(dimens.hairlineWidth, colors.divider, RoundedCornerShape(dimens.radiusPill))
                .clickable { onRescan() }
                .padding(horizontal = dimens.spaceXl, vertical = dimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = colors.textPrimary,
                modifier = Modifier.size(dimens.iconSm)
            )
            Text(
                stringResource(R.string.action_rescan),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, count: Int) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        if (count > 0) {
            Spacer(Modifier.size(dimens.spaceSm))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(dimens.radiusPill))
                    .background(colors.accentMuted)
                    .padding(horizontal = dimens.spaceSm, vertical = dimens.spaceXxs),
            ) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.greenText,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** A single recent-copy card: leading clipboard glyph, the copied text (2 lines), and copy + delete
 *  actions. Tapping the body opens the full detail overlay. */
@Composable
private fun LiteCopyCard(
    entry: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.radiusMd))
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.divider, RoundedCornerShape(dimens.radiusMd))
            .clickable { onOpen() }
            .padding(dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(dimens.iconLg + dimens.spaceMd)
                .clip(RoundedCornerShape(dimens.radiusSm))
                .background(colors.accentMuted),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_clipboard_light),
                contentDescription = null,
                tint = colors.greenText,
                modifier = Modifier.size(dimens.iconMd)
            )
        }
        Spacer(Modifier.size(dimens.spaceMd))
        Text(
            entry,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.size(dimens.spaceSm))
        IconAction(Icons.Outlined.ContentCopy, colors.greenText, onCopy)
        IconAction(Icons.Outlined.DeleteOutline, colors.error, onDelete)
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    val dimens = AppThemeTokens.dimens
    Box(
        modifier = Modifier
            .size(dimens.touchTarget)
            .clip(RoundedCornerShape(dimens.radiusSm))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(dimens.iconMd))
    }
}

@Composable
private fun EmptyTray(text: String) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
            modifier = Modifier.padding(dimens.spaceMd)
        )
    }
}
