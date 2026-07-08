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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.components.HowToUseChip
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.withInterNumerals
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialOverlay
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialStep
import com.EntropicSoftwareLab.ClipboardShare.ui.modal.backdropSource
import com.EntropicSoftwareLab.ClipboardShare.ui.modal.rememberBackdropState
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.rememberTutorialAnchors
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.tutorialAnchor

/**
 * Compose Clipboard tab. Tapping a recent-copy card opens a full-text detail overlay (back arrow +
 * scrollable full text + a bottom Copy button); copying happens from there, not from the list tap.
 */
@Composable
fun ClipboardScreen(
    history: List<String>,
    acceptSync: Boolean,
    onAcceptSyncChange: (Boolean) -> Unit,
    onPushCurrent: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (String) -> Unit,
    showTutorial: Boolean,
    onShowTutorial: () -> Unit,
    onTutorialDismiss: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val anchors = rememberTutorialAnchors()
    val backdrop = rememberBackdropState()
    var detailEntry by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxSize().backdropSource(backdrop).padding(dimens.spaceXl)) {
            // Header: icon + title + How-to-use chip
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 72.dp, bottom = dimens.spaceXl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_hdr_history),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg),
                )
                Text(
                    stringResource(R.string.history_and_push_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                HowToUseChip(onClick = onShowTutorial)
            }

            // Accept incoming clipboard toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = dimens.spaceLg)
                    .tutorialAnchor(anchors, "accept"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.accept_incoming_clipboard),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = acceptSync,
                    onCheckedChange = onAcceptSyncChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = colors.green,
                    ),
                )
            }

            Text(
                stringResource(R.string.history_share_sheet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = dimens.spaceLg),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimens.hairlineWidth)
                    .background(colors.divider),
            )
            Spacer(Modifier.height(dimens.spaceLg))

            Text(
                stringResource(R.string.recent_copies_hint),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = dimens.spaceLg),
            )

            if (history.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = dimens.spaceXxl),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
                ) {
                    items(history, key = { it }) { entry ->
                        ClipboardCard(entry = entry, colors = colors, dimens = dimens, onOpen = { detailEntry = it }, onDelete = onDelete)
                    }
                }
            }

            Spacer(Modifier.height(dimens.spaceLg))

            // Push current clipboard
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = dimens.spaceLg)
                    .height(64.dp)
                    .clip(RoundedCornerShape(dimens.radiusLg))
                    .background(colors.green)
                    .tutorialAnchor(anchors, "push")
                    .clickable { onPushCurrent() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.action_push_current_clipboard),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (showTutorial) {
            val steps = listOf(
                TutorialStep(targetKey = "push", text = stringResource(R.string.history_tutorial_push)),
                TutorialStep(
                    targetKey = "accept",
                    text = stringResource(R.string.history_tutorial_accept_switch),
                    hideSkipButton = true,
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

        detailEntry?.let { entry ->
            ClipboardDetail(
                entry = entry,
                colors = colors,
                dimens = dimens,
                onBack = { detailEntry = null },
                onCopy = {
                    onCopy(entry)
                    detailEntry = null
                },
            )
        }
    }
}

@Composable
internal fun ClipboardCard(
    entry: String,
    colors: com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppColors,
    dimens: com.EntropicSoftwareLab.ClipboardShare.ui.theme.Dimens,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.radiusLg))
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.divider, RoundedCornerShape(dimens.radiusLg))
            .clickable { onOpen(entry) }
            .padding(dimens.spaceLg),
    ) {
        Text(
            entry.withInterNumerals(),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(end = 40.dp),
        )
        // Wrapped in an IconButton so the tap target is >=48dp while the visible badge stays 32dp.
        IconButton(
            onClick = { onDelete(entry) },
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(dimens.radiusMd))
                    .background(Color(0x80000000)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_delete), tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Full-screen detail for one clipboard entry: back arrow, the complete text, and a Copy button. */
@Composable
internal fun ClipboardDetail(
    entry: String,
    colors: com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppColors,
    dimens: com.EntropicSoftwareLab.ClipboardShare.ui.theme.Dimens,
    onBack: () -> Unit,
    onCopy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(dimens.spaceXl),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 72.dp, bottom = dimens.spaceLg),
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
            Text(
                stringResource(R.string.copy_detail_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(dimens.radiusLg))
                .background(colors.card)
                .border(dimens.hairlineWidth, colors.divider, RoundedCornerShape(dimens.radiusLg))
                .verticalScroll(rememberScrollState())
                .padding(dimens.spaceLg),
        ) {
            Text(
                entry.withInterNumerals(),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
            )
        }

        Spacer(Modifier.height(dimens.spaceLg))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(dimens.radiusLg))
                .background(colors.green)
                .clickable { onCopy() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.copy_detail_button),
                style = MaterialTheme.typography.titleMedium,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
