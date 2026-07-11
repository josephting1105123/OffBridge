package com.EntropicSoftwareLab.ClipboardShare.ui.modal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity

/**
 * Backdrop capture for native-feel modal scrims: dim + blur the page behind a modal the way a
 * `dialog::backdrop { background: rgb(0 0 0 / .45); backdrop-filter: blur(6px) }` does on the web.
 *
 * A screen tags its content with [backdropSource]; a modal layer above it draws a frosted copy of
 * that content via [drawFrostedBackdrop] (holes punched with BlendMode.Clear stay sharp because the
 * frost is drawn inside the same offscreen-composited pass as the dim). Blur is gated by
 * `AppThemeTokens.glass.enableBlur` (RenderEffect needs API 31+); below that the scrim falls back
 * to the stronger flat dim so contrast is preserved.
 *
 * The overlay is NOT re-drawn when the captured content changes underneath it (no invalidation
 * bridge between layers) — acceptable because the page under a modal is static while the modal is
 * up, and entrance animations force redraws during the transition window.
 */

/** Scrim alpha over a frosted (blurred) backdrop — reads lighter because the blur separates layers. */
const val BACKDROP_DIM_FROSTED = 0.45f

/** Scrim alpha when blur is unavailable — the dim alone has to carry the layer separation. */
const val BACKDROP_DIM_FLAT = 0.6f

/** Frost strength; mirrors the 6px web backdrop-filter reference. */
val BackdropBlurRadius = 6.dp

/** Shared handle between a screen's [backdropSource] content and the modal scrims drawn above it. */
@Stable
class BackdropState {
    internal var layer: GraphicsLayer? by mutableStateOf(null)
}

@Composable
fun rememberBackdropState(): BackdropState = remember { BackdropState() }

/**
 * Records this node's content into [state] while drawing it normally. Apply to the screen's content
 * container — never to a container that also holds the modal overlay itself (the overlay would
 * record its own frosted copy recursively).
 */
@Composable
fun Modifier.backdropSource(state: BackdropState): Modifier {
    val layer = rememberGraphicsLayer()
    SideEffect { state.layer = layer }
    return drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}

/**
 * Remembers the intermediate layer a modal uses to re-draw the captured content blurred. Returns
 * null when blur is off (API < 31 or reduced-quality mode) so callers fall back to dim-only.
 */
@Composable
fun rememberFrostLayer(enableBlur: Boolean): GraphicsLayer? {
    if (!enableBlur) return null
    val frost = rememberGraphicsLayer()
    val radiusPx = with(LocalDensity.current) { BackdropBlurRadius.toPx() }
    SideEffect { frost.renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp) }
    return frost
}

/**
 * Draws the frosted page copy + dim scrim. Call as the FIRST draw ops of a modal's scrim pass;
 * if the pass uses `CompositingStrategy.Offscreen`, a later BlendMode.Clear hole punches through
 * both frost and dim, leaving the spotlighted content sharp.
 */
fun DrawScope.drawFrostedBackdrop(state: BackdropState?, frost: GraphicsLayer?) {
    val src = state?.layer
    val frosted = frost != null && src != null
    if (frosted) {
        frost!!.record { drawLayer(src!!) }
        drawLayer(frost)
    }
    drawRect(Color.Black.copy(alpha = if (frosted) BACKDROP_DIM_FROSTED else BACKDROP_DIM_FLAT))
}
