package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.components.AppButton
import com.EntropicSoftwareLab.ClipboardShare.ui.components.AppCard
import com.EntropicSoftwareLab.ClipboardShare.ui.components.AppSecondaryButton
import com.EntropicSoftwareLab.ClipboardShare.ui.components.LiquidCapsuleLoader
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.ClipboardShare.update.ApkUpdateInstaller
import com.EntropicSoftwareLab.ClipboardShare.update.InstallSource
import com.EntropicSoftwareLab.ClipboardShare.update.UpdateRepository
import com.EntropicSoftwareLab.ClipboardShare.update.UpdateStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Reveal/collapse duration for the result card (and the loader glide it drives). */
private const val REVEAL_MS = 420

/** The app's Play Store listing — the update action for Play-installed builds. */
private const val PLAY_URL =
    "https://play.google.com/store/apps/details?id=com.EntropicSoftwareLab.ClipboardShare"

/**
 * "Updates" screen — checks the latest GitHub release for a newer Android build (pre-warmed at launch
 * via [UpdateRepository]). When one exists, the action routes on how this build was installed: a Play
 * install opens the Play listing; a sideloaded install downloads the release APK and hands it to the
 * system installer in-app. Same liquid-capsule loader and checking / up-to-date / available / failed
 * states as the Windows client.
 */
@Composable
fun UpdateScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Shared, process-wide status — a silent check runs at app launch (SplashActivity) to pre-warm it.
    val status by UpdateRepository.status.collectAsState()

    // Each check cycle (screen open = 0, or a manual re-check) bumps this. Opening the page reuses the
    // launch-time pre-warm (force = false); "Check again"/"Try again" forces a fresh fetch. Either way
    // the loader is held for a minimum of 1s before the result may reveal — just enough not to flash,
    // without delaying the result card.
    var checkCycle by remember { mutableStateOf(0) }
    var minLoadElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(checkCycle) {
        minLoadElapsed = false
        UpdateRepository.check(context, force = checkCycle > 0)
        delay(1000)
        minLoadElapsed = true
    }

    // True while the sideloaded APK is downloading — the loader keeps flowing with a "Downloading…"
    // label and the result card steps aside until the system installer is launched.
    var downloading by remember { mutableStateOf(false) }
    val startApkUpdate: (String) -> Unit = start@{ apkUrl ->
        // Guard against a double-tap firing two overlapping downloads that would truncate-write the
        // same cache file — mirrors the atomic guard the Windows client uses around its own
        // download-and-install (see ClipboardAppController.downloadAndInstallUpdate). Setting
        // `downloading` synchronously here (before launching) rather than as the coroutine's first
        // line closes the gap regardless of dispatcher timing.
        if (downloading) return@start
        downloading = true
        scope.launch {
            try {
                val apk = ApkUpdateInstaller.download(context, apkUrl)
                val launched = ApkUpdateInstaller.install(context, apk)
                if (!launched) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_allow_install_hint),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.update_download_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                downloading = false
            }
        }
    }

    val resolved = status is UpdateStatus.UpToDate ||
        status is UpdateStatus.UpdateAvailable ||
        status is UpdateStatus.CheckFailed
    // Reveal the result once the 1s minimum has passed, the check has resolved, and we're not mid-
    // download (during a download the loader takes over with the "Downloading…" label).
    val showResult = minLoadElapsed && resolved && !downloading
    // Hold the last resolved status so the card keeps its content while it animates back out.
    var lastResult by remember { mutableStateOf<UpdateStatus?>(null) }
    LaunchedEffect(status) { if (resolved) lastResult = status }

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
        LiquidCapsuleLoader(modifier = Modifier.padding(bottom = 28.dp))

        AnimatedVisibility(
            visible = !showResult,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)) +
                shrinkVertically(tween(REVEAL_MS, easing = FastOutLinearInEasing)),
        ) {
            Text(
                text = stringResource(
                    if (downloading) R.string.update_downloading else R.string.update_checking,
                ),
                color = AppThemeTokens.colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        AnimatedVisibility(
            visible = showResult,
            enter = fadeIn(tween(300, delayMillis = 100)) +
                expandVertically(
                    tween(REVEAL_MS, easing = FastOutLinearInEasing),
                    expandFrom = Alignment.CenterVertically,
                ),
            exit = fadeOut(tween(200)) +
                shrinkVertically(
                    tween(REVEAL_MS, easing = FastOutLinearInEasing),
                    shrinkTowards = Alignment.CenterVertically,
                ),
        ) {
            when (val s = lastResult) {
                is UpdateStatus.UpToDate -> UpdateResultCard(
                    version = s.currentVersion,
                    upToDate = true,
                    primaryText = null,
                    onPrimary = null,
                    onCheckAgain = { checkCycle++ },
                )
                is UpdateStatus.UpdateAvailable -> {
                    // Play install → Play listing (matching signature). Sideloaded install with a
                    // release APK available → in-app download+install. Sideloaded but no APK yet →
                    // fall back to the Play listing (the only actionable target until one is published).
                    val useApk = remember(s.apkUrl) { !InstallSource.isFromPlay(context) && s.apkUrl != null }
                    UpdateResultCard(
                        version = s.version,
                        upToDate = false,
                        primaryText = stringResource(
                            if (useApk) R.string.update_download_button else R.string.update_play_button,
                        ),
                        onPrimary = {
                            if (useApk) startApkUpdate(s.apkUrl!!) else openPlayStore(context)
                        },
                        onCheckAgain = null,
                    )
                }
                is UpdateStatus.CheckFailed -> UpdateFailedCard(onRetry = { checkCycle++ })
                else -> {}
            }
        }
    }
}

/**
 * The resolved-state card: "Current/Latest version vX.Y.Z" with either an up-to-date confirmation +
 * Check-again ([upToDate] = true), or an Update-available note + a primary action ([primaryText]).
 */
@Composable
private fun UpdateResultCard(
    version: String,
    upToDate: Boolean,
    primaryText: String?,
    onPrimary: (() -> Unit)?,
    onCheckAgain: (() -> Unit)?,
) {
    AppCard(modifier = Modifier.widthIn(max = 360.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    if (upToDate) R.string.update_current_version_label
                    else R.string.update_latest_version_label,
                ),
                color = AppThemeTokens.colors.textSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "v$version",
                color = AppThemeTokens.colors.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )

            if (upToDate) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check_circle),
                        contentDescription = null,
                        tint = AppThemeTokens.colors.greenText,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.update_up_to_date),
                        color = AppThemeTokens.colors.textSecondary,
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(20.dp))
                AppSecondaryButton(
                    text = stringResource(R.string.update_check_again),
                    onClick = { onCheckAgain?.invoke() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_available_label),
                    color = AppThemeTokens.colors.greenText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(20.dp))
                AppButton(
                    text = primaryText.orEmpty(),
                    onClick = { onPrimary?.invoke() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The failed-check card: reason line + a Try-again button. Loader keeps flowing above. */
@Composable
private fun UpdateFailedCard(onRetry: () -> Unit) {
    AppCard(modifier = Modifier.widthIn(max = 360.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = AppThemeTokens.colors.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.update_check_failed),
                    color = AppThemeTokens.colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(20.dp))
            AppSecondaryButton(
                text = stringResource(R.string.update_try_again),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun openPlayStore(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_URL)))
    } catch (e: Exception) {
        Toast.makeText(
            context,
            context.getString(R.string.settings_toast_could_not_open_browser),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
