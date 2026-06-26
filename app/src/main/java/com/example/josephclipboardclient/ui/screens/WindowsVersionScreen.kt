package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/**
 * Static "desktop app in development" info screen — the first XML-to-Compose conversion.
 * (Uses the brand ic_computer vector instead of the 💻 emoji, per the design rules.)
 */
@Composable
fun WindowsVersionScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppThemeTokens.colors.background)
            .padding(AppThemeTokens.dimens.spaceXxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_computer),
                contentDescription = null,
                tint = AppThemeTokens.colors.greenText,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(AppThemeTokens.dimens.spaceXl))
            Text(
                text = stringResource(R.string.clipboard_share_windows_title),
                color = AppThemeTokens.colors.textPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(AppThemeTokens.dimens.spaceLg))
            Text(
                text = stringResource(R.string.desktop_app_in_development_desc),
                color = AppThemeTokens.colors.textSecondary,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
