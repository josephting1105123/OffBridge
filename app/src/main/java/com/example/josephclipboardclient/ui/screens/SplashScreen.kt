package com.EntropicSoftwareLab.ClipboardShare.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens
import kotlinx.coroutines.delay

/**
 * Splash visual: centered logo + name, with the pulsing "loading dots" near the bottom.
 * Navigation (the 2s delay → Main/Onboarding) stays in SplashActivity — this is presentation only.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppThemeTokens.colors.background),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_clipboard_dark),
                contentDescription = null,
                tint = AppThemeTokens.colors.textPrimary,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                color = AppThemeTokens.colors.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // Pulsing alpha + cycling 0..3 dots (mirrors the old ObjectAnimator + dot-count loop).
        val pulse = rememberInfiniteTransition(label = "splashPulse")
        val dotsAlpha by pulse.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "dotsAlpha",
        )
        var dotCount by remember { mutableIntStateOf(3) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(800)
                dotCount = (dotCount + 1) % 4
            }
        }
        Text(
            text = ".".repeat(dotCount),
            color = AppThemeTokens.colors.textPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(dotsAlpha),
        )

        // Pre-warm the DesignModeToggle composable and its animations so that the first time 
        // it appears in the MenuBottomSheet it doesn't cause a layout stutter.
        Box(modifier = Modifier.alpha(0f).size(1.dp)) {
            com.EntropicSoftwareLab.ClipboardShare.ui.components.DesignModeToggle(
                options = listOf("off", "on"),
                selectedIndex = 0,
                onSelect = {},
                selectedColors = listOf(androidx.compose.ui.graphics.Color.Red, androidx.compose.ui.graphics.Color.Green)
            )
        }
    }
}
