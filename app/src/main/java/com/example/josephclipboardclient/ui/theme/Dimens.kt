package com.EntropicSoftwareLab.ClipboardShare.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing / radii / control sizes — the Compose mirror of res/values/dimens.xml.
 * Compose has no built-in spacing tokens, so they live here and are read via [AppThemeTokens.dimens].
 */
@Immutable
data class Dimens(
    val spaceXxs: Dp = 2.dp,
    val spaceXs: Dp = 4.dp,
    val spaceSm: Dp = 8.dp,
    val spaceMd: Dp = 12.dp,
    val spaceLg: Dp = 16.dp,
    val spaceXl: Dp = 24.dp,
    val spaceXxl: Dp = 32.dp,
    val spaceHuge: Dp = 48.dp,
    val radiusXs: Dp = 6.dp,
    val radiusSm: Dp = 8.dp,
    val radiusMd: Dp = 12.dp,
    val radiusLg: Dp = 16.dp,
    val radiusXl: Dp = 20.dp,
    val radiusPill: Dp = 999.dp,
    val touchTarget: Dp = 48.dp,
    val buttonHeight: Dp = 56.dp,
    val iconSm: Dp = 18.dp,
    val iconMd: Dp = 24.dp,
    val iconLg: Dp = 32.dp,
    val headerIcon: Dp = 36.dp,
    val hairlineWidth: Dp = 1.dp,
    val blurRadius: Dp = 24.dp,
)

val LocalDimens = staticCompositionLocalOf { Dimens() }
