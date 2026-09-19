package com.joseph.clipboard.windows.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.joseph.clipboard.windows.storage.TranslationManager
import com.joseph.clipboard.windows.theme.Dimens
import com.joseph.clipboard.windows.theme.LocalExtraColors
import java.awt.Desktop
import java.net.URI

/** The website's download section — opened by the "Go to download page" button below. */
private const val DOWNLOAD_URL = "https://clipboard-share-a8e.pages.dev/#download"

/** Hero icon chip — the BentoTile accent-chip recipe scaled up to carry an otherwise empty screen. */
private val HERO_CHIP = 76.dp
private val HERO_ICON = 40.dp

/** Translation lookup with an English fallback, mirroring the nav-rail label pattern in Main.kt. */
private fun tr(key: String, fallback: String): String =
    TranslationManager.getString(key).takeIf { it != key } ?: fallback

/**
 * "Download OffBridge for Android" screen — points the user at the website to get the
 * mobile app. Reached from the nav-rail Android item.
 *
 * Composed like the Updates page next door: an accent hero above a glass card holding the copy and
 * the single CTA, so both nav destinations read as one system (glass card, brand button, shared
 * type scale and spacing tokens) instead of stock Material.
 */
@Composable
fun AndroidVersionScreen() {
    val extra = LocalExtraColors.current
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
            .padding(Dimens.spaceXxl)
            .graphicsLayer {
                alpha = enter
                val s = 0.92f + 0.08f * enter
                scaleX = s
                scaleY = s
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(HERO_CHIP)
                .clip(RoundedCornerShape(Dimens.radiusXl))
                .background(extra.accentMuted),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Android,
                contentDescription = null,
                tint = extra.green,
                modifier = Modifier.size(HERO_ICON)
            )
        }
        Spacer(Modifier.height(Dimens.spaceXl))
        GlassCard(
            modifier = Modifier.widthIn(max = 360.dp),
            contentPadding = PaddingValues(Dimens.spaceXl)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = tr("download_android_heading", "Download OffBridge for Android"),
                    style = MaterialTheme.typography.h5,
                    color = MaterialTheme.colors.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Dimens.spaceMd))
                Text(
                    text = tr(
                        "download_android_blurb",
                        "Get the mobile app for your phone. Sync your clipboard and send files between this computer and your device."
                    ),
                    style = MaterialTheme.typography.body1,
                    color = extra.textSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Dimens.spaceXl))
                AppButton(
                    text = tr("download_open_website", "Go to download page"),
                    onClick = {
                        try {
                            if (Desktop.isDesktopSupported() &&
                                Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                            ) {
                                Desktop.getDesktop().browse(URI(DOWNLOAD_URL))
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
