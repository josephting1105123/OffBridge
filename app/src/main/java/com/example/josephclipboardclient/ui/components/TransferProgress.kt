package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.TabularFigures
import java.util.Locale
import kotlin.math.roundToInt

enum class TransferPhase { TRANSFERRING, COMPLETED, FAILED, CANCELLED }

/** Immutable snapshot of one transfer's progress. */
data class TransferProgressData(
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val fileIndex: Int = 1,
    val fileCount: Int = 1,
    val phase: TransferPhase = TransferPhase.TRANSFERRING,
)

/**
 * The defining transfer surface: determinate bar + tabular figures (% · sent/total · speed), per-file
 * count, explicit terminal states, and a Retry on failure. Numbers use tabular figures so they don't
 * reflow as digits change width.
 */
@Composable
fun TransferProgress(
    data: TransferProgressData,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    AppCard(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = data.fileName,
                style = MaterialTheme.typography.titleMedium,
                color = AppThemeTokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(AppThemeTokens.dimens.spaceSm))
            TransferStatusChip(state = data.phase.toChipState())
        }

        Spacer(Modifier.height(AppThemeTokens.dimens.spaceSm))

        val fraction = when {
            data.phase == TransferPhase.COMPLETED -> 1f
            data.totalBytes > 0L -> (data.bytesTransferred.toFloat() / data.totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
        val barColor = when (data.phase) {
            TransferPhase.TRANSFERRING -> AppThemeTokens.colors.green
            TransferPhase.COMPLETED -> AppThemeTokens.colors.success
            TransferPhase.FAILED -> AppThemeTokens.colors.error
            TransferPhase.CANCELLED -> AppThemeTokens.colors.textSecondary
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = barColor,
            trackColor = AppThemeTokens.colors.divider,
        )

        Spacer(Modifier.height(AppThemeTokens.dimens.spaceXs))

        if (data.fileCount > 1) {
            Text(
                text = "File ${data.fileIndex} of ${data.fileCount}",
                style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
                color = AppThemeTokens.colors.textSecondary,
            )
            Spacer(Modifier.height(AppThemeTokens.dimens.spaceXxs))
        }
        Text(
            text = statsLine(data, fraction),
            style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
            color = AppThemeTokens.colors.textSecondary,
        )

        if (data.phase == TransferPhase.FAILED && onRetry != null) {
            Spacer(Modifier.height(AppThemeTokens.dimens.spaceSm))
            AppButton(text = "Retry", onClick = onRetry)
        }
    }
}

private fun TransferPhase.toChipState(): TransferState = when (this) {
    TransferPhase.TRANSFERRING -> TransferState.SENDING
    TransferPhase.COMPLETED -> TransferState.COMPLETED
    TransferPhase.FAILED -> TransferState.FAILED
    TransferPhase.CANCELLED -> TransferState.CANCELLED
}

private fun statsLine(d: TransferProgressData, fraction: Float): String {
    val pct = (fraction * 100f).roundToInt()
    return when (d.phase) {
        TransferPhase.COMPLETED -> "${formatBytes(d.totalBytes)} · Complete"
        TransferPhase.CANCELLED -> "$pct% · Cancelled at ${formatBytes(d.bytesTransferred)}"
        TransferPhase.FAILED -> "$pct% · ${formatBytes(d.bytesTransferred)} / ${formatBytes(d.totalBytes)} · Failed"
        TransferPhase.TRANSFERRING ->
            "$pct%  ·  ${formatBytes(d.bytesTransferred)} / ${formatBytes(d.totalBytes)}  ·  ${formatBytes(d.bytesPerSecond)}/s"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
