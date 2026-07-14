package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/** The website's download section — opened by the "Go to download page" button below. */
private const val DOWNLOAD_URL = "https://clipboard-share-a8e.pages.dev/#download"

/**
 * "Download Clipboard Share for Windows" screen — points the user at the website to get the
 * desktop companion. Reached from the power-menu "Windows Version" item.
 */
@Composable
fun WindowsVersionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val enter by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "enter",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppThemeTokens.colors.background)
            .padding(AppThemeTokens.dimens.spaceXxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = enter
                val s = 0.92f + 0.08f * enter
                scaleX = s
                scaleY = s
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_computer),
                contentDescription = null,
                tint = AppThemeTokens.colors.greenText,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(AppThemeTokens.dimens.spaceXl))
            Text(
                text = stringResource(R.string.download_windows_heading),
                color = AppThemeTokens.colors.textPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(AppThemeTokens.dimens.spaceLg))
            Text(
                text = stringResource(R.string.download_windows_blurb),
                color = AppThemeTokens.colors.textSecondary,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(AppThemeTokens.dimens.spaceXxl))
            Button(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)))
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.settings_toast_could_not_open_browser),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppThemeTokens.colors.greenText),
                modifier = Modifier.widthIn(min = 200.dp),
            ) {
                Text(
                    text = stringResource(R.string.download_open_website),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
