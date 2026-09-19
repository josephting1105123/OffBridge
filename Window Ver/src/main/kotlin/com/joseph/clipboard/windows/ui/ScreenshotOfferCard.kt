package com.joseph.clipboard.windows.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.joseph.clipboard.windows.ClipboardAppController
import java.util.Base64
import javax.imageio.ImageIO

/**
 * "Your phone took a screenshot" — shown in-app while the window is on screen.
 *
 * When the window is hidden this never appears; the announcement arrives as a tray balloon instead
 * (see ClipboardAppController.onScreenshotAnnounced). The split exists because a Windows tray
 * balloon is text-only: the preview can only be shown somewhere the app is already drawing.
 *
 * Nothing has been transferred at this point. Open is what asks the phone to send the image.
 */
@Composable
fun ScreenshotOfferCard(
    announced: ClipboardAppController.AnnouncedScreenshot,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = LocalExtraColors.current
    // Keyed on the session so a second announcement decodes rather than reusing the first preview.
    val preview = remember(announced.sessionId) { decodeThumbnail(announced.thumbnailB64) }

    GlassCard(modifier = modifier.width(320.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                TranslationManager.getString("screenshot_offer_title")
                    .takeIf { it != "screenshot_offer_title" } ?: "Screenshot on ${announced.senderName}",
                style = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onSurface,
            )
            Text(
                announced.name,
                style = MaterialTheme.typography.caption,
                color = extra.textSecondary,
            )
            if (preview != null) {
                Image(
                    painter = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(extra.surfaceElevated),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        TranslationManager.getString("screenshot_offer_dismiss")
                            .takeIf { it != "screenshot_offer_dismiss" } ?: "Not now",
                        color = extra.textSecondary,
                    )
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onOpen) {
                    Text(
                        TranslationManager.getString("screenshot_offer_open")
                            .takeIf { it != "screenshot_offer_open" } ?: "Open here",
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(0.dp))
        }
    }
}

/**
 * Decodes the announcement's base64 JPEG, or null.
 *
 * The bytes come off the network from a paired-but-not-trusted peer, so every failure path — bad
 * base64, a truncated JPEG, an image ImageIO refuses — returns null and the card simply renders
 * without a preview. A decode failure must not take the window down.
 */
private fun decodeThumbnail(base64: String): BitmapPainter? {
    if (base64.isEmpty()) return null
    return try {
        val bytes = Base64.getDecoder().decode(base64)
        val image = ImageIO.read(bytes.inputStream()) ?: return null
        BitmapPainter(image.toComposeImageBitmap())
    } catch (t: Throwable) {
        null
    }
}
