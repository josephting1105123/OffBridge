package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.components.EmptyState
import com.EntropicSoftwareLab.ClipboardShare.ui.components.SkeletonBox
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppColors
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.Dimens

/** Kind of file-preview tile to render (mirrors FilePreviewAdapter's branches, Coil-free). */
enum class FilePreviewKind { IMAGE, FOLDER, TEXT, OTHER }

/**
 * Read-only preview model for one chosen item. [bitmap] is a pre-decoded thumbnail for images
 * (loaded off the main thread by the Fragment); null for non-image kinds.
 */
data class FilePreviewUi(
    val name: String,
    val kind: FilePreviewKind,
    val bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
)

/** A paired, discoverable target device row. */
data class SendDeviceUi(
    val ip: String,
    val name: String,
    val isWindows: Boolean,
)

/**
 * Compose rewrite of SendToDevicesFragment's screen: a back-arrow header, a read-only horizontal
 * strip of the chosen files, the paired-device list as selectable cards (System Status DeviceCard
 * look + selected outline/fill), a select-all control and the bottom Send button. Pure presentation:
 * all polling / networking / SAF logic stays in the Fragment, which feeds state + callbacks.
 */
@Composable
fun SendToDevicesScreen(
    previews: List<FilePreviewUi>,
    devices: List<SendDeviceUi>,
    selectedIps: Set<String>,
    loading: Boolean,
    sending: Boolean,
    onBack: () -> Unit,
    onToggleDevice: (String) -> Unit,
    onToggleSelectAll: (Boolean) -> Unit,
    onSend: () -> Unit,
    onGoToPairing: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    val allSelected = devices.isNotEmpty() && selectedIps.size == devices.size
    val canSend = previews.isNotEmpty() && selectedIps.isNotEmpty() && !sending

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // Header: back arrow + title (top padding 96dp mirrors the original XML / Send Files).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = dimens.spaceXl, end = dimens.spaceXl, top = 96.dp, bottom = dimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(dimens.touchTarget)
                    .clip(RoundedCornerShape(dimens.radiusMd))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.action_close),
                    tint = colors.textPrimary,
                    modifier = Modifier.size(dimens.iconMd),
                )
            }
            Spacer(Modifier.width(dimens.spaceSm))
            Icon(
                painter = painterResource(R.drawable.ic_hdr_send),
                contentDescription = null,
                tint = colors.green,
                modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg),
            )
            Text(
                stringResource(R.string.send_to_devices_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Scrollable middle: file previews + paired-device list.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimens.spaceXl),
        ) {
            Text(
                stringResource(R.string.selected_files_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = dimens.spaceSm),
            )

            // Read-only file-preview strip (horizontal).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = dimens.spaceXl),
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
                    itemsIndexed(previews) { _, preview ->
                        FilePreviewTile(preview = preview, colors = colors, dimens = dimens)
                    }
                }
            }

            // Recipients header + select-all + scanning spinner.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = dimens.spaceLg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.select_trusted_recipients_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.width(dimens.spaceLg))
                Row(
                    modifier = Modifier.clickable(enabled = devices.isNotEmpty()) { onToggleSelectAll(!allSelected) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { onToggleSelectAll(it) },
                        enabled = devices.isNotEmpty(),
                        colors = CheckboxDefaults.colors(
                            checkedColor = colors.green,
                            uncheckedColor = colors.green,
                            checkmarkColor = Color.Black,
                        ),
                    )
                    Text(
                        stringResource(R.string.action_select_all),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.green,
                    )
                }
                // Discovery keeps polling while the list is empty — the spinner says "still
                // looking" next to the empty state below (System Status shows the same pair).
                if (!loading && devices.isEmpty()) {
                    CircularProgressIndicator(
                        color = colors.green,
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(start = dimens.spaceLg).size(20.dp),
                    )
                }
            }

            // Device list — skeletons while the first poll is in flight.
            if (loading) {
                repeat(2) {
                    SkeletonBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(DEVICE_CARD_HEIGHT)
                            .padding(bottom = dimens.spaceMd),
                        cornerRadius = dimens.radiusLg,
                    )
                }
            } else if (devices.isEmpty() && !sending) {
                // System-Status-style empty state: no paired device is online, so offer the
                // direct fix — jump to the Pairing tab. Suppressed mid-send so a transient
                // device dropout can't dangle a tab-switch button under an in-flight batch.
                EmptyState(
                    icon = ImageVector.vectorResource(R.drawable.ic_device),
                    title = stringResource(R.string.send_no_devices_title),
                    subtitle = stringResource(R.string.send_no_devices_hint),
                    actionLabel = stringResource(R.string.action_go_to_pairing),
                    onAction = onGoToPairing,
                    modifier = Modifier.padding(top = dimens.spaceXl),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceMd)) {
                    devices.forEach { device ->
                        SelectableDeviceCard(
                            device = device,
                            selected = selectedIps.contains(device.ip),
                            colors = colors,
                            dimens = dimens,
                            onClick = { onToggleDevice(device.ip) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(dimens.spaceLg))
        }

        // Bottom Send button (green when items + selection present and not already sending).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = dimens.spaceXl, end = dimens.spaceXl, top = dimens.spaceMd, bottom = dimens.spaceXl)
                .height(64.dp)
                .clip(RoundedCornerShape(dimens.radiusLg))
                .background(if (canSend) colors.green else colors.card)
                .clickable(enabled = canSend) { onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(if (sending) R.string.action_sending else R.string.action_send),
                style = MaterialTheme.typography.titleMedium,
                color = if (canSend) Color.Black else colors.textSecondary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Read-only preview tile that matches the Send-Files "Selected files" card design. */
@Composable
private fun FilePreviewTile(
    preview: FilePreviewUi,
    colors: AppColors,
    dimens: Dimens,
) {
    val shape = RoundedCornerShape(dimens.radiusMd)
    Column(
        modifier = Modifier
            .width(PREVIEW_TILE_WIDTH)
            .clip(shape)
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.divider, shape)
            .padding(dimens.spaceSm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            // Square picture (matches the Send-Files selected-files card), name below.
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (preview.kind == FilePreviewKind.IMAGE && preview.bitmap != null) {
                Image(
                    bitmap = preview.bitmap,
                    contentDescription = preview.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(dimens.radiusSm)),
                )
            } else {
                val iconRes = when (preview.kind) {
                    FilePreviewKind.FOLDER -> R.drawable.ic_folder
                    FilePreviewKind.TEXT -> R.drawable.ic_text
                    else -> null
                }
                if (iconRes != null) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(dimens.iconLg),
                    )
                } else {
                    Text("📄", style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        Spacer(Modifier.height(dimens.spaceXs))
        Text(
            preview.name,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Paired-device card matching the System Status DeviceCard look: card bg + 1dp hairline + 36dp green
 * device icon + name. Selected adds a green outline and an accentMuted fill.
 */
@Composable
private fun SelectableDeviceCard(
    device: SendDeviceUi,
    selected: Boolean,
    colors: AppColors,
    dimens: Dimens,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(dimens.radiusLg)
    val borderColor = if (selected) colors.green else colors.divider
    val borderWidth = if (selected) 2.dp else dimens.hairlineWidth
    val fill = if (selected) colors.accentMuted else colors.card
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .border(borderWidth, borderColor, shape)
            .clickable { onClick() }
            .padding(dimens.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(if (device.isWindows) R.drawable.ic_computer else R.drawable.ic_device),
            contentDescription = null,
            tint = colors.green,
            modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg),
        )
        Text(
            device.name,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = colors.green,
                modifier = Modifier.size(dimens.iconMd),
            )
        }
    }
}

private val PREVIEW_TILE_WIDTH = 100.dp
private val DEVICE_CARD_HEIGHT = 64.dp
