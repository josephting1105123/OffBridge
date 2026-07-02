package com.joseph.clipboard.windows.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Native-feel modal backdrop for the desktop client: while any modal surface (in-window Dialog,
 * pairing/file-offer overlay, power menu) is up, the page content behind it blurs 6dp and the
 * modal's own scrim dims it 45% — the web `dialog::backdrop { blur(6px) + rgb(0 0 0 / .45) }`
 * recipe. Mirrors the Android ui/modal/Backdrop.kt vocabulary.
 *
 * Wiring: AppContent provides [LocalBackdrop] and applies [backdropBlur] to the screen content;
 * every modal calls [BackdropEffect] while visible. The spotlight TutorialOverlay does NOT use
 * this (it lives inside the blurred region and must keep its punched hole sharp), so it keeps its
 * own heavier dim instead.
 */

/** Scrim over a blurred page — the blur carries part of the layer separation. */
val BackdropScrim = Color.Black.copy(alpha = 0.45f)

/** Blur strength; mirrors the 6px web backdrop-filter reference. */
val BackdropBlurRadius = 6.dp

/** Counts visible modal surfaces; the page blurs while any are up. */
class BackdropController {
    var depth by mutableStateOf(0)
}

val LocalBackdrop = staticCompositionLocalOf<BackdropController?> { null }

/** Marks the calling modal as visible for its composition lifetime (no-op without a provider). */
@Composable
fun BackdropEffect() {
    val controller = LocalBackdrop.current ?: return
    DisposableEffect(controller) {
        controller.depth++
        onDispose { controller.depth-- }
    }
}

/** Apply to the page content that should recede behind modals; the radius animates in/out. */
@Composable
fun Modifier.backdropBlur(controller: BackdropController): Modifier {
    val radius by animateDpAsState(
        targetValue = if (controller.depth > 0) BackdropBlurRadius else 0.dp,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
    )
    return if (radius > 0.dp) this.blur(radius) else this
}

/**
 * Modal panel entrance: scale 0.95 -> 1 with a fade over 180ms, ease-out — the panel reads as
 * placed in front of the page rather than teleporting in at full size.
 */
@Composable
fun Modifier.modalPopIn(): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        val v = p.value
        alpha = v
        scaleX = 0.95f + 0.05f * v
        scaleY = 0.95f + 0.05f * v
    }
}
