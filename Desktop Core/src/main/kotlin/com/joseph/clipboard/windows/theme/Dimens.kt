package com.joseph.clipboard.windows.theme

import androidx.compose.ui.unit.dp

/**
 * Design tokens for the desktop app: an 8dp spacing rhythm, a consistent corner-radius
 * and elevation scale, and standard control sizes. Use these instead of ad-hoc dp values
 * so spacing, rounding and depth stay consistent across every screen (Android parity lives
 * in res/values/dimens.xml).
 */
object Dimens {
    // Spacing — 4 / 8dp rhythm
    val spaceXxs = 2.dp
    val spaceXs = 4.dp
    val spaceSm = 8.dp
    val spaceMd = 12.dp
    val spaceLg = 16.dp
    val spaceXl = 24.dp
    val spaceXxl = 32.dp
    val spaceHuge = 48.dp

    // Corner radii
    val radiusXs = 6.dp
    val radiusSm = 8.dp
    val radiusMd = 12.dp
    val radiusLg = 16.dp
    val radiusXl = 20.dp
    val radiusPill = 50.dp

    // Elevation scale (cards → modals)
    val elevCard = 1.dp
    val elevRaised = 3.dp
    val elevOverlay = 8.dp
    val elevModal = 24.dp

    // Control / icon sizes
    val touchTarget = 48.dp
    val buttonHeight = 48.dp
    val buttonHeightLg = 56.dp   // primary CTA baseline (Android parity)
    val iconSm = 18.dp
    val iconMd = 24.dp
    val iconLg = 32.dp
    val headerIcon = 36.dp       // screen header icons (ic_hdr_*)

    // Glass recipe
    val hairlineWidth = 1.dp     // 1dp glass border thickness
}
