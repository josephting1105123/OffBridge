package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/** Primary CTA — green fill (M3 colorScheme.primary), 56dp tall, press-scale. One per screen. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier
            .height(AppThemeTokens.dimens.buttonHeight)
            .pressScale(interaction),
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(AppThemeTokens.dimens.radiusMd),
    ) {
        Text(text)
    }
}

/** Tonal action — muted-green fill with green label. Subordinate to the primary CTA. */
@Composable
fun AppTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier
            .height(AppThemeTokens.dimens.buttonHeight)
            .pressScale(interaction),
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(AppThemeTokens.dimens.radiusMd),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppThemeTokens.colors.accentMuted,
            contentColor = AppThemeTokens.colors.greenText,
        ),
    ) {
        Text(text)
    }
}

/** Secondary action — hairline-outlined, neutral label. */
@Composable
fun AppSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(AppThemeTokens.dimens.buttonHeight)
            .pressScale(interaction),
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(AppThemeTokens.dimens.radiusMd),
        border = BorderStroke(AppThemeTokens.dimens.hairlineWidth, AppThemeTokens.colors.divider),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppThemeTokens.colors.textPrimary),
    ) {
        Text(text)
    }
}

/** Destructive action — semantic danger fill, visually separated from the primary CTA. */
@Composable
fun AppDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier
            .height(AppThemeTokens.dimens.buttonHeight)
            .pressScale(interaction),
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(AppThemeTokens.dimens.radiusMd),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppThemeTokens.colors.error,
            contentColor = Color.White,
        ),
    ) {
        Text(text)
    }
}

/** Text-only secondary action — WCAG-safe green label. */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text, color = AppThemeTokens.colors.greenText)
    }
}
