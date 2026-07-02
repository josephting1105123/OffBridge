package com.joseph.clipboard.windows.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.joseph.clipboard.windows.ClipboardAppController
import com.joseph.clipboard.windows.network.control.QrControlState
import com.joseph.clipboard.windows.theme.GreenPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File

/** UI state for the QR host session lifecycle (host setup runs off the UI thread). */
private sealed interface QrUiState {
    object Loading : QrUiState
    data class Ready(
        val bitmap: ImageBitmap,
        val ssid: String?,
        val passphrase: String?,
        val hotspotActive: Boolean,
        /** Live BLE control-plane state, or null when the PC isn't advertising a control channel. */
        val controlState: StateFlow<QrControlState>?,
        /** Live AP creds (republished after a credential regeneration), or null when no control channel. */
        val creds: StateFlow<Pair<String, String>>?
    ) : QrUiState
    data class Error(val message: String) : QrUiState
}

/**
 * "Send via QR" dialog (Windows = sender). Opens an off-grid advertising window via
 * [ClipboardAppController.startQrHostSession] — which stands up a Wi-Fi Direct hotspot for the true
 * off-grid case (no shared router) and/or advertises the LAN IP for the same-network case — then
 * renders the returned payload as a QR for a nearby Android phone to scan and receive.
 *
 * Host setup blocks for ~1–4s (hotspot spin-up), so it runs in a [LaunchedEffect] behind a loading
 * state instead of during composition.
 *
 * NFC is intentionally absent on desktop — QR is the only off-grid carrier here.
 */
@Composable
fun ShowQrDialog(
    files: List<File>,
    appController: ClipboardAppController,
    onDismiss: () -> Unit
) {
    // The QR session serves only real files; the dialog must count/size the same set
    // (the raw selection can include a folder) so it never over-counts or mislabels.
    val realFiles = remember(files) { files.filter { it.exists() && it.isFile } }

    var uiState by remember(realFiles) { mutableStateOf<QrUiState>(QrUiState.Loading) }

    // Open the host session (hotspot + LAN) off the UI thread, then encode the QR.
    LaunchedEffect(realFiles) {
        uiState = QrUiState.Loading
        if (realFiles.isEmpty()) {
            uiState = QrUiState.Error(
                "No sendable files selected.\nFolders aren't supported for QR yet — choose individual files."
            )
            return@LaunchedEffect
        }
        val info = withContext(Dispatchers.IO) { appController.startQrHostSession(realFiles) }
        uiState = if (info == null) {
            QrUiState.Error(
                "Couldn't start a Wi-Fi hotspot and no shared network was found.\n" +
                    "Your Wi-Fi adapter may not support Wi-Fi Direct, or Wi-Fi is turned off."
            )
        } else {
            val bmp = try { encodeQr(info.qrJson, 660) } catch (e: Exception) { null }
            if (bmp == null) QrUiState.Error("Couldn't render the QR code.")
            else QrUiState.Ready(
                bmp, info.ssid, info.passphrase, info.hotspotActive, info.controlState, info.creds
            )
        }
    }

    // Leaving the dialog (with or without a scan) retires the advertising window and drops the
    // hotspot if no transfer is in flight. A successful scan has already claimed the session.
    DisposableEffect(Unit) {
        onDispose { appController.cancelQrHostSession() }
    }

    val totalBytes = realFiles.sumOf { it.length() }
    val sizeStr = if (totalBytes > 1024 * 1024)
        String.format("%.2f MB", totalBytes / (1024f * 1024f))
    else
        String.format("%.1f KB", totalBytes / 1024f)

    BackdropEffect()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colors.background,
            modifier = Modifier.width(420.dp).modalPopIn()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Send via QR",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground
                )
                Spacer(Modifier.height(16.dp))

                when (val s = uiState) {
                    is QrUiState.Loading -> LoadingBlock()
                    is QrUiState.Error -> ErrorBlock(s.message)
                    is QrUiState.Ready -> ReadyBlock(s, realFiles.size, sizeStr, appController)
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Done", color = MaterialTheme.colors.onPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier.heightIn(min = 160.dp).fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = GreenPrimary)
            Spacer(Modifier.height(16.dp))
            Text(
                "Setting up the hotspot…",
                fontSize = 14.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ErrorBlock(message: String) {
    Box(
        modifier = Modifier.heightIn(min = 160.dp).fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            message,
            fontSize = 14.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ReadyBlock(
    state: QrUiState.Ready,
    fileCount: Int,
    sizeStr: String,
    appController: ClipboardAppController
) {
    // Live control-plane status (null flow → the PC isn't advertising BLE; behaves like before).
    val control = state.controlState?.collectAsState()?.value
    // Live AP creds — republished after a credential regeneration so the manual-join hint + QR refresh.
    val liveCreds = state.creds?.collectAsState()?.value
    val ssid = liveCreds?.first ?: state.ssid
    val passphrase = liveCreds?.second ?: state.passphrase

    // Regenerated creds (attempt 3 rescue): re-encode the QR so a not-yet-linked phone can rescan.
    var bitmap by remember { mutableStateOf(state.bitmap) }
    LaunchedEffect(liveCreds) {
        val c = liveCreds ?: return@LaunchedEffect
        if (c.first == state.ssid && c.second == state.passphrase) return@LaunchedEffect
        val json = withContext(Dispatchers.IO) { appController.rebuildQrJson(c.first, c.second) } ?: return@LaunchedEffect
        try { bitmap = encodeQr(json, 660) } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .size(280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "Off-grid QR code",
            modifier = Modifier.fillMaxSize()
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "Scan with the Clipboard Share Android app to receive $fileCount file(s) • $sizeStr",
        fontSize = 14.sp,
        color = MaterialTheme.colors.onBackground,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(6.dp))
    if (state.hotspotActive) {
        Text(
            "No shared network needed — your phone joins this PC's Wi-Fi directly.",
            fontSize = 12.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
        if (ssid != null && passphrase != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Can't scan? Join this Wi-Fi manually:",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "$ssid  /  $passphrase",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground,
                textAlign = TextAlign.Center
            )
        }
    } else {
        Text(
            "Both devices must be on the same Wi-Fi / network.",
            fontSize = 12.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }

    // Live status from the BLE control channel — only rendered when the PC advertises one, so a PC
    // without BLE looks exactly as before.
    if (control != null) {
        Spacer(Modifier.height(14.dp))
        ControlStatusLine(control)
    }
}

/** One-line, color-coded status driven by the BLE control channel (§ live PC status). */
@Composable
private fun ControlStatusLine(control: QrControlState) {
    val (text, color) = when (control) {
        is QrControlState.Advertising ->
            "Waiting for a phone to connect over Bluetooth…" to MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
        is QrControlState.CentralConnected ->
            "Phone found — linking…" to MaterialTheme.colors.onBackground.copy(alpha = 0.8f)
        is QrControlState.Linked ->
            "Connected to ${control.phoneName} — starting Wi-Fi…" to GreenPrimary
        is QrControlState.Joining ->
            (if (control.attempt > 1) "Retrying with fresh Wi-Fi credentials — rescan the code…"
            else "Phone connected — joining this PC's Wi-Fi…") to GreenPrimary
        is QrControlState.AwaitingDial ->
            "Phone joined the Wi-Fi — connecting…" to GreenPrimary
        is QrControlState.Transferring ->
            "Transferring ✓" to GreenPrimary
        is QrControlState.ControlLost ->
            "Bluetooth link dropped — the phone can still join the Wi-Fi to receive." to
                MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
        is QrControlState.Failed -> {
            val msg = if (control.firewallSuspected)
                "The phone joined the Wi-Fi but couldn't reach this PC. Allow Clipboard Share through Windows Defender Firewall, then try again."
            else
                "The phone couldn't join this PC's Wi-Fi. Keep the phone close with Wi-Fi on, then try again."
            msg to MaterialTheme.colors.error
        }
    }
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

/** Renders [text] to a black-on-white QR [ImageBitmap] via ZXing. */
private fun encodeQr(text: String, size: Int): ImageBitmap {
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
