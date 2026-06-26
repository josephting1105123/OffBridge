package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/** Active transport rung (mirrors the off-grid transport ladder). */
enum class TransportTier(val label: String) {
    LAN("LAN"),
    WIFI_DIRECT("Wi-Fi Direct"),
    HOTSPOT("Hotspot"),
    QR_NFC("QR / NFC"),
    OFFLINE("Offline"),
}

/**
 * Glanceable connection pill: peer name + active transport tier, with a lock when encrypted.
 * This is what lets the "System status" tab be demoted from top-level nav (see plan Phase 5).
 */
@Composable
fun ConnectionStatusChip(
    deviceName: String?,
    tier: TransportTier,
    encrypted: Boolean,
    modifier: Modifier = Modifier,
) {
    val connected = deviceName != null && tier != TransportTier.OFFLINE
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (connected) AppThemeTokens.colors.accentMuted else AppThemeTokens.colors.card,
        border = BorderStroke(AppThemeTokens.dimens.hairlineWidth, AppThemeTokens.colors.hairline),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = AppThemeTokens.dimens.spaceMd,
                vertical = AppThemeTokens.dimens.spaceSm,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (connected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                contentDescription = null,
                tint = if (connected) AppThemeTokens.colors.success else AppThemeTokens.colors.textSecondary,
                modifier = Modifier.size(AppThemeTokens.dimens.iconSm),
            )
            Spacer(Modifier.width(AppThemeTokens.dimens.spaceXs))
            Text(
                text = if (connected) "$deviceName · ${tier.label}" else "Not connected",
                style = MaterialTheme.typography.labelLarge,
                color = AppThemeTokens.colors.textPrimary,
            )
            if (connected && encrypted) {
                Spacer(Modifier.width(AppThemeTokens.dimens.spaceXs))
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Encrypted",
                    tint = AppThemeTokens.colors.success,
                    modifier = Modifier.size(AppThemeTokens.dimens.iconSm),
                )
            }
        }
    }
}

enum class TransferState { SENDING, RECEIVING, COMPLETED, FAILED, CANCELLED }

/** Semantic transfer-state chip — icon + colour + label (never colour alone). */
@Composable
fun TransferStatusChip(state: TransferState, modifier: Modifier = Modifier) {
    val icon: ImageVector
    val tint: androidx.compose.ui.graphics.Color
    val label: String
    when (state) {
        TransferState.SENDING -> { icon = Icons.Filled.Wifi; tint = AppThemeTokens.colors.info; label = "Sending" }
        TransferState.RECEIVING -> { icon = Icons.Filled.Wifi; tint = AppThemeTokens.colors.info; label = "Receiving" }
        TransferState.COMPLETED -> { icon = Icons.Filled.CheckCircle; tint = AppThemeTokens.colors.success; label = "Done" }
        TransferState.FAILED -> { icon = Icons.Filled.Error; tint = AppThemeTokens.colors.error; label = "Failed" }
        TransferState.CANCELLED -> { icon = Icons.Filled.Cancel; tint = AppThemeTokens.colors.textSecondary; label = "Cancelled" }
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(AppThemeTokens.dimens.iconSm))
        Spacer(Modifier.width(AppThemeTokens.dimens.spaceXs))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}
