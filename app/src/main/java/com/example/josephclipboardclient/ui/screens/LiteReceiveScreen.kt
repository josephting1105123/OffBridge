package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

@Composable
fun LiteReceiveScreen(
    deviceName: String,
    onBack: () -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onOpenCamera: () -> Unit
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(dimens.spaceXl)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 72.dp, bottom = dimens.spaceXl),
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
                    stringResource(R.string.lite_receive_files),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
            }

            // Receive animation: custom ai-loader translated to Jetpack Compose
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(bottom = dimens.spaceLg),
                contentAlignment = Alignment.Center
            ) {
                com.EntropicSoftwareLab.ClipboardShare.ui.components.ReceivingLoader(
                    text = stringResource(R.string.receiving_label)
                )
            }

            OutlinedTextField(
                value = deviceName,
                onValueChange = onDeviceNameChange,
                label = { Text(stringResource(R.string.device_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = dimens.spaceXl),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.green,
                    cursorColor = colors.green,
                    focusedLabelColor = colors.green
                )
            )

            // A fixed gap, not weight(1f): this Column is verticalScroll, where a weighted child
            // resolves against an infinite main axis and collapses to 0 — it wouldn't pin anything.
            Spacer(modifier = Modifier.height(dimens.spaceXl))

            ReceiveGridButton(
                iconRes = R.drawable.ic_camera,
                label = stringResource(R.string.receive_open_camera),
                modifier = Modifier.fillMaxWidth().height(dimens.buttonHeight * 1.5f),
                onClick = onOpenCamera
            )
        }
    }
}
