package com.EntropicSoftwareLab.ClipboardShare.ui.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.sendvia.QrIcon
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.DeviceCardRow
import com.EntropicSoftwareLab.ClipboardShare.ui.formatTotalSize
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.SELECTION_BADGE_SIZE
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.SELECTION_CHECK_ICON
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/** Demo total shown by the fake Send-Via sheet when no real files are queued (~1.2 GB). */
internal const val TUTORIAL_DEMO_TOTAL_BYTES = 1_288_490_189L

/*
 * Fake UI rendered inside tutorial spotlights. Themed via AppThemeTokens so every card matches the
 * REAL screen components (FilePreviewCard, SendViaSheet, QrIcon, DeviceCardRow) in BOTH light and dark.
 */

/**
 * Fake device card for the pairing tutorial. Delegates to the real [DeviceCardRow] so it is pixel-for-
 * pixel the same size as a live card; [showPairButton] toggles the (reserved-footprint) action button.
 * The 12.dp horizontal padding cancels the tutorial spotlight's 12.dp pad so the card's width matches a
 * real on-screen [DeviceCardRow] exactly.
 */
@Composable
fun TutorialPlaceholderDevice(showPairButton: Boolean = false) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        DeviceCardRow(
            name = "Android Phone",
            isWindows = false,
            isPaired = false,
            showButton = showPairButton,
        )
    }
}

/**
 * Fake three-tile "Send Via" sheet (Connected Devices / QR / NFC) mirroring the REAL
 * [com.EntropicSoftwareLab.ClipboardShare.sendvia.SendViaSheet]: a floating island card (capped width,
 * side/bottom margins, fully rounded, hairline border) with a "Send Via" title above a Row of three
 * equal-weight tiles (icon centred above its label), each a raised surfaceElevated box with a divider
 * border and a green icon tint.
 */
@Composable
fun TutorialPlaceholderSendVia(
    connectedButtonModifier: Modifier = Modifier,
    qrButtonModifier: Modifier = Modifier,
    nfcButtonModifier: Modifier = Modifier,
    totalSizeBytes: Long = TUTORIAL_DEMO_TOTAL_BYTES,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val shape = RoundedCornerShape(dimens.radiusXl)
    Column(
        modifier = Modifier
            // Mirrors the real SendViaSheet's floating-island chain exactly (margins, cap, radius,
            // hairline) so the tutorial replica and the live sheet stay pixel-identical.
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .padding(start = dimens.spaceMd, end = dimens.spaceMd, bottom = dimens.spaceMd)
            .clip(shape)
            .background(colors.bottomSheet)
            .border(dimens.hairlineWidth, colors.hairline, shape)
            .padding(dimens.spaceLg),
    ) {
        Text(
            stringResource(R.string.send_via_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        // Total-size line mirroring the real sheet; a demo value stands in when no files are queued.
        Text(
            stringResource(R.string.send_via_size_selected, formatTotalSize(totalSizeBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = dimens.spaceXs, bottom = dimens.spaceLg),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd),
        ) {
            SendViaTile(label = stringResource(R.string.send_via_connected_devices), tileModifier = connectedButtonModifier) {
                Icon(
                    painterResource(R.drawable.ic_device),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.iconLg),
                )
            }
            SendViaTile(label = stringResource(R.string.send_via_qr), tileModifier = qrButtonModifier) {
                QrIcon(modifier = Modifier.size(dimens.iconLg), tint = colors.green)
            }
            SendViaTile(label = stringResource(R.string.send_via_nfc), tileModifier = nfcButtonModifier) {
                Icon(
                    painterResource(R.drawable.ic_nfc),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.iconLg),
                )
            }
        }
    }
}

/**
 * One "Send Via" tile: icon centred above its label inside a raised surfaceElevated box with a divider
 * border. Mirrors the real SendViaSheet's SendViaOption (104.dp tall, weight(1f), radiusLg,
 * surfaceElevated bg, divider border). [tileModifier] lets the cursor-reveal attach an
 * onGloballyPositioned probe.
 */
@Composable
private fun RowScope.SendViaTile(
    label: String,
    tileModifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val shape = RoundedCornerShape(dimens.radiusLg)
    Column(
        modifier = tileModifier
            .weight(1f)
            .height(104.dp)
            .clip(shape)
            .background(colors.surfaceElevated)
            .border(dimens.hairlineWidth, colors.divider, shape)
            .padding(dimens.spaceMd),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(dimens.iconLg), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(dimens.spaceSm))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * Three fake file cards on the Send Files screen, rendered inside a tutorial spotlight. Top-left aligned
 * so they overlay where the REAL first grid row sits (matching the live `SelectedFilesGrid`). Mirrors
 * the REAL `FilePreviewCard`: a card with the X (unselected) / checkmark (selected)
 * top-right badge. Themed via [AppThemeTokens] so it matches light AND dark.
 */
@Composable
fun TutorialPlaceholderFiles(selected: Boolean = false) {
    // Centred as a group so the spotlight band frames all three files symmetrically. 8dp gaps + 96dp
    // cards keep all three fully on-screen even on ~360dp-wide phones.
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("file 1.mp4", "file 2.pdf", "file 3.jpg").forEach { name ->
            TutorialFakeFileCard(name = name, selected = selected)
        }
    }
}

/**
 * One fake selected-file card. Fixed 96dp wide (matches the real grid's adaptive minSize), but
 * otherwise a faithful copy of the real `FilePreviewCard`: a card (with a 2.dp green
 * border when [selected]) carrying the file icon/name and the top-right X (unselected) / checkmark
 * (selected) badge — the checkmark uses the same shrunk size constants as the real card.
 */
@Composable
private fun TutorialFakeFileCard(name: String, selected: Boolean) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val shape = RoundedCornerShape(dimens.radiusMd)

    val borderColor = if (selected) colors.green else colors.divider
    val borderWidth = if (selected) 2.dp else dimens.hairlineWidth
    Box(
        modifier = Modifier
            .width(96.dp)
            .aspectRatio(0.85f)
            .clip(shape)
            .background(colors.card)
            .border(borderWidth, borderColor, shape),
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
                Icon(
                    Icons.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(dimens.iconLg),
                )
            }
            Spacer(Modifier.height(dimens.spaceXs))
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (selected) {
            // Selection checkmark (top-right), mirroring the real card's selected state (shrunk 5%).
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(dimens.spaceXxs)
                    .size(SELECTION_BADGE_SIZE)
                    .clip(CircleShape)
                    .background(colors.green),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(SELECTION_CHECK_ICON),
                )
            }
        } else {
            // Quick-delete X badge (top-right), mirroring the real card's normal state.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(dimens.spaceXxs)
                    .size(24.dp)
                    .clip(RoundedCornerShape(dimens.radiusSm))
                    .background(Color(0x80000000)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(dimens.iconSm),
                )
            }
        }
    }
}
