package com.EntropicSoftwareLab.ClipboardShare.ui.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.offgrid.NfcAntennaLocator
import com.EntropicSoftwareLab.ClipboardShare.offgrid.NfcTapGuideView
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.FilePreviewKind
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.FilePreviewUi
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.SendDeviceUi
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.SendToDevicesScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/*
 * Full-screen replicas of the real Send-Via destinations, rendered inside the Send-Files tutorial
 * (TutorialStep.fullScreenContent). They look exactly like the real screens but are non-interactive —
 * the overlay's tap-catcher advances the tour; callbacks are no-ops and nothing networks.
 *
 *  - Connected Devices: reuses the REAL [SendToDevicesScreen] with demo state (pixel-exact, auto-tracks
 *    any future restyle of that screen).
 *  - QR / NFC: the real off-grid screens are legacy XML (fragment_qr_display.xml), so these are faithful
 *    Compose copies — flat, matching the real XML (the bevel refactor hasn't reached those screens).
 */

/** Step 6 — exact copy of the Connected Devices (Send to Devices) screen, one device pre-selected. */
@Composable
internal fun TutorialReplicaConnectedDevices() {
    SendToDevicesScreen(
        previews = listOf(
            FilePreviewUi("file 1.mp4", FilePreviewKind.OTHER),
            FilePreviewUi("file 2.pdf", FilePreviewKind.OTHER),
            FilePreviewUi("file 3.jpg", FilePreviewKind.IMAGE),
        ),
        devices = listOf(
            SendDeviceUi(ip = "10.0.0.2", name = "Android Phone", isWindows = false),
            SendDeviceUi(ip = "10.0.0.3", name = "My PC", isWindows = true),
        ),
        selectedIps = setOf("10.0.0.2"),
        loading = false,
        sending = false,
        onBack = {},
        onToggleDevice = {},
        onToggleSelectAll = {},
        onSend = {},
        onGoToPairing = {},
    )
}

/** Step 7 — Compose copy of the QR send screen (fragment_qr_display.xml): title, status, white frame. */
@Composable
internal fun TutorialReplicaQr() {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    Column(
        modifier = Modifier.fillMaxSize().background(colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Text(
            stringResource(R.string.offgrid_send_title_qr),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.spaceXl),
        )
        Spacer(Modifier.height(dimens.spaceMd))
        Text(
            stringResource(R.string.offgrid_status_waiting_for_scan),
            fontSize = 15.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.spaceXl),
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(dimens.radiusMd))
                    .background(Color.White)
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Black regardless of theme: this sits on a hardcoded white plate, and ic_qr_frame's
                // own android:tint would otherwise resolve to the near-white colorControlNormal and
                // disappear on the dark theme.
                Icon(
                    painterResource(R.drawable.ic_qr_frame),
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        ReplicaCancelButton()
    }
}

/** New NFC step — Compose copy of the NFC send presentation: title, subtitle, the real tap-guide view. */
@Composable
internal fun TutorialReplicaNfc() {
    val colors = AppThemeTokens.colors
    val dimens = AppThemeTokens.dimens
    Column(
        modifier = Modifier.fillMaxSize().background(colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Text(
            stringResource(R.string.offgrid_send_title_nfc),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.spaceXl),
        )
        Spacer(Modifier.height(dimens.spaceSm))
        Text(
            stringResource(R.string.nfc_guide_subtitle_send),
            fontSize = 15.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.spaceXl),
        )
        // The real tap-guide view (rippling antenna). Needs a bounded height — weight(1f) gives it one.
        AndroidView(
            factory = { ctx ->
                NfcTapGuideView(ctx).apply { antennaFraction = NfcAntennaLocator.antennaVerticalFraction(ctx) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = dimens.spaceXl, vertical = dimens.spaceSm),
        )
        ReplicaCancelButton()
    }
}

/** The dark cancel button pinned to the bottom of the QR / NFC screens (mirrors the XML #333333 button). */
@Composable
private fun ReplicaCancelButton() {
    val dimens = AppThemeTokens.dimens
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = dimens.spaceXl, end = dimens.spaceXl, bottom = dimens.spaceXl, top = dimens.spaceMd)
            .height(64.dp)
            .clip(RoundedCornerShape(dimens.radiusLg))
            .background(Color(0xFF333333)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.action_cancel),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}
