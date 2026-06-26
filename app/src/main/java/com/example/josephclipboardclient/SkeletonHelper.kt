package com.EntropicSoftwareLab.ClipboardShare

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.facebook.shimmer.ShimmerFrameLayout

/**
 * Attaches/detaches shimmer skeleton placeholders while real content loads.
 * Skeletons are kept visible for at least [MIN_SHOWN_MS] to avoid a
 * flash-of-skeleton on fast loads.
 */
object SkeletonHelper {

    private const val MIN_SHOWN_MS = 250L
    private val attachTimes = mutableMapOf<Int, Long>()

    /**
     * Inflates [count] copies of [layoutId] into a shimmer wrapper inside
     * [container], makes the container visible and starts the shimmer.
     */
    fun show(container: ViewGroup, layoutId: Int, count: Int, horizontal: Boolean) {
        container.removeAllViews()
        val shimmer = ShimmerFrameLayout(container.context)
        shimmer.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val row = LinearLayout(container.context).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val inflater = LayoutInflater.from(container.context)
        repeat(count) { inflater.inflate(layoutId, row, true) }
        shimmer.addView(row)
        shimmer.startShimmer()
        container.addView(shimmer)
        container.visibility = View.VISIBLE
        attachTimes[container.id] = SystemClock.elapsedRealtime()
    }

    /**
     * Stops and removes the skeleton in [container], deferring removal so the
     * skeleton was visible for at least [MIN_SHOWN_MS].
     */
    fun hide(container: ViewGroup) {
        val attachedAt = attachTimes.remove(container.id)
        val elapsed = if (attachedAt != null) SystemClock.elapsedRealtime() - attachedAt else MIN_SHOWN_MS
        val remaining = (MIN_SHOWN_MS - elapsed).coerceAtLeast(0L)
        val dismiss = Runnable {
            for (i in 0 until container.childCount) {
                (container.getChildAt(i) as? ShimmerFrameLayout)?.stopShimmer()
            }
            container.removeAllViews()
            container.visibility = View.GONE
        }
        if (remaining == 0L) dismiss.run() else container.postDelayed(dismiss, remaining)
    }
}
