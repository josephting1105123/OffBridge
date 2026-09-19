package com.joseph.clipboard.windows.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Icons drawn in-repo rather than taken from the Material set, for marks that have to be identical on
 * both clients. Material's own icons differ between the Android and Compose Desktop artifacts, so
 * anything that must match pixel-for-pixel across platforms belongs here.
 */

/**
 * QR mark: four rounded scan brackets around three finder squares and a small data cluster.
 *
 * Geometry is the same 24x24 viewport as Android's `ic_qr_frame.xml`, coordinate for coordinate — the
 * two are meant to be one mark, so any edit here needs the same edit there. Replaces
 * `Icons.Filled.QrCode2`, whose dense module grid read as a grey smudge at [Dimens.iconSm] and looked
 * nothing like the Android tile beside it in screenshots.
 *
 * The fill is a placeholder: every call site renders it through `Icon(..., tint = ...)`, which
 * overrides it.
 */
val QrFrameIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "QrFrame",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            // Four corner brackets. The quad is the rounded outer corner; the inner corner stays square.
            moveTo(2f, 9f); lineTo(2f, 4f); quadTo(2f, 2f, 4f, 2f)
            lineTo(9f, 2f); lineTo(9f, 4f); lineTo(4f, 4f); lineTo(4f, 9f); close()

            moveTo(22f, 9f); lineTo(22f, 4f); quadTo(22f, 2f, 20f, 2f)
            lineTo(15f, 2f); lineTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f); close()

            moveTo(2f, 15f); lineTo(2f, 20f); quadTo(2f, 22f, 4f, 22f)
            lineTo(9f, 22f); lineTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f); close()

            moveTo(22f, 15f); lineTo(22f, 20f); quadTo(22f, 22f, 20f, 22f)
            lineTo(15f, 22f); lineTo(15f, 20f); lineTo(20f, 20f); lineTo(20f, 15f); close()

            // Three finder squares. Each is an outer ring with an inner square that EvenOdd carves out.
            moveTo(6f, 6f); horizontalLineToRelative(5f); verticalLineToRelative(5f)
            horizontalLineToRelative(-5f); close()
            moveTo(7.5f, 7.5f); horizontalLineToRelative(2f); verticalLineToRelative(2f)
            horizontalLineToRelative(-2f); close()

            moveTo(13f, 6f); horizontalLineToRelative(5f); verticalLineToRelative(5f)
            horizontalLineToRelative(-5f); close()
            moveTo(14.5f, 7.5f); horizontalLineToRelative(2f); verticalLineToRelative(2f)
            horizontalLineToRelative(-2f); close()

            moveTo(6f, 13f); horizontalLineToRelative(5f); verticalLineToRelative(5f)
            horizontalLineToRelative(-5f); close()
            moveTo(7.5f, 14.5f); horizontalLineToRelative(2f); verticalLineToRelative(2f)
            horizontalLineToRelative(-2f); close()

            // Data cluster in the fourth corner. Kept clear of one another: shapes that meet even at a
            // single corner merge into one blob once the icon is rasterised at 24dp.
            moveTo(13f, 16f); horizontalLineToRelative(2f); verticalLineToRelative(2f)
            horizontalLineToRelative(-2f); close()
            moveTo(15f, 13f); horizontalLineToRelative(2f); verticalLineToRelative(2f)
            horizontalLineToRelative(-2f); close()
            moveTo(17f, 13f); horizontalLineToRelative(2f); verticalLineToRelative(5f)
            horizontalLineToRelative(-2f); close()
        }
    }.build()
}
