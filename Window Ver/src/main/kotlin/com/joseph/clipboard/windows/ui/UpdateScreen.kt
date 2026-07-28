package com.joseph.clipboard.windows.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.delay
import com.joseph.clipboard.windows.ClipboardAppController
import com.joseph.clipboard.windows.LocalThemeMode
import com.joseph.clipboard.windows.storage.TranslationManager
import com.joseph.clipboard.windows.theme.ErrorText
import com.joseph.clipboard.windows.theme.GreenPrimary
import com.joseph.clipboard.windows.theme.ThemeMode
import com.joseph.clipboard.windows.update.UpdateStatus

/** Reveal/collapse duration for the result card (and the loader glide it drives). Shared by the
 *  expand and its reverse so "Check again" folds away with the exact same motion. */
private const val REVEAL_MS = 420

/** Translation lookup with an English fallback, mirroring AndroidVersionScreen's tr() helper. */
private fun tr(key: String, fallback: String): String =
    TranslationManager.getString(key).takeIf { it != key } ?: fallback

/**
 * "Updates" screen — checks GitHub for a newer Windows build and, when one exists, downloads and
 * launches the installer entirely in-app (no browser, no visiting the website). Reached from the
 * nav-rail Updates item, directly below Android. Always re-checks fresh on open, ignoring the
 * passive startup check's cooldown (see [ClipboardAppController.checkForUpdates]).
 */
@Composable
fun UpdateScreen(appController: ClipboardAppController) {
    val status = appController.updateStatus.collectAsState().value
    var showConfirm by remember { mutableStateOf(false) }

    // Each check cycle (screen open, or a manual re-check) bumps this. The effect kicks off the
    // check and holds the loading animation for a minimum of 1s before the result may reveal —
    // just enough for the loader not to flash, without delaying the result card.
    var checkCycle by remember { mutableStateOf(0) }
    var minLoadElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(checkCycle) {
        minLoadElapsed = false
        appController.checkForUpdates(force = true)
        delay(1000)
        minLoadElapsed = true
    }

    val resolved = status is UpdateStatus.UpToDate ||
        status is UpdateStatus.UpdateAvailable ||
        status is UpdateStatus.CheckFailed
    // Reveal the result only once the 1s minimum has passed AND the check has actually resolved.
    val showResult = minLoadElapsed && resolved
    // Hold the last resolved status so the card keeps its content while it animates back out
    // (e.g. on a re-check the status briefly drops to Checking mid-exit).
    var lastResult by remember { mutableStateOf<UpdateStatus?>(null) }
    LaunchedEffect(status) { if (resolved) lastResult = status }

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
        // The liquid-capsule loader (recreated from the reference clip) is the screen's always-on
        // hero — it keeps sloshing in every state. During the loading phase it sits low with the
        // status label beneath it; when the result reveals, the expanding card pushes it up.
        LiquidCapsuleLoader(modifier = Modifier.padding(bottom = 28.dp))

        // Loading phase: the receive pill's per-letter staggered label. Fades/shrinks out on reveal.
        AnimatedVisibility(
            visible = !showResult,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(REVEAL_MS, easing = FastOutLinearInEasing)),
        ) {
            val loadingText = if (status is UpdateStatus.Downloading)
                tr("update_downloading", "Downloading update…")
            else
                tr("update_checking", "Checking for updates…")
            StaggeredLabel(text = loadingText, color = MaterialTheme.colors.onBackground)
        }

        // Result phase: the card opens from its middle as the loader above glides up. The expand
        // accelerates into the finish (FastOutLinearIn — no slow decelerating tail), and the exit is
        // the exact same motion reversed (same easing/duration, collapsing back toward centre), so
        // "Check again" folds the card away and slides the loader back down symmetrically.
        AnimatedVisibility(
            visible = showResult,
            enter = fadeIn(tween(300, delayMillis = 100)) +
                expandVertically(tween(REVEAL_MS, easing = FastOutLinearInEasing), expandFrom = Alignment.CenterVertically),
            exit = fadeOut(tween(200)) +
                shrinkVertically(tween(REVEAL_MS, easing = FastOutLinearInEasing), shrinkTowards = Alignment.CenterVertically),
        ) {
            when (val s = lastResult) {
                is UpdateStatus.UpToDate -> UpdateResultCard(
                    version = s.currentVersion,
                    upToDate = true,
                    notes = null,
                    onDownload = null,
                    onCheckAgain = { checkCycle++ },
                )
                is UpdateStatus.UpdateAvailable -> UpdateResultCard(
                    version = s.version,
                    upToDate = false,
                    notes = s.notes.takeIf { it.isNotBlank() },
                    onDownload = { showConfirm = true },
                    onCheckAgain = null,
                )
                is UpdateStatus.CheckFailed -> UpdateFailedCard(
                    onRetry = { checkCycle++ },
                )
                else -> {}
            }
        }
    }

    if (showConfirm) {
        BackdropEffect() // blur the main window's content while this OS dialog is up
        val themeModeState = LocalThemeMode.current
        DialogWindow(
            onCloseRequest = { showConfirm = false },
            title = tr("update_confirm_title", "Update OffBridge"),
            resizable = false,
            state = rememberDialogState(width = 380.dp, height = 220.dp)
        ) {
            MaterialTheme(colors = if (themeModeState.value == ThemeMode.LIGHT) lightColors() else darkColors()) {
                Surface(color = MaterialTheme.colors.background) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            tr(
                                "update_confirm_body",
                                "This will close OffBridge and launch the installer. Continue?"
                            ),
                            color = MaterialTheme.colors.onSurface,
                            fontSize = 15.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(onClick = { showConfirm = false }) {
                                Text(TranslationManager.getString("action_cancel"))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = {
                                    showConfirm = false
                                    appController.downloadAndInstallUpdate()
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = GreenPrimary)
                            ) {
                                Text(tr("update_confirm_continue", "Continue"), color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The resolved-state glass card: "Latest version vX.X.X" with either a Download CTA (an update is
 * available) or an up-to-date confirmation + Check-again. Brand glass card + brand buttons so it
 * reads as one system with the rest of the app.
 */
@Composable
private fun UpdateResultCard(
    version: String,
    upToDate: Boolean,
    notes: String?,
    onDownload: (() -> Unit)?,
    onCheckAgain: (() -> Unit)?,
) {
    GlassCard(modifier = Modifier.widthIn(max = 360.dp), contentPadding = PaddingValues(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                tr("update_latest_version_label", "Latest version"),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "v$version",
                style = MaterialTheme.typography.h5,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onSurface,
            )

            if (upToDate) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = GreenPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tr("update_up_to_date", "No update available"),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.height(20.dp))
                AppSecondaryButton(
                    text = tr("update_check_again", "Check again"),
                    onClick = { onCheckAgain?.invoke() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                if (notes != null) {
                    Spacer(Modifier.height(16.dp))
                    GlassDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        notes,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(20.dp))
                AppButton(
                    text = tr("update_download_button", "Download & Install"),
                    onClick = { onDownload?.invoke() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    tr(
                        "update_smartscreen_note",
                        "Windows may show a security prompt on first run — click \"More info\" then \"Run anyway\"."
                    ),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The failed-check glass card: reason line + a Try-again brand button. Ring keeps flowing above. */
@Composable
private fun UpdateFailedCard(onRetry: () -> Unit) {
    GlassCard(modifier = Modifier.widthIn(max = 360.dp), contentPadding = PaddingValues(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = ErrorText,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    tr("update_check_failed", "Couldn't check for updates"),
                    style = MaterialTheme.typography.subtitle1,
                    color = MaterialTheme.colors.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(20.dp))
            AppSecondaryButton(
                text = tr("update_try_again", "Try again"),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
