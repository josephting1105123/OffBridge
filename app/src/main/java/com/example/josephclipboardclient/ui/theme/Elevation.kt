package com.EntropicSoftwareLab.ClipboardShare.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Elevation scale. In the glass language "elevation" is rendered as tint + hairline + a soft shadow,
 * not as a heavy Material-2 drop shadow. Read via [AppThemeTokens.elevation].
 */
@Immutable
data class Elevation(
    val level0: Dp = 0.dp,    // flush
    val level1: Dp = 1.dp,    // card
    val level2: Dp = 3.dp,    // bento tile / raised
    val level3: Dp = 8.dp,    // popover / menu
    val modal: Dp = 24.dp,    // sheet / dialog
)

val LocalElevation = staticCompositionLocalOf { Elevation() }
