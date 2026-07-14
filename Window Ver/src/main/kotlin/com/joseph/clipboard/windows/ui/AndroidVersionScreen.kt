package com.joseph.clipboard.windows.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joseph.clipboard.windows.storage.TranslationManager
import com.joseph.clipboard.windows.theme.GreenPrimary
import java.awt.Desktop
import java.net.URI

/** The website's download section — opened by the "Go to download page" button below. */
private const val DOWNLOAD_URL = "https://clipboard-share-a8e.pages.dev/#download"

/** Translation lookup with an English fallback, mirroring the nav-rail label pattern in Main.kt. */
private fun tr(key: String, fallback: String): String =
    TranslationManager.getString(key).takeIf { it != key } ?: fallback

/**
 * "Download Clipboard Share for Android" screen — points the user at the website to get the
 * mobile app. Reached from the nav-rail Android item.
 */
@Composable
fun AndroidVersionScreen() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val enter by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "enter"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(32.dp)
            .graphicsLayer {
                alpha = enter
                val s = 0.92f + 0.08f * enter
                scaleX = s
                scaleY = s
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Android,
            contentDescription = null,
            modifier = Modifier.padding(bottom = 24.dp).size(80.dp),
            tint = GreenPrimary
        )
        Text(
            text = tr("download_android_heading", "Download Clipboard Share for Android"),
            color = MaterialTheme.colors.onBackground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
            text = tr(
                "download_android_blurb",
                "Get the mobile app for your phone. Sync your clipboard and send files between this computer and your device."
            ),
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
            modifier = Modifier.padding(bottom = 28.dp)
        )
        Button(
            onClick = {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI(DOWNLOAD_URL))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            colors = ButtonDefaults.buttonColors(backgroundColor = GreenPrimary)
        ) {
            Text(
                text = tr("download_open_website", "Go to download page"),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
