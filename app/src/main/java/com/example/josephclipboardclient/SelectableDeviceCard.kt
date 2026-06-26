package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

// This class is extracted from the original FileTransferActivity.kt
// No changes to the logic are needed.

class SelectableDeviceCard(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private var _isSelected = false
    private val STATE_SELECTED = intArrayOf(R.attr.state_selected)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun setSelected(selected: Boolean) {
        if (_isSelected != selected) {
            _isSelected = selected
            refreshDrawableState()
        }
    }

    override fun isSelected(): Boolean {
        return _isSelected
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val drawableState = super.onCreateDrawableState(extraSpace + 1)
        if (isSelected) {
            mergeDrawableStates(drawableState, STATE_SELECTED)
        }
        return drawableState
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.refreshDrawableState()
        }
    }
}