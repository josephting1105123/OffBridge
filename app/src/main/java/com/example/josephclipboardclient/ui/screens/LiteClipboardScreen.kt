package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Phone
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
import com.EntropicSoftwareLab.ClipboardShare.ui.components.SegmentDots
import com.EntropicSoftwareLab.ClipboardShare.ui.components.SegmentedSnapPager
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
    val pagerState = rememberPagerState { 2 }

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

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                SegmentedSnapPager(
                    segmentCount = 2,
                    modifier = Modifier.fillMaxSize(),
                    state = pagerState
                ) { segment ->
                    if (segment == 0) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            stickyHeader {
                                SegmentTitle {
                                    SectionLabel(stringResource(R.string.lite_paired_nearby_devices), pairedDevices.size)
                                }
                            }
                            item {
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
                                Spacer(Modifier.height(dimens.spaceLg))
                            }
                            if (pairedDevices.isEmpty()) {
                                item {
                                    EmptyTray(
                                        stringResource(R.string.paired_devices_empty),
                                        Modifier.fillParentMaxWidth().fillParentMaxHeight(0.5f)
                                    )
                                }
                            } else {
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
                            }
                            item { RescanPill(onRescan) }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
                            contentPadding = PaddingValues(bottom = dimens.spaceXl)
                        ) {
                            stickyHeader {
                                SegmentTitle {
                                    SectionLabel(stringResource(R.string.recent_copies_tap_hint), history.size)
                                }
                            }
                            if (history.isEmpty()) {
                                item {
                                    EmptyTray(
                                        stringResource(R.string.history_empty),
                                        Modifier.fillParentMaxWidth().fillParentMaxHeight(0.6f)
                                    )
                                }
                            } else {
                                // Index in the key so a (theoretically) duplicate copy string can't crash the list.
                                itemsIndexed(history, key = { i, entry -> "$i:$entry" }) { _, entry ->
                                    LiteCopyRow(
                                        entry = entry,
                                        onOpen = { detailEntry = entry },
                                        onCopy = { onCopy(entry) },
                                        onDelete = { onDelete(entry) }
                                    )
                                }
                            }
                        }
                    }
                }
                SegmentDots(pagerState, 2, Modifier.align(Alignment.CenterEnd).offset(x = dimens.spaceLg))
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

/** Pinned segment title: opaque so rows scroll underneath it cleanly. */
@Composable
private fun SegmentTitle(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppThemeTokens.colors.background)
            .padding(vertical = AppThemeTokens.dimens.spaceSm)
    ) { content() }
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

private enum class ClipKind { LINK, EMAIL, PHONE, COLOR, CODE, TEXT }

private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
private val COLOR_REGEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
private val PHONE_REGEX = Regex("^\\+?[0-9 ()\\-]{7,20}$")
private val CODE_MARKERS = listOf("{", "}", ";", "=>", "def ", "fun ", "</")

private fun clipKindOf(s: String): ClipKind {
    val t = s.trim()
    val isLink = (t.startsWith("http://", true) || t.startsWith("https://", true) || t.startsWith("www.", true)) &&
        t.none { it.isWhitespace() }
    return when {
        isLink -> ClipKind.LINK
        EMAIL_REGEX.matches(t) -> ClipKind.EMAIL
        COLOR_REGEX.matches(t) -> ClipKind.COLOR
        PHONE_REGEX.matches(t) && t.count { it.isDigit() } >= 7 -> ClipKind.PHONE
        t.contains('\n') && CODE_MARKERS.any { t.contains(it) } -> ClipKind.CODE
        else -> ClipKind.TEXT
    }
}

/** A single dense recent-copy row: type badge, the copied text (2 lines, plus the host for links), and
 *  copy + delete actions. Tapping the body opens the full detail overlay. */
@Composable
private fun LiteCopyRow(
    entry: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val t = entry.trim()
    val kind = clipKindOf(entry)
    val badgeColor = if (kind == ClipKind.COLOR) {
        val hex = if (t.length == 4) "#" + t.substring(1).map { "$it$it" }.joinToString("") else t
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(colors.accentMuted)
    } else colors.accentMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.radiusMd))
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.divider, RoundedCornerShape(dimens.radiusMd))
            .clickable { onOpen() }
            .padding(horizontal = dimens.spaceMd, vertical = dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(dimens.radiusSm))
                .background(badgeColor),
            contentAlignment = Alignment.Center
        ) {
            val iconModifier = Modifier.size(dimens.iconSm)
            when (kind) {
                ClipKind.LINK -> Icon(Icons.Outlined.Link, null, iconModifier, colors.greenText)
                ClipKind.EMAIL -> Icon(Icons.Outlined.Email, null, iconModifier, colors.greenText)
                ClipKind.PHONE -> Icon(Icons.Outlined.Phone, null, iconModifier, colors.greenText)
                ClipKind.COLOR -> Icon(Icons.Outlined.Palette, null, iconModifier, colors.greenText)
                ClipKind.CODE -> Icon(Icons.Outlined.Code, null, iconModifier, colors.greenText)
                ClipKind.TEXT -> Icon(
                    painterResource(R.drawable.ic_clipboard_light), null, iconModifier, colors.greenText
                )
            }
        }
        Spacer(Modifier.size(dimens.spaceMd))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (kind == ClipKind.LINK) {
                val host = runCatching {
                    android.net.Uri.parse(if (t.startsWith("www.", true)) "https://$t" else t).host
                }.getOrNull()
                if (host != null) {
                    Text(
                        host,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
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
private fun EmptyTray(text: String, modifier: Modifier = Modifier) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
            modifier = Modifier.padding(dimens.spaceMd)
        )
    }
}
