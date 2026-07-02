package com.EntropicSoftwareLab.ClipboardShare.sendvia

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.ui.modal.BackdropState
import com.EntropicSoftwareLab.ClipboardShare.ui.modal.drawFrostedBackdrop
import com.EntropicSoftwareLab.ClipboardShare.ui.modal.rememberFrostLayer
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/**
 * In-screen "Send Via" overlay: a dim scrim over the send-files content plus the [SendViaSheet]
 * island floating just above the container's bottom edge (the sheet carries its own margins).
 * Because the fragment container ends at the bottom nav bar's top edge, the card slides up from
 * behind the nav bar and can never cover it — the nav bar stays usable while this is open.
 * Dismissed by back press or a tap on the scrim.
 */
@Composable
fun SendViaOverlay(
    visible: Boolean,
    nfcUnavailable: Boolean,
    totalSizeBytes: Long,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Screen content captured via Modifier.backdropSource — enables the frosted (blurred) scrim. */
    backdrop: BackdropState? = null,
) {
    val motion = AppThemeTokens.motion
    val frost = rememberFrostLayer(AppThemeTokens.glass.enableBlur && backdrop != null)
    val dismissLabel = stringResource(R.string.action_cancel)
    val sheetTitle = stringResource(R.string.send_via_title)

    BackHandler(enabled = visible) { onDismiss() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(motion.dur(100))),
        exit = fadeOut(tween(motion.dur(100))),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Frosted page copy + dim (falls back to the flat dim when blur is unavailable).
                    .drawBehind { drawFrostedBackdrop(backdrop, frost) }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = dismissLabel,
                    ) { onDismiss() },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Mirrors the window anims: fast_slide_up (150ms decelerate) / fast_slide_down
                    // (120ms accelerate).
                    .animateEnterExit(
                        enter = slideInVertically(tween(motion.dur(150), easing = LinearOutSlowInEasing)) { it },
                        exit = slideOutVertically(tween(motion.dur(120), easing = FastOutLinearInEasing)) { it },
                    )
                    // Consume taps so a tap on the card's padding/title doesn't fall through to the
                    // scrim behind it and dismiss.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { paneTitle = sheetTitle },
            ) {
                SendViaSheet(nfcUnavailable = nfcUnavailable, totalSizeBytes = totalSizeBytes, onChoose = onChoose)
            }
        }
    }
}
