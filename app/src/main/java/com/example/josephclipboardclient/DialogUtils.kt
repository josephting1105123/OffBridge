package com.EntropicSoftwareLab.ClipboardShare

import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog

/**
 * Adds horizontal spacing between an AlertDialog's action buttons. Some dialog themes render the
 * buttons flush against each other with no gap; call this from the dialog's OnShowListener (the
 * buttons don't exist until the dialog is shown). Casts to the generic [ViewGroup.MarginLayoutParams]
 * (the button bar's params aren't always LinearLayout.LayoutParams) and sets both start + left margin.
 */
fun AlertDialog.spaceButtons(gapDp: Int = 12) {
    val gap = (gapDp * context.resources.displayMetrics.density).toInt()
    intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
        val button = getButton(which) ?: return@forEach
        (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.marginStart = gap
            lp.leftMargin = gap
            button.layoutParams = lp
        }
    }
}
