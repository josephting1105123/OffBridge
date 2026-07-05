package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/** Helpful empty state: icon + message (+ optional pairing-style action) instead of a blank screen. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppThemeTokens.dimens.spaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppThemeTokens.colors.textSecondary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(AppThemeTokens.dimens.spaceMd))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AppThemeTokens.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(AppThemeTokens.dimens.spaceXs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = AppThemeTokens.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(AppThemeTokens.dimens.spaceLg))
            // Same transparent, hairline-outlined pill as the System Status Pair button,
            // 20% under the 56dp CTA baseline.
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.height(EMPTY_ACTION_HEIGHT),
                border = BorderStroke(AppThemeTokens.dimens.hairlineWidth, AppThemeTokens.colors.divider),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent),
            ) {
                Text(actionLabel, color = AppThemeTokens.colors.green, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private val EMPTY_ACTION_HEIGHT = 45.dp
