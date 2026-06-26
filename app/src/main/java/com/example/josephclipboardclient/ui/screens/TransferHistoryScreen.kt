package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.TransferRecord
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppColors
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.Dimens

/** One per-file row in the transfer detail view (mirrors the old DetailFilesAdapter row). */
data class DetailFileRow(
    val position: Int,
    val fileName: String,
    val progress: Int,        // 0..100
    val progressText: String,
    val progressTint: Int,    // android color int
    val showOpen: Boolean,
    val completedBytes: Long = 0L,   // for the active row, drives the smooth count-up
    val sizeBytes: Long = 0L,
    val isActive: Boolean = false,   // the single in-flight (transferring/paused) file
    val activePrefix: String = "",   // "Transferring"/"Paused" prefix for the active row
)

private val LIST_HEIGHT = 435.dp

@Composable
fun TransferHistoryScreen(
    records: List<TransferRecord>,
    filterOptions: List<String>,
    currentFilter: String,
    onFilterSelected: (String) -> Unit,
    filterLabel: (String) -> String,
    isSelectMode: Boolean,
    onSelectModeChange: (Boolean) -> Unit,
    selectedSessionIds: Set<String>,
    onToggleSelection: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    showDetail: Boolean,
    detailRecord: TransferRecord?,
    onRecordClick: (TransferRecord) -> Unit,
    onBackToList: () -> Unit,
    rowLabel: (TransferRecord) -> String,
    rowFileSummary: (TransferRecord) -> String,
    rowTimestamp: (TransferRecord) -> String,
    detailSubtitle: (TransferRecord) -> String,
    buildDetailFiles: (TransferRecord) -> List<DetailFileRow>,
    onDetailAction: (TransferRecord) -> Unit,
    onDeleteTransaction: (TransferRecord) -> Unit,
    onOpenFile: (String) -> Unit,
    onAnchorBounds: (String, IntArray) -> Unit = { _, _ -> },
    reportAnchors: Boolean = false,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    // Reports a Compose region's window bounds so the (view-based) tutorial can spotlight it. ONLY
    // active during the tutorial — onGloballyPositioned fires every scroll/layout frame, so leaving it
    // on for normal use makes the list + filter capsules lag. No anchors → plain (free) Modifier.
    val reportBounds: (String) -> Modifier = { key ->
        if (reportAnchors) Modifier.onGloballyPositioned { coords ->
            val pos = coords.positionInWindow()
            onAnchorBounds(key, intArrayOf(pos.x.toInt(), pos.y.toInt(), coords.size.width, coords.size.height))
        } else Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = dimens.radiusXl, topEnd = dimens.radiusXl))
            .background(colors.bottomSheet)
            .padding(20.dp)
            // Step 9 spotlight maps to the WHOLE sheet (its real size/position) rather than the tall
            // fixed-height list region, which sat too low on tablets.
            .then(reportBounds("list")),
    ) {
        if (showDetail && detailRecord != null) {
            DetailView(detailRecord, colors, dimens, buildDetailFiles, detailSubtitle, onBackToList, onDetailAction, onDeleteTransaction, onOpenFile, reportBounds)
        } else {
            ListView(records, filterOptions, currentFilter, onFilterSelected, filterLabel, isSelectMode, onSelectModeChange, selectedSessionIds, onToggleSelection, onDeleteSelected, onRecordClick, rowLabel, rowFileSummary, rowTimestamp, colors, dimens, reportBounds)
        }
    }
}

@Composable
private fun ListView(
    records: List<TransferRecord>,
    filterOptions: List<String>,
    currentFilter: String,
    onFilterSelected: (String) -> Unit,
    filterLabel: (String) -> String,
    isSelectMode: Boolean,
    onSelectModeChange: (Boolean) -> Unit,
    selectedSessionIds: Set<String>,
    onToggleSelection: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onRecordClick: (TransferRecord) -> Unit,
    rowLabel: (TransferRecord) -> String,
    rowFileSummary: (TransferRecord) -> String,
    rowTimestamp: (TransferRecord) -> String,
    colors: AppColors,
    dimens: Dimens,
    reportBounds: (String) -> Modifier,
) {
    // Header / selection banner
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = dimens.spaceLg), verticalAlignment = Alignment.CenterVertically) {
        if (isSelectMode) {
            Text("${selectedSessionIds.size} selected", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
            CapsuleButton(stringResource(R.string.action_delete), filled = true, enabled = selectedSessionIds.isNotEmpty(), fill = colors.error, textColor = Color.White, onClick = onDeleteSelected)
            Spacer(Modifier.width(dimens.spaceSm))
            CapsuleButton(stringResource(R.string.action_cancel), filled = false, fill = colors.card, textColor = colors.textPrimary, onClick = { onSelectModeChange(false) })
        } else {
            Text(stringResource(R.string.transfer_history_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            CapsuleButton(stringResource(R.string.action_select), filled = false, enabled = records.isNotEmpty(), fill = colors.card, textColor = colors.textPrimary, onClick = { onSelectModeChange(true) }, modifier = reportBounds("select"))
        }
    }

    // Filter capsules
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = dimens.spaceLg).then(reportBounds("filter")),
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
    ) {
        filterOptions.forEach { option ->
            val selected = option == currentFilter
            Text(
                filterLabel(option),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color.White else colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(dimens.radiusPill))
                    .background(if (selected) colors.green else colors.card)
                    .clickable { onFilterSelected(option) }
                    .padding(horizontal = dimens.spaceMd, vertical = dimens.spaceSm),
            )
        }
    }

    // List (fixed ~5-card height so the sheet stays constant)
    Box(modifier = Modifier.fillMaxWidth().height(LIST_HEIGHT)) {
        if (records.isEmpty()) {
            Text(
                stringResource(R.string.no_transfer_history_yet),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = dimens.spaceXxl),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
                itemsIndexed(records, key = { _, it -> it.sessionId }) { index, record ->
                    // First card anchors "record" (the tap target); last card anchors "recordlast" so the
                    // tutorial can frame all cards and drop its tooltip just beneath them.
                    val cardAnchor = when (index) {
                        0 -> reportBounds("record")
                        records.lastIndex -> reportBounds("recordlast")
                        else -> Modifier
                    }
                    HistoryCard(record, isSelectMode, record.sessionId in selectedSessionIds, onRecordClick, rowLabel, rowFileSummary, rowTimestamp, colors, dimens, cardAnchor)
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    record: TransferRecord,
    isSelectMode: Boolean,
    selected: Boolean,
    onRecordClick: (TransferRecord) -> Unit,
    rowLabel: (TransferRecord) -> String,
    rowFileSummary: (TransferRecord) -> String,
    rowTimestamp: (TransferRecord) -> String,
    colors: AppColors,
    dimens: Dimens,
    anchorModifier: Modifier,
) {
    val shape = RoundedCornerShape(dimens.radiusLg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(anchorModifier)
            .clip(shape)
            .background(if (selected) colors.accentMuted else colors.card)
            .border(dimens.hairlineWidth, colors.divider, shape)
            .clickable { onRecordClick(record) }
            .padding(dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(if (record.direction == "incoming") R.drawable.ic_hdr_receive else R.drawable.ic_hdr_send),
            contentDescription = null,
            tint = colors.green,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = dimens.spaceMd)) {
            Text(rowLabel(record), style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rowFileSummary(record), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rowTimestamp(record), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Text(
            statusLabel(record.status),
            style = MaterialTheme.typography.labelLarge,
            color = statusColor(colors, record.status),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DetailView(
    record: TransferRecord,
    colors: AppColors,
    dimens: Dimens,
    buildDetailFiles: (TransferRecord) -> List<DetailFileRow>,
    detailSubtitle: (TransferRecord) -> String,
    onBackToList: () -> Unit,
    onDetailAction: (TransferRecord) -> Unit,
    onDeleteTransaction: (TransferRecord) -> Unit,
    onOpenFile: (String) -> Unit,
    reportBounds: (String) -> Modifier,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = dimens.spaceLg), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(dimens.radiusMd)).clickable { onBackToList() }, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_close), tint = colors.textPrimary, modifier = Modifier.size(dimens.iconMd))
        }
        Spacer(Modifier.width(dimens.spaceMd))
        Text(stringResource(R.string.transaction_details_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        CapsuleButton(stringResource(R.string.action_delete), filled = true, fill = colors.error, textColor = Color.White, onClick = { onDeleteTransaction(record) })
    }

    Text(detailSubtitle(record), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(bottom = dimens.spaceLg))

    val files = buildDetailFiles(record)
    val firstOpenIndex = files.indexOfFirst { it.showOpen }
    Box(modifier = Modifier.fillMaxWidth().height(LIST_HEIGHT)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
            itemsIndexed(files, key = { _, it -> it.position }) { index, row ->
                DetailFileItem(row, colors, dimens, onOpenFile, if (index == firstOpenIndex) reportBounds("open") else Modifier)
            }
        }
    }

    Spacer(Modifier.height(dimens.spaceLg))
    val action = detailAction(record)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(dimens.radiusLg))
            .background(if (action.enabled) action.fill else colors.divider)
            .then(if (action.enabled) Modifier.clickable { onDetailAction(record) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(action.labelRes), style = MaterialTheme.typography.titleMedium, color = if (action.enabled) action.textColor else colors.textSecondary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DetailFileItem(
    row: DetailFileRow,
    colors: AppColors,
    dimens: Dimens,
    onOpenFile: (String) -> Unit,
    openAnchor: Modifier,
) {
    val shape = RoundedCornerShape(dimens.radiusLg)
    // Active (transferring/paused) row: count the bar + MB/percent text up smoothly between the
    // 500ms polls instead of stepping. Done/waiting rows are static.
    val fraction: Float
    val progressText: String
    if (row.isActive) {
        val animBytes by animateFloatAsState(
            targetValue = row.completedBytes.toFloat(),
            animationSpec = tween(durationMillis = 500, easing = LinearEasing),
            label = "bytes",
        )
        val size = row.sizeBytes.toFloat()
        val frac = if (size > 0f) (animBytes / size).coerceIn(0f, 1f) else 0f
        fraction = frac
        val prog = stringResource(
            R.string.history_file_progress,
            (frac * 100).toInt(),
            String.format("%.1f", animBytes / 1048576f),
            String.format("%.1f", size / 1048576f),
        )
        progressText = stringResource(R.string.history_file_progress_prefixed, row.activePrefix, prog)
    } else {
        fraction = row.progress / 100f
        progressText = row.progressText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.divider, shape)
            .padding(dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left column: file name, slim progress bar, progress caption.
        Column(modifier = Modifier.weight(1f)) {
            Text(row.fileName, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(dimens.spaceSm))
            ProgressBar(fraction = fraction, tint = Color(row.progressTint), track = colors.divider)
            Spacer(Modifier.height(dimens.spaceXs))
            Text(progressText, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        // Right: Open button, vertically centred against the whole card.
        if (row.showOpen) {
            Spacer(Modifier.width(dimens.spaceMd))
            CapsuleButton(stringResource(R.string.action_open), filled = false, fill = colors.accentMuted, textColor = colors.greenText, onClick = { onOpenFile(row.fileName) }, modifier = openAnchor)
        }
    }
}

/** Slim determinate bar (no Material stop-indicator dot). The caller animates the fraction when needed. */
@Composable
private fun ProgressBar(fraction: Float, tint: Color, track: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(tint),
        )
    }
}

@Composable
private fun CapsuleButton(
    label: String,
    filled: Boolean,
    enabled: Boolean = true,
    fill: Color,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val shape = RoundedCornerShape(dimens.radiusPill)
    val bg = if (!enabled) colors.divider else if (filled) fill else colors.card
    val border = if (filled) bg else colors.divider
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(dimens.hairlineWidth, border, shape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = dimens.spaceLg, vertical = dimens.spaceSm),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) textColor else colors.textSecondary, fontWeight = FontWeight.Bold)
    }
}

private fun statusLabel(status: String): String = status.replaceFirstChar { it.uppercase() }

private fun statusColor(colors: AppColors, status: String): Color = when (status) {
    "completed" -> colors.success
    "transferring" -> colors.info
    "pending", "paused" -> colors.warning
    "failed", "declined", "cancelled" -> colors.error
    else -> colors.textSecondary
}

private data class DetailActionStyle(val labelRes: Int, val enabled: Boolean, val fill: Color, val textColor: Color)

@Composable
private fun detailAction(record: TransferRecord): DetailActionStyle {
    val colors = AppThemeTokens.colors
    return when (record.status) {
        "completed", "declined", "cancelled" -> DetailActionStyle(R.string.action_close, true, colors.surfaceElevated, colors.textPrimary)
        // Cancel is enabled for BOTH directions: cancelTransfer() sends CANCEL_TRANSFER| for
        // outgoing and DECLINE_OFFER| for incoming, and the daemon already handles both.
        "transferring" -> DetailActionStyle(R.string.action_cancel, true, colors.error, Color.White)
        "pending" -> DetailActionStyle(R.string.history_pending_accept, false, colors.divider, colors.textSecondary)
        else -> DetailActionStyle(R.string.action_resume, true, colors.green, Color.Black)
    }
}
