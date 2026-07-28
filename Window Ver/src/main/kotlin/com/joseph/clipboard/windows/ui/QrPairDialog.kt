package com.joseph.clipboard.windows.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.joseph.clipboard.windows.ClipboardAppController
import com.joseph.clipboard.windows.network.QrPairCoordinator
import com.joseph.clipboard.windows.storage.DiagLog
import com.joseph.clipboard.windows.storage.TranslationManager
import com.joseph.clipboard.windows.theme.Dimens
import com.joseph.clipboard.windows.theme.LocalExtraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage

/** UI state for the pairing-code window. */
private sealed interface PairQrUiState {
    object Loading : PairQrUiState
    data class Ready(val info: QrPairCoordinator.PairQrInfo, val bitmap: ImageBitmap) : PairQrUiState
    data class Error(val message: String) : PairQrUiState
}

/**
 * "Pair with QR" dialog. The PC shows a pairing credential; a phone scans it and the two devices
 * pair with no PIN to compare and no Accept prompt — the scan itself is the consent, and both sides
 * prove the derived AES-256 key to each other before either stores anything.
 *
 * There is no scan button that works here: Windows ships no camera stack, so the action is shown
 * disabled with the reason rather than hidden, which would leave a user hunting for it.
 */
@Composable
fun QrPairDialog(
    appController: ClipboardAppController,
    onDismiss: () -> Unit
) {
    val colors = LocalExtraColors.current
    var uiState by remember { mutableStateOf<PairQrUiState>(PairQrUiState.Loading) }
    // Bumped by refreshing an expired code; each change re-opens the window.
    var openEpoch by remember { mutableStateOf(0) }
    val pairedWith by appController.qrPairedDevice.collectAsState()

    LaunchedEffect(openEpoch) {
        uiState = PairQrUiState.Loading
        val info = withContext(Dispatchers.IO) { appController.startQrPairSession() }
        uiState = if (info == null) {
            PairQrUiState.Error(str("qr_pair_no_route", "This PC isn't on a Wi-Fi network, so there's nothing for the phone to connect to."))
        } else {
            val bmp = try { encodePairQr(info.qrText, 660) } catch (e: Exception) {
                DiagLog.w("QR pair encode failed: ${e.message}")
                null
            }
            if (bmp == null) PairQrUiState.Error(str("qr_pair_render_failed", "Couldn't render the pairing code."))
            else PairQrUiState.Ready(info, bmp)
        }
    }

    // Closing the window retires the code, so a dial-in after the dialog is gone is denied.
    DisposableEffect(Unit) {
        onDispose { appController.cancelQrPairSession() }
    }

    BackdropEffect()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colors.background,
            modifier = Modifier.width(420.dp).modalPopIn()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Dimens.spaceXl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    str("qr_pair_title", "Pair with QR"),
                    style = MaterialTheme.typography.h6,
                    color = MaterialTheme.colors.onBackground
                )
                Spacer(Modifier.height(Dimens.spaceMd))

                when (val s = uiState) {
                    is PairQrUiState.Loading -> PairLoadingBlock()
                    is PairQrUiState.Error -> PairErrorBlock(s.message)
                    is PairQrUiState.Ready ->
                        if (pairedWith != null) PairedBlock(pairedWith!!)
                        else PairCodeBlock(
                            state = s,
                            onRefresh = { openEpoch++ },
                        )
                }

                Spacer(Modifier.height(Dimens.spaceLg))
                AppButton(
                    text = str("action_done", "Done"),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PairLoadingBlock() {
    Box(
        modifier = Modifier.heightIn(min = 200.dp).fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = LocalExtraColors.current.green)
            Spacer(Modifier.height(Dimens.spaceMd))
            Text(
                str("qr_pair_preparing", "Preparing your pairing code…"),
                style = MaterialTheme.typography.body2,
                color = LocalExtraColors.current.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PairErrorBlock(message: String) {
    Box(
        modifier = Modifier.heightIn(min = 200.dp).fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            message,
            style = MaterialTheme.typography.body2,
            color = LocalExtraColors.current.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

/** Terminal success state — the phone finished the handshake while this dialog was open. */
@Composable
private fun PairedBlock(deviceName: String) {
    val colors = LocalExtraColors.current
    Column(
        modifier = Modifier.heightIn(min = 200.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = colors.green,
            modifier = Modifier.size(Dimens.iconLg)
        )
        Spacer(Modifier.height(Dimens.spaceMd))
        Text(
            str("qr_pair_success", "Paired with %s").format(deviceName),
            style = MaterialTheme.typography.subtitle1,
            color = MaterialTheme.colors.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Dimens.spaceXs))
        Text(
            str("qr_pair_success_hint", "You can now send files and share the clipboard with it."),
            style = MaterialTheme.typography.caption,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PairCodeBlock(
    state: PairQrUiState.Ready,
    onRefresh: () -> Unit,
) {
    val colors = LocalExtraColors.current

    // Live countdown. A code that silently stops working is the worst failure mode here, so the
    // remaining time is on screen and the expired state offers the fix rather than just reporting it.
    var remainingMs by remember(state.info) { mutableStateOf(state.info.expiresAtMs - System.currentTimeMillis()) }
    LaunchedEffect(state.info) {
        while (remainingMs > 0) {
            delay(500)
            remainingMs = state.info.expiresAtMs - System.currentTimeMillis()
        }
    }
    val expired = remainingMs <= 0

    Box(
        modifier = Modifier
            .size(260.dp)
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = state.bitmap,
            contentDescription = str("qr_pair_code_desc", "Pairing QR code"),
            // Fading rather than hiding the stale code keeps the layout still while the refresh
            // action appears underneath it.
            modifier = Modifier.fillMaxSize().alpha(if (expired) 0.15f else 1f)
        )
        if (expired) {
            Text(
                str("qr_pair_expired", "Code expired"),
                style = MaterialTheme.typography.subtitle1,
                color = Color.Black,
                textAlign = TextAlign.Center
            )
        }
    }

    Spacer(Modifier.height(Dimens.spaceMd))
    if (expired) {
        AppTonalButton(
            text = str("qr_pair_refresh", "Show a new code"),
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Text(
            str("qr_pair_scan_prompt", "Scan this with Clipboard Share on the other device."),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Dimens.spaceXs))
        Text(
            str("qr_pair_expires_in", "Expires in %s").format(formatCountdown(remainingMs)),
            style = MaterialTheme.typography.caption,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
    }

    Spacer(Modifier.height(Dimens.spaceMd))
    // Pairing is same-network only: no AP is raised, so there are no credentials to print and no
    // hotspot to offer. Stating the requirement is what replaces both — "nothing happens when I scan"
    // is the failure this dialog exists to prevent.
    if (!expired) {
        Text(
            str("qr_pair_same_network", "Both devices must be on the same Wi-Fi."),
            style = MaterialTheme.typography.caption,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
    }

    // Windows has no camera stack, so this direction of the pairing only works one way. Shown
    // disabled with the reason so the capability gap is explicit instead of mysteriously absent.
    Spacer(Modifier.height(Dimens.spaceMd))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.PhotoCamera,
            contentDescription = null,
            tint = colors.textSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(Dimens.iconSm)
        )
        Spacer(Modifier.width(Dimens.spaceSm))
        Text(
            str("qr_pair_no_camera", "Scanning needs a camera — scan this PC's code from your phone instead."),
            style = MaterialTheme.typography.caption,
            color = colors.textSecondary.copy(alpha = 0.7f),
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f)
        )
    }
}

/** mm:ss for the countdown; a bare seconds number reads as an error code at a glance. */
private fun formatCountdown(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Translation lookup with an English fallback, matching the rest of the desktop UI. */
private fun str(key: String, fallback: String): String =
    TranslationManager.getString(key).takeIf { it != key } ?: fallback

/** Renders [text] to a black-on-white QR [ImageBitmap] via ZXing. */
private fun encodePairQr(text: String, size: Int): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1
    )
    val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    for (y in 0 until size) {
        for (x in 0 until size) {
            image.setRGB(x, y, if (matrix[x, y]) black else white)
        }
    }
    return image.toComposeImageBitmap()
}
