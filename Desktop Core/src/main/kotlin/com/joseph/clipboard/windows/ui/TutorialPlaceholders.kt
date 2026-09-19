package com.joseph.clipboard.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joseph.clipboard.windows.theme.GreenPrimary

/**
 * Fake device card shown inside the tutorial spotlight when the real device
 * list is empty. Ports Android's tutorial_placeholder_device.xml and
 * tutorial_placeholder_device_with_pair.xml.
 */
@Composable
fun TutorialPlaceholderDevice(showPairButton: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 12dp cancels the tutorial spotlight's 12dp pad so the card width matches a real device row.
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Smartphone,
            contentDescription = null,
            tint = GreenPrimary,
            modifier = Modifier.padding(end = 16.dp).size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Android Phone",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onSurface
            )
            Text(
                "Android",
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )
        }
        // Always reserve the Pair button's footprint so the step-3 card (no button)
        // and the step-4 card (button shown) render at an identical size.
        Box(modifier = Modifier.size(width = 110.dp, height = 40.dp)) {
            if (showPairButton) {
                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(backgroundColor = GreenPrimary),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Pair", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Fake "Send to Devices" interface for Send-Files tutorial step 6: the
 * selected-files row (as in step 2) above a paired-device card. Ports Android's
 * tutorial_placeholder_connected_devices.xml.
 */
@Composable
fun TutorialPlaceholderConnectedDevices() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            TutorialPlaceholderFiles(selected = false)
        }
        Text(
            "Select trusted recipients",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onBackground,
            modifier = Modifier.padding(start = 10.dp, top = 20.dp, bottom = 12.dp)
        )
        TutorialPlaceholderDevice()
    }
}

/**
 * Stylized fake QR graphic for the off-grid teaser step.
 * Ports Android's tutorial_placeholder_qr.xml.
 */
@Composable
fun TutorialPlaceholderQr() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(130.dp).background(Color.White)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.TopStart)
                    .padding(0.dp)
                    .offset(x = 14.dp, y = 14.dp)
                    .background(Color.Black)
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = (-14).dp, y = 14.dp)
                    .background(Color.Black)
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.BottomStart)
                    .offset(x = 14.dp, y = (-14).dp)
                    .background(Color.Black)
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-20).dp, y = (-20).dp)
                    .background(Color.Black)
            )
        }
        Text(
            "QR",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * Three fake file cards shown inside the tutorial spotlight on the Send Files
 * screen. Ports Android's tutorial_placeholder_files.xml and
 * tutorial_placeholder_files_selected.xml ([selected] adds the green overlay).
 *
 * [alignStart] left-aligns the cards (matching the real left-aligned Selected-Files row) instead of
 * centering them; the Send-Files steps use it so the demo cards sit where real files would appear.
 */
@Composable
fun TutorialPlaceholderFiles(selected: Boolean = false, alignStart: Boolean = false) {
    Row(
        // 12dp top cancels the spotlight's pad. alignStart hugs the left edge (real row position);
        // otherwise the 3 demo cards centre together in the fixed-width spotlight.
        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(
            12.dp,
            if (alignStart) Alignment.Start else Alignment.CenterHorizontally
        ),
        verticalAlignment = Alignment.Top
    ) {
        listOf("file 1.mp4", "file 2.pdf", "file 3.jpg").forEach { name ->
            Box(
                modifier = Modifier
                    .size(100.dp, 100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colors.surface)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("📄", fontSize = 32.sp, modifier = Modifier.padding(bottom = 8.dp))
                    Text(
                        name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x6634D399))
                    )
                }
            }
        }
    }
}
