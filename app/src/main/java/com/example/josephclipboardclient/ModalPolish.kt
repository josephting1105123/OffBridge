package com.EntropicSoftwareLab.ClipboardShare

import android.app.Dialog
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Window-level "native modal" polish for dialogs and sheets that live in their own window
 * (AlertDialog, BottomSheetDialogFragment, Compose AlertDialog): blur what's behind the window and
 * soften the dim to 45% on Android 12+ (cross-window blur permitting), so the page visibly recedes
 * while the dialog comes forward. In-layout overlays get the equivalent treatment from
 * ui/modal/Backdrop.kt instead.
 */

/** Blur-behind radius in dp; mirrors the 6px web backdrop-filter reference and Backdrop.kt. */
private const val BLUR_BEHIND_DP = 6f

/** Scrim strength over a blurred page — matches BACKDROP_DIM_FROSTED in Backdrop.kt. */
private const val DIM_WITH_BLUR = 0.45f

/**
 * Dims + blurs the page behind this window. On API < 31, or when the system has cross-window blur
 * disabled (battery saver, developer setting), the theme's stronger default dim is kept so the
 * modal layer separation never disappears.
 */
fun Window.applyModalBackdrop() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    if (!windowManager.isCrossWindowBlurEnabled) return
    addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    attributes = attributes.apply {
        blurBehindRadius = (BLUR_BEHIND_DP * context.resources.displayMetrics.density).toInt()
        dimAmount = DIM_WITH_BLUR
    }
}

/**
 * Full centered-dialog polish: blurred/dimmed backdrop + the scale 0.95 -> 1 + fade entrance.
 * Call after create() and before show(). Bottom sheets should call [applyModalBackdrop] directly
 * instead — their native slide-up entrance is the correct sheet vocabulary.
 */
fun Dialog.applyModalPolish() {
    window?.let { w ->
        w.applyModalBackdrop()
        w.setWindowAnimations(R.style.ModalWindowAnimation)
    }
}

/**
 * Compose-dialog variant: drop this inside any AlertDialog/Dialog slot (title, text, content) —
 * it reaches the dialog's own window through [DialogWindowProvider] and applies [applyModalPolish].
 */
@Composable
fun PolishDialogWindow() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.applyModalBackdrop()
        window.setWindowAnimations(R.style.ModalWindowAnimation)
    }
}
