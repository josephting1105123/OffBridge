package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.components.AppButton
import com.EntropicSoftwareLab.ClipboardShare.ui.components.AppCard
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/** The website's download section — opened by the "Go to download page" button below. */
private const val DOWNLOAD_URL = "https://clipboard-share-a8e.pages.dev/#download"

/** Hero icon chip — the BentoTile accent-chip recipe scaled up to carry an otherwise empty screen. */
private val HERO_CHIP = 76.dp
private val HERO_ICON = 40.dp

/**
 * "Download Clipboard Share for Windows" screen — points the user at the website to get the
 * desktop companion. Reached from the power-menu "Windows Version" item.
 *
 * Composed like the Updates page: an accent hero above a glass card holding the copy and the single
 * CTA, so both destinations read as one system (glass card, brand button, shared type scale and
 * spacing tokens). Mirrors the Windows client's Android page.
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppThemeTokens.colors.background)
            .padding(AppThemeTokens.dimens.spaceXxl)
            .graphicsLayer {
                alpha = enter
                val s = 0.92f + 0.08f * enter
                scaleX = s
                scaleY = s
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(HERO_CHIP)
                .clip(RoundedCornerShape(AppThemeTokens.dimens.radiusXl))
                .background(AppThemeTokens.colors.accentMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_computer),
                contentDescription = null,
                tint = AppThemeTokens.colors.green,
                modifier = Modifier.size(HERO_ICON),
            )
        }
        Spacer(Modifier.height(AppThemeTokens.dimens.spaceXl))
        AppCard(modifier = Modifier.widthIn(max = 360.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.download_windows_heading),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppThemeTokens.colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(AppThemeTokens.dimens.spaceMd))
                Text(
                    text = stringResource(R.string.download_windows_blurb),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppThemeTokens.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(AppThemeTokens.dimens.spaceXl))
                AppButton(
                    text = stringResource(R.string.download_open_website),
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
