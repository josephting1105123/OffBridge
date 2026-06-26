package com.EntropicSoftwareLab.ClipboardShare

import android.app.Activity
import android.widget.TextView

/** Binds a 6-digit verification PIN into the segmented boxes of [R.layout.view_pin_code]. */
object PinBox {

    private val DIGIT_IDS = intArrayOf(
        R.id.pin_digit_0, R.id.pin_digit_1, R.id.pin_digit_2,
        R.id.pin_digit_3, R.id.pin_digit_4, R.id.pin_digit_5
    )

    /** Fills the boxes with [pin]'s digits (one per box); extra boxes are left blank. */
    fun bind(activity: Activity, pin: String) {
        DIGIT_IDS.forEachIndexed { i, id ->
            activity.findViewById<TextView>(id)?.text = pin.getOrNull(i)?.toString() ?: ""
        }
    }
}
