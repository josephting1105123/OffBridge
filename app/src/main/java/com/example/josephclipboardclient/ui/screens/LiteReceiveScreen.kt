package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
                Icon(
                    painter = painterResource(R.drawable.ic_hdr_receive),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.headerIcon).padding(end = dimens.spaceLg)
                )
                Text(
                    stringResource(R.string.lite_receive_files),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
            }

            // Receive animation: an expanding radar pulse + the word "Receiving" with a shimmer sweep,
            // signalling the device is in receive mode. Colours are chosen per theme (deep emerald on
            // light, bright green on dark) so it reads on both. (Reference clip in Context and References
            // File is a video I can't decode; this is an on-brand interpretation.)
            val dark = isSystemInDarkTheme()
            val receivingBase = if (dark) Color(0xFF4ADE88) else Color(0xFF047857)
            val receivingHi = if (dark) Color.White else Color(0xFF34D399)
            val infiniteTransition = rememberInfiniteTransition(label = "receive")

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(bottom = dimens.spaceLg),
                contentAlignment = Alignment.Center
            ) {
                val ringT by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "ring"
                )
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .scale(0.5f + ringT)
                        .clip(CircleShape)
                        .background(receivingBase.copy(alpha = (1f - ringT) * 0.22f))
                )

                val sweep by infiniteTransition.animateFloat(
                    initialValue = -260f,
                    targetValue = 520f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1600, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "sweep"
                )
                val shimmer = Brush.linearGradient(
                    colors = listOf(receivingBase, receivingHi, receivingBase),
                    start = Offset(sweep, 0f),
                    end = Offset(sweep + 260f, 0f),
                )
                Text(
                    text = stringResource(R.string.receiving_label),
                    style = MaterialTheme.typography.displaySmall.copy(brush = shimmer),
                    fontWeight = FontWeight.Bold
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
