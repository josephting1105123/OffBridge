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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.components.DesignModeToggle
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

@Composable
fun LiteClipboardScreen(
    history: List<String>,
    pairedDevices: List<SendDeviceUi>,
    acceptSync: Boolean,
    onAcceptSyncChange: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (String) -> Unit,
    onPair: (String) -> Unit,
    onUnpair: (String) -> Unit
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
                Icon(
                    painter = painterResource(R.drawable.ic_hdr_history),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg)
                )
                Text(
                    stringResource(R.string.lite_clipboard),
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
                    text = if (syncActive)
                        stringResource(R.string.lite_clip_sync_on, pairedDevices.size)
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

            Spacer(Modifier.height(dimens.spaceXl))

            SectionLabel(stringResource(R.string.connected_devices_label), pairedDevices.size)
            Spacer(Modifier.height(dimens.spaceSm))

            // Connected devices tray — bounded (not weighted) so it never gets squeezed off-screen;
            // it scrolls internally past the max.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 220.dp)
                    .clip(RoundedCornerShape(dimens.radiusLg))
                    .background(colors.bottomSheet)
                    .padding(dimens.spaceSm)
            ) {
                if (pairedDevices.isEmpty()) {
                    EmptyTray(stringResource(R.string.paired_devices_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)
                    ) {
                        items(pairedDevices) { device ->
                            DeviceCardRow(
                                name = device.name,
                                isWindows = device.isWindows,
                                isPaired = device.isPaired,
                                onPair = { onPair(device.ip) },
                                onUnpair = { onUnpair(device.ip) }
                            )
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
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
