package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.components.HowToUseChip
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialOverlay
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.TutorialStep
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.rememberTutorialAnchors
import com.EntropicSoftwareLab.ClipboardShare.ui.tutorial.tutorialAnchor

/**
 * Compose Receive tab. NFC-receive and QR-scan are two boxes in a row (icon above label); the device
 * name field exits editing on a tap outside or the keyboard Done action.
 */
@Composable
fun ReceiveScreen(
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    nfcUnavailable: Boolean,
    onOpenCamera: () -> Unit,
    onNfcReceive: () -> Unit,
    showTutorial: Boolean,
    onShowTutorial: () -> Unit,
    onTutorialDismiss: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val anchors = rememberTutorialAnchors()
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(dimens.spaceXl),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 72.dp, bottom = dimens.spaceXl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_hdr_receive),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg),
                )
                Text(
                    stringResource(R.string.receive_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                HowToUseChip(onClick = onShowTutorial)
            }

            // Device name (mirrors System Status; sets how senders see this device).
            OutlinedTextField(
                value = deviceName,
                onValueChange = onDeviceNameChange,
                label = { Text(stringResource(R.string.device_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(bottom = dimens.spaceLg),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.green,
                    cursorColor = colors.green,
                    focusedLabelColor = colors.green,
                ),
            )

            // Receive methods — two boxes in a row (icon above label), card colour + hairline.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd),
            ) {
                ReceiveGridButton(
                    iconRes = R.drawable.ic_camera,
                    label = stringResource(R.string.receive_open_camera),
                    modifier = Modifier.weight(1f).tutorialAnchor(anchors, "camera"),
                    onClick = onOpenCamera,
                )
                ReceiveGridButton(
                    iconRes = R.drawable.ic_nfc,
                    label = stringResource(R.string.receive_nfc),
                    enabled = !nfcUnavailable,
                    modifier = Modifier.weight(1f).tutorialAnchor(anchors, "nfc"),
                    onClick = onNfcReceive,
                )
            }

            if (nfcUnavailable) {
                Text(
                    stringResource(R.string.receive_nfc_unavailable_device),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    modifier = Modifier.padding(top = dimens.spaceSm),
                )
            }
        }

        if (showTutorial) {
            val steps = listOf(
                TutorialStep(
                    targetKey = "camera",
                    text = stringResource(R.string.receive_tut_qr),
                ),
                TutorialStep(
                    targetKey = "nfc",
                    text = stringResource(
                        if (nfcUnavailable) R.string.receive_tut_nfc_unavailable else R.string.receive_tut_nfc,
                    ),
                    hideSkipButton = true,
                ),
            )
            TutorialOverlay(
                steps = steps,
                totalSteps = steps.size,
                boundsMap = anchors,
                onDismiss = onTutorialDismiss,
                onComplete = onTutorialDismiss,
            )
        }
    }
}

@Composable
private fun ReceiveGridButton(
    iconRes: Int,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val shape = RoundedCornerShape(dimens.radiusLg)
    Column(
        modifier = modifier
            .height(100.dp)
            .clip(shape)
            .alpha(if (enabled) 1f else 0.45f)
            .background(colors.card)
            .border(dimens.hairlineWidth, colors.divider, shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(dimens.spaceMd),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = colors.green,
            modifier = Modifier.size(dimens.iconLg),
        )
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
