package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface

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

/** Puts [text] on the system clipboard under [label] and confirms with a [toast] string. */
fun Context.copyPlainText(label: String, text: CharSequence, @StringRes toast: Int) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
}

/**
 * Shared scrollable, selectable-text modal with an OK button and a neutral Copy action — the common
 * scaffold behind the history text preview and the transport-diagnostics dialog. [monospace] renders
 * the small fixed-width diag styling; [clipLabel] defaults to the title. Applies [applyModalPolish].
 */
fun showSelectableTextDialog(
    context: Context,
    title: CharSequence,
    text: CharSequence,
    @StringRes copyButtonLabel: Int,
    @StringRes copiedToast: Int,
    monospace: Boolean = false,
    clipLabel: CharSequence = title,
) {
    val pad = (16 * context.resources.displayMetrics.density).toInt()
    val tv = TextView(context).apply {
        setText(text)
        if (monospace) {
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        setTextIsSelectable(true)
        setPadding(pad, pad, pad, pad)
    }
    val scroll = ScrollView(context).apply { addView(tv) }
    AlertDialog.Builder(context)
        .setTitle(title)
        .setView(scroll)
        .setPositiveButton(android.R.string.ok, null)
        .setNeutralButton(copyButtonLabel) { _, _ -> context.copyPlainText(clipLabel.toString(), text, copiedToast) }
        .create()
        .also { it.applyModalPolish() }
        .show()
}
