package com.EntropicSoftwareLab.ClipboardShare

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.EntropicSoftwareLab.ClipboardShare.pairing.PairQrPayload
import com.EntropicSoftwareLab.ClipboardShare.pairing.QrPairClient
import com.EntropicSoftwareLab.ClipboardShare.pairing.QrPairSession
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.QrPairMode
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.QrPairScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.QrPairStatus
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.QrPairUi
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QR pairing screen host. Owns everything stateful — the displayed pairing window, the camera, the
 * permission prompt and the handshake — and feeds [QrPairScreen] a plain snapshot, matching how the
 * fragments drive the other Compose screens.
 *
 * Both directions work here: this device can show a code for someone else to scan, or scan theirs.
 * Whichever way round, no PIN is compared and no Accept prompt appears — the scan proves physical
 * proximity and the two devices prove the derived AES-256 key to each other.
 */
class QrPairActivity : AppCompatActivity() {

    private var ui by mutableStateOf(QrPairUi(mode = QrPairMode.SHOW, status = QrPairStatus.Preparing))

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }

    // One scan may start exactly one handshake. Without this every analyzed frame carrying the same
    // code would fire another pair() and burn the one-shot token on our own retries.
    private val scanClaimed = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        ui = ui.copy(cameraGranted = granted)
        if (!granted) {
            ui = ui.copy(status = QrPairStatus.Error(getString(R.string.qr_pair_camera_denied)))
        }
    }

    /** The host side of a pairing that completed while our code was on screen. */
    private val qrPairedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val name = intent?.getStringExtra("SENDER_NAME")?.takeIf { it.isNotBlank() }
                ?: intent?.getStringExtra("SENDER_IP")
                ?: return
            ui = ui.copy(status = QrPairStatus.Success(name))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.getString("theme_mode", "System Default")) {
                "Light" -> AppCompatDelegate.MODE_NIGHT_NO
                "Dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        ContextCompat.registerReceiver(
            this,
            qrPairedReceiver,
            IntentFilter(PairingDaemon.ACTION_QR_PAIRED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        ui = ui.copy(cameraGranted = hasCameraPermission())
        openWindow(withHotspot = false)

        setContentView(ComposeView(this).apply {
            setContent {
                AppTheme {
                    QrPairScreen(
                        ui = ui,
                        onBack = { finish() },
                        onModeChange = ::switchMode,
                        onRefreshCode = { openWindow(withHotspot = ui.hotspotActive) },
                        onStartHotspot = { openWindow(withHotspot = true) },
                        onRequestCamera = { cameraPermission.launch(Manifest.permission.CAMERA) },
                        onPreviewReady = ::bindCamera,
                        onRetry = ::retry,
                    )
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        // Retire the code so a dial-in after this screen is gone is denied, and drop any hotspot it
        // raised. Both are safe to call when nothing is open.
        QrPairSession.close()
        runCatching { unregisterReceiver(qrPairedReceiver) }
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        barcodeScanner.close()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun switchMode(mode: QrPairMode) {
        scanClaimed.set(false)
        if (mode == QrPairMode.SCAN) {
            // Showing a code while scanning would let a user pair the device with itself, and the
            // camera has the screen anyway. Retire the window on the way in.
            QrPairSession.close()
            ui = ui.copy(mode = mode, status = QrPairStatus.Ready, cameraGranted = hasCameraPermission())
            if (!hasCameraPermission()) cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            cameraProvider?.unbindAll()
            ui = ui.copy(mode = mode)
            openWindow(withHotspot = false)
        }
    }

    private fun retry() {
        scanClaimed.set(false)
        if (ui.mode == QrPairMode.SCAN) {
            ui = ui.copy(status = QrPairStatus.Ready, cameraGranted = hasCameraPermission())
        } else {
            openWindow(withHotspot = false)
        }
    }

    /** Opens (or re-opens) the displayed pairing window. Hotspot setup blocks, so it runs off-thread. */
    private fun openWindow(withHotspot: Boolean) {
        ui = ui.copy(mode = QrPairMode.SHOW, status = QrPairStatus.Preparing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { QrPairSession.open(applicationContext, withHotspot) }
            ui = when (result) {
                is QrPairSession.OpenResult.Ready -> {
                    val bitmap = withContext(Dispatchers.Default) { encodeQr(result.info.qrText) }
                    ui.copy(
                        status = QrPairStatus.Ready,
                        qr = bitmap?.asImageBitmap(),
                        expiresAtMs = result.info.expiresAtMs,
                        ssid = result.info.ssid,
                        passphrase = result.info.passphrase,
                        hotspotActive = result.info.hotspotActive,
                    )
                }

                is QrPairSession.OpenResult.NoRoute ->
                    ui.copy(status = QrPairStatus.Error(getString(R.string.qr_pair_no_route)))

                is QrPairSession.OpenResult.HotspotUnavailable ->
                    ui.copy(status = QrPairStatus.Error(getString(R.string.qr_pair_hotspot_failed)))
            }
        }
    }

    private fun bindCamera(previewView: PreviewView) {
        if (!hasCameraPermission()) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = try { providerFuture.get() } catch (e: Exception) { return@addListener }
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyzeFrame(proxy) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || scanClaimed.get()) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val raw = barcode.rawValue ?: continue
                    // Ignore anything that isn't one of our pairing codes — including our own
                    // off-grid file-transfer QR, which the same camera will happily read.
                    if (!PairQrPayload.looksLikePairCode(raw)) continue
                    if (scanClaimed.compareAndSet(false, true)) onCodeScanned(raw)
                    break
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun onCodeScanned(raw: String) {
        when (val parsed = PairQrPayload.parse(raw, System.currentTimeMillis())) {
            is PairQrPayload.ParseResult.Valid -> runHandshake(parsed.payload)
            is PairQrPayload.ParseResult.Expired ->
                fail(getString(R.string.qr_pair_scanned_expired))
            is PairQrPayload.ParseResult.UnsupportedVersion ->
                fail(getString(R.string.qr_pair_unsupported_version))
            is PairQrPayload.ParseResult.Invalid ->
                fail(getString(R.string.qr_pair_invalid_code))
        }
    }

    private fun runHandshake(payload: PairQrPayload) {
        ui = ui.copy(status = QrPairStatus.Pairing)
        lifecycleScope.launch {
            val result = QrPairClient.pair(applicationContext, payload, TransferDaemon.customDeviceName)
            ui = when (result) {
                is QrPairClient.Result.Success -> ui.copy(status = QrPairStatus.Success(result.peerName))
                is QrPairClient.Result.Denied -> errorState(R.string.qr_pair_denied)
                is QrPairClient.Result.Expired -> errorState(R.string.qr_pair_scanned_expired)
                is QrPairClient.Result.Unreachable -> errorState(R.string.qr_pair_unreachable)
                is QrPairClient.Result.JoinRefused -> errorState(R.string.qr_pair_join_refused)
                is QrPairClient.Result.ConfirmationFailed -> errorState(R.string.qr_pair_confirmation_failed)
                is QrPairClient.Result.Failed -> errorState(R.string.qr_pair_failed)
            }
        }
    }

    private fun errorState(messageRes: Int): QrPairUi =
        ui.copy(status = QrPairStatus.Error(getString(messageRes)))

    private fun fail(message: String) {
        ui = ui.copy(status = QrPairStatus.Error(message))
    }

    /** Null when the payload is too dense for a QR — surfaced as an error rather than a blank card. */
    private fun encodeQr(content: String): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX, hints)
        val pixels = IntArray(QR_SIZE_PX * QR_SIZE_PX)
        for (y in 0 until QR_SIZE_PX) {
            for (x in 0 until QR_SIZE_PX) {
                pixels[y * QR_SIZE_PX + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, QR_SIZE_PX, QR_SIZE_PX, Bitmap.Config.RGB_565)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private companion object {
        /**
         * Matches the off-grid send screen's encode size (QrDisplayFragment). The code is drawn into a
         * 280dp frame, which is 840px on a 3x display, so 720 was being upscaled and softening the
         * module edges a scanner has to resolve. Not a size the layout reads — purely the bitmap's own
         * resolution.
         */
        const val QR_SIZE_PX = 800
    }
}
