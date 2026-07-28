package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import kotlinx.coroutines.delay

/**
 * Ceilings for the two big square-ish blocks on this screen.
 *
 * Both used to be sized purely as a fraction of screen width, which is fine on a phone and breaks on
 * a tablet: 82% of a 1000dp-wide window is an 820dp square, taller than the viewport, so the pairing
 * code could not be shown at all. Width alone is the wrong input — what has to fit is height.
 *
 * [QR_MAX_SIZE] matches the flat 280dp frame the off-grid send screen has always used
 * (res/layout/fragment_qr_display.xml). A QR is read by a camera at arm's length; past that size more
 * pixels buy nothing, so a hard ceiling costs no scannability. The camera preview gets a looser one
 * because a bigger viewfinder genuinely is easier to aim, and its cap sits above any phone width so
 * phones keep the full-width preview they have today.
 */
private val QR_MAX_SIZE = 280.dp
private val SCAN_PREVIEW_MAX_WIDTH = 420.dp

/** Which half of the pairing screen is on show. */
enum class QrPairMode { SHOW, SCAN }

/** What the screen is doing right now; the activity owns every transition between these. */
sealed class QrPairStatus {
    /** Opening the window. */
    object Preparing : QrPairStatus()

    /** A code is on screen / the camera is live — the normal resting state. */
    object Ready : QrPairStatus()

    /** A code was scanned and the handshake is running. */
    object Pairing : QrPairStatus()

    data class Success(val deviceName: String) : QrPairStatus()

    /** [message] is already resolved and user-facing. [retryable] shows the try-again action. */
    data class Error(val message: String, val retryable: Boolean = true) : QrPairStatus()
}

/** Everything the screen renders, assembled by [com.EntropicSoftwareLab.ClipboardShare.QrPairActivity]. */
data class QrPairUi(
    val mode: QrPairMode,
    val status: QrPairStatus,
    val qr: ImageBitmap? = null,
    val expiresAtMs: Long = 0L,
    val cameraGranted: Boolean = false,
)

/**
 * QR pairing screen (Pro). One code on screen, one camera — pairing happens in whichever direction
 * the two people find easier, and neither side compares a PIN: the scan is the consent, and the two
 * devices prove the derived AES-256 key to each other before either stores anything.
 */
@Composable
fun QrPairScreen(
    ui: QrPairUi,
    onBack: () -> Unit,
    onModeChange: (QrPairMode) -> Unit,
    onRefreshCode: () -> Unit,
    onRequestCamera: () -> Unit,
    onPreviewReady: (PreviewView) -> Unit,
    onRetry: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(dimens.spaceXl),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 48.dp, bottom = dimens.spaceXl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = stringResource(R.string.action_close),
                tint = colors.textPrimary,
                modifier = Modifier
                    .size(dimens.touchTarget)
                    .clip(RoundedCornerShape(dimens.radiusMd))
                    .clickable { onBack() }
                    .padding(dimens.spaceSm),
            )
            Text(
                stringResource(R.string.qr_pair_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                modifier = Modifier.padding(start = dimens.spaceSm),
            )
        }

        when (val status = ui.status) {
            is QrPairStatus.Preparing -> CenteredBlock {
                CircularProgressIndicator(color = colors.green)
                Text(
                    stringResource(R.string.qr_pair_preparing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = dimens.spaceMd),
                )
            }

            is QrPairStatus.Pairing -> CenteredBlock {
                CircularProgressIndicator(color = colors.green)
                Text(
                    stringResource(R.string.qr_pair_connecting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = dimens.spaceMd),
                )
            }

            is QrPairStatus.Success -> CenteredBlock {
                Icon(
                    painter = painterResource(R.drawable.ic_check_circle),
                    contentDescription = null,
                    tint = colors.green,
                    modifier = Modifier.size(dimens.iconLg),
                )
                Text(
                    stringResource(R.string.qr_pair_success, status.deviceName),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = dimens.spaceMd),
                )
                Text(
                    stringResource(R.string.qr_pair_success_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = dimens.spaceXs),
                )
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.green, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().padding(top = dimens.spaceXl).height(dimens.buttonHeight),
                ) { Text(stringResource(R.string.action_done), fontWeight = FontWeight.Bold) }
            }

            is QrPairStatus.Error -> CenteredBlock {
                Text(
                    status.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                if (status.retryable) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth().padding(top = dimens.spaceLg).height(dimens.buttonHeight),
                    ) { Text(stringResource(R.string.qr_pair_try_again), color = colors.green) }
                }
            }

            is QrPairStatus.Ready -> when (ui.mode) {
                QrPairMode.SHOW -> ShowCodeBlock(ui, onModeChange, onRefreshCode)
                QrPairMode.SCAN -> ScanBlock(ui, onModeChange, onRequestCamera, onPreviewReady)
            }
        }
    }
}

@Composable
private fun CenteredBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun ShowCodeBlock(
    ui: QrPairUi,
    onModeChange: (QrPairMode) -> Unit,
    onRefreshCode: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    // Live countdown. A code that silently stops working is the worst failure here, so the time left
    // is on screen and expiry offers the fix instead of just reporting the problem.
    var remainingMs by remember(ui.expiresAtMs) { mutableStateOf(ui.expiresAtMs - System.currentTimeMillis()) }
    LaunchedEffect(ui.expiresAtMs) {
        while (remainingMs > 0) {
            delay(500)
            remainingMs = ui.expiresAtMs - System.currentTimeMillis()
        }
    }
    val expired = remainingMs <= 0

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // BoxWithConstraints rather than chaining widthIn onto fillMaxWidth: fillMaxWidth hands down a
        // FIXED width constraint, and a later widthIn(max=) gets coerced back up to that fixed minimum,
        // so the ceiling would silently do nothing. Reading maxWidth and picking the size outright is
        // the only form of this that actually caps.
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size((maxWidth * 0.82f).coerceAtMost(QR_MAX_SIZE))
                    .clip(RoundedCornerShape(dimens.radiusLg))
                    .background(Color.White)
                    .padding(dimens.spaceMd),
                contentAlignment = Alignment.Center,
            ) {
                ui.qr?.let {
                    Image(
                        bitmap = it,
                        contentDescription = stringResource(R.string.qr_pair_code_desc),
                        // Fading rather than hiding keeps the layout still while the refresh action
                        // appears underneath.
                        modifier = Modifier.fillMaxSize().alpha(if (expired) 0.12f else 1f),
                    )
                }
                if (expired) {
                    Text(
                        stringResource(R.string.qr_pair_expired),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Black,
                    )
                }
            }
        }

        if (expired) {
            Button(
                onClick = onRefreshCode,
                colors = ButtonDefaults.buttonColors(containerColor = colors.green, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().padding(top = dimens.spaceLg).height(dimens.buttonHeight),
            ) { Text(stringResource(R.string.qr_pair_refresh), fontWeight = FontWeight.Bold) }
        } else {
            Text(
                stringResource(R.string.qr_pair_scan_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = dimens.spaceLg),
            )
            Text(
                stringResource(R.string.qr_pair_expires_in, formatCountdown(remainingMs)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = dimens.spaceXs),
            )
        }

        // Pairing is same-network only, so there are no AP credentials to print and no hotspot to
        // offer. What replaces both is saying the requirement up front, since "nothing happens when I
        // scan" is the failure this screen has to prevent.
        Text(
            stringResource(R.string.qr_pair_same_network_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = dimens.spaceLg),
        )

        Button(
            onClick = { onModeChange(QrPairMode.SCAN) },
            colors = ButtonDefaults.buttonColors(containerColor = colors.green, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().padding(top = dimens.spaceMd).height(dimens.buttonHeight),
        ) { Text(stringResource(R.string.qr_pair_scan_action), fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ScanBlock(
    ui: QrPairUi,
    onModeChange: (QrPairMode) -> Unit,
    onRequestCamera: () -> Unit,
    onPreviewReady: (PreviewView) -> Unit,
) {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Same unbounded-growth problem as the code block above, and worse: aspectRatio(0.85f) makes
        // the preview TALLER than it is wide, so a 1000dp-wide tablet window asked for ~1180dp of
        // height. Cap the width above any phone's, so phones keep today's full-width viewfinder and
        // only large windows are held back.
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(maxWidth.coerceAtMost(SCAN_PREVIEW_MAX_WIDTH))
                    .aspectRatio(0.85f)
                    .clip(RoundedCornerShape(dimens.radiusLg))
                    .background(colors.card),
                contentAlignment = Alignment.Center,
            ) {
                if (ui.cameraGranted) {
                    AndroidView(
                        factory = { ctx -> PreviewView(ctx).also(onPreviewReady) },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(dimens.spaceXl),
                    ) {
                        Text(
                            stringResource(R.string.qr_pair_camera_needed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = onRequestCamera,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.green, contentColor = Color.Black),
                            modifier = Modifier.padding(top = dimens.spaceLg),
                        ) { Text(stringResource(R.string.qr_pair_camera_grant), fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        Text(
            stringResource(R.string.qr_pair_scan_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = dimens.spaceLg),
        )

        OutlinedButton(
            onClick = { onModeChange(QrPairMode.SHOW) },
            modifier = Modifier.fillMaxWidth().padding(top = dimens.spaceLg).height(dimens.buttonHeight),
        ) { Text(stringResource(R.string.qr_pair_show_action), color = colors.green) }
    }
}

/** mm:ss — a bare seconds count reads as an error code at a glance. */
private fun formatCountdown(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
