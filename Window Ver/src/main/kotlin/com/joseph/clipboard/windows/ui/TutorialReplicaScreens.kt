package com.joseph.clipboard.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joseph.clipboard.windows.theme.Dimens
import com.joseph.clipboard.windows.theme.GreenPrimary
import com.joseph.clipboard.windows.theme.InfoColor

/*
 * Full-screen replicas of the real Send-Via destinations for the Send-Files tutorial
 * (TutorialStep.fullScreenContent). The real Windows flow shows a CENTERED modal dialog, so these
 * render a centered dialog-styled card (not a small floating placeholder), reusing the existing safe
 * tutorial content — no networking, no real hotspot. The overlay's tap-catcher advances the tour.
 */

/**
 * Step 5 — centered replica of the real "Send Via" chooser dialog (title + two surface buttons +
 * caption), matching the real Windows [SendViaDialog] position/size instead of a placeholder pinned
 * over the device-list anchor. [connectedButtonModifier]/[qrButtonModifier] let the cursor-reveal
 * animation (steps 6-7) probe the button the finger taps.
 */
@Composable
internal fun TutorialReplicaSendVia(
    connectedButtonModifier: Modifier = Modifier,
    qrButtonModifier: Modifier = Modifier,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colors.background,
            elevation = Dimens.elevModal,
            modifier = Modifier.width(380.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Dimens.spaceXl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Send Via",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground,
                )
                // Total-size line mirroring the real SendViaDialog (demo value in the tutorial).
                Text(
                    sendViaSizeLabel(TUTORIAL_DEMO_TOTAL_BYTES),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = Dimens.spaceMd),
                )
                Box(
                    modifier = connectedButtonModifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(Dimens.radiusMd))
                        .background(MaterialTheme.colors.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Connected Devices", color = MaterialTheme.colors.onSurface)
                }
                Spacer(Modifier.height(Dimens.spaceSm))
                Box(
                    modifier = qrButtonModifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(Dimens.radiusMd))
                        .background(MaterialTheme.colors.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("QR", color = MaterialTheme.colors.onSurface)
                }
                Spacer(Modifier.height(Dimens.spaceMd))
                Text(
                    "QR shows a code for a nearby Android phone to scan and receive over your local network.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Step 6 — centered replica of the "Send to Devices" dialog (file sink + device card + Send). */
@Composable
internal fun TutorialReplicaConnectedDevices() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colors.background,
            elevation = Dimens.elevModal,
            modifier = Modifier.width(500.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(Dimens.spaceXl)) {
                Text(
                    "Send to Devices",
                    style = MaterialTheme.typography.h5,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.padding(bottom = Dimens.spaceLg),
                )
                TutorialPlaceholderConnectedDevices()
                Spacer(Modifier.height(Dimens.spaceXl))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(Dimens.radiusSm))
                        .background(GreenPrimary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Send", style = MaterialTheme.typography.button, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Step 7 — centered replica of the "Send via QR" dialog (title + QR + caption + Done). */
@Composable
internal fun TutorialReplicaQr() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colors.background,
            elevation = Dimens.elevModal,
            modifier = Modifier.width(420.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Dimens.spaceXl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Send via QR",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground,
                )
                Spacer(Modifier.height(Dimens.spaceLg))
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(Dimens.radiusMd))
                        .background(Color.White)
                        .padding(16.dp),
                ) {
                    FakeQrGlyph()
                }
                Spacer(Modifier.height(Dimens.spaceLg))
                Text(
                    "Scan with the Clipboard Share Android app to receive your files over the local network.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Dimens.spaceXl))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(Dimens.radiusSm))
                        .background(GreenPrimary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Done", style = MaterialTheme.typography.button, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Step 13 (before-tap) — centered replica of a single received history card, mirroring the real
 * [TransferHistoryItem] for an incoming transfer (Info icon + title/subtitle + green "Received"
 * status). [modifier] lets the cursor-reveal animation probe the card center for the finger-tap.
 */
@Composable
internal fun TutorialReplicaReceivedCard(modifier: Modifier = Modifier) {
    // Width is supplied by [modifier] so the reveal can size the card to the real list card it overlays.
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.radiusLg))
            .background(MaterialTheme.colors.surface)
            .padding(Dimens.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(Dimens.iconLg),
        )
        Spacer(Modifier.width(Dimens.spaceLg))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Android Phone",
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onSurface,
            )
            Text(
                "2 files • 12.4 MB",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
        Text(
            "Received",
            style = MaterialTheme.typography.caption,
            fontWeight = FontWeight.Bold,
            color = GreenPrimary,
        )
    }
}

/**
 * Step 13 (after-tap) — full-area detail replica of the real transfer-detail overlay (fills the
 * dialog like the real view): header (From + Delete), summary caption, per-file rows each with a
 * full progress bar and the blue "Open" button, plus the bottom "Open Folder" action. Static replica
 * — no real file access. [openButtonModifier] lets the reveal probe the first "Open" button so it can
 * spotlight + tap it.
 */
@Composable
internal fun TutorialReplicaOpenFileDetail(openButtonModifier: Modifier = Modifier) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(Dimens.spaceXl),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "From : Android Phone",
                style = MaterialTheme.typography.subtitle1,
                color = MaterialTheme.colors.onBackground,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(Dimens.radiusLg))
                    .background(MaterialTheme.colors.error)
                    .padding(horizontal = Dimens.spaceLg),
                contentAlignment = Alignment.Center,
            ) {
                Text("Delete", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(Dimens.spaceMd))
        Text(
            "22 Jun 2026 14:30 • 2 files (12.4 MB)",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(Dimens.spaceLg))
        TutorialReplicaFileRow("holiday-photo.jpg", "100% (8.1 MB / 8.1 MB)", openButtonModifier = openButtonModifier)
        Spacer(Modifier.height(Dimens.spaceSm))
        TutorialReplicaFileRow("report.pdf", "100% (4.3 MB / 4.3 MB)")
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(Dimens.radiusMd))
                .background(Color(0xFF333333)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Open Folder", style = MaterialTheme.typography.button, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

/** One file row inside [TutorialReplicaOpenFileDetail]: icon + name + full progress bar + "Open". */
@Composable
private fun TutorialReplicaFileRow(
    fileName: String,
    progressLabel: String,
    openButtonModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(Dimens.iconLg),
        )
        Spacer(Modifier.width(Dimens.spaceLg))
        Column(modifier = Modifier.weight(1f)) {
            Text(fileName, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onBackground)
            Spacer(Modifier.height(Dimens.spaceXs))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(GreenPrimary),
            )
            Spacer(Modifier.height(Dimens.spaceXs))
            Text(progressLabel, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f))
        }
        Spacer(Modifier.width(Dimens.spaceLg))
        Box(
            modifier = openButtonModifier
                .height(36.dp)
                .clip(RoundedCornerShape(Dimens.radiusMd))
                .background(InfoColor)
                .padding(horizontal = Dimens.spaceLg),
            contentAlignment = Alignment.Center,
        ) {
            Text("Open", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

/** A simple black-on-white QR-style glyph (three finder squares + a data module) — a teaser, not real. */
@Composable
private fun FakeQrGlyph() {
    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        val finder = 64.dp
        val inset = 18.dp
        Box(Modifier.size(finder).align(Alignment.TopStart).offset(x = inset, y = inset).background(Color.Black))
        Box(Modifier.size(finder).align(Alignment.TopEnd).offset(x = -inset, y = inset).background(Color.Black))
        Box(Modifier.size(finder).align(Alignment.BottomStart).offset(x = inset, y = -inset).background(Color.Black))
        Box(Modifier.size(40.dp).align(Alignment.BottomEnd).offset(x = -36.dp, y = -36.dp).background(Color.Black))
    }
}
