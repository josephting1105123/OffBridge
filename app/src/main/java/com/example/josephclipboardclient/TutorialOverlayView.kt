package com.EntropicSoftwareLab.ClipboardShare

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.view.doOnLayout
import kotlin.math.PI
import kotlin.math.sin

/**
 * A single tutorial step definition.
 */
data class TutorialStep(
    val targetView: View,
    val text: String,
    val placeholderText: String? = null,
    val forceTooltipAbove: Boolean = false,
    val minSpotlightHeightDp: Int? = null,
    val placeholderLayoutId: Int? = null,
    /** Fraction of spotlight height to shift vertically (+ve = down, -ve = up). */
    val spotlightYOffsetFraction: Float = 0f,
    /** Fraction of spotlight height to trim from the bottom edge. */
    val spotlightBottomTrimFraction: Float = 0f,
    /** Absolute vertical shift of the spotlight in dp. */
    val spotlightYOffsetDp: Int = 0,
    /** Absolute vertical shift of the placeholder content in dp. */
    val placeholderYOffsetDp: Int = 0,
    /** Override the Next/Done button label. Null = auto. */
    val buttonLabel: String? = null,
    /** Called when this step becomes active. */
    val onShow: (() -> Unit)? = null,
    /** Called when this step is leaving (next step shown or tutorial dismissed). */
    val onHide: (() -> Unit)? = null,
    /** Milliseconds to wait after onShow before resolving targetViewProvider and updating spotlight. */
    val delayMs: Long = 0L,
    /** If set, called after delayMs to obtain the actual view to spotlight (overrides targetView). */
    val targetViewProvider: (() -> View)? = null,
    /** Hide the Skip button for this step. */
    val hideSkipButton: Boolean = false,
    /** Dp trimmed from each horizontal side of the spotlight (total width shrinks by 2x this). */
    val spotlightHorizontalInsetDp: Int = 0,
    /** Dp trimmed from both the top and bottom edges of the spotlight. */
    val spotlightVerticalInsetDp: Int = 0,
    /** Dp trimmed from the bottom edge of the spotlight only. */
    val spotlightBottomTrimDp: Int = 0,
    /** Scale applied to the spotlight around its center (e.g. 0.95f shrinks it by 5%). */
    val spotlightScale: Float = 1f,
    /** If set, the placeholder content is left-aligned with this view instead of the spotlight edge. */
    val placeholderAlignStartViewProvider: (() -> View)? = null,
    /** If set, the tooltip's bottom edge is aligned with the bottom of this view. */
    val tooltipBottomAlignViewProvider: (() -> View)? = null,
    /** Keep the tooltip entirely above the spotlight, even if it must hug the top of the screen. */
    val tooltipAboveSpotlight: Boolean = false,
    /**
     * If set, the spotlight is anchored to the BOTTOM of the overlay: its bottom edge sits this many
     * dp above the overlay's bottom while its height is preserved from the target/minSpotlight
     * computation. Used to render a fake bottom sheet (e.g. the Send-Via chooser) at the real
     * on-screen position rather than as a popup in the middle.
     */
    val spotlightBottomAnchorMarginDp: Int? = null,
    /** If set, pins the tooltip's top this many dp below the overlay's top, overriding auto-placement. */
    val tooltipPinTopMarginDp: Int? = null,
    /** Force the tooltip BELOW the spotlight (clamped to the screen), overriding the auto fallback. */
    val forceTooltipBelow: Boolean = false,
    /** If set, the tooltip's TOP is placed just below this view's bottom, centred on screen. */
    val tooltipBelowViewProvider: (() -> View)? = null,
    /**
     * If set, a finger-tap pointer is animated over the view with this id INSIDE the placeholder,
     * hinting which control the next step opens (e.g. the Send-Via "Connected devices" row).
     */
    val pointerTapTargetId: Int? = null,
    /** If set, a finger-tap pointer is animated over the spotlight's centre (a real, non-placeholder card). */
    val pointerTapSpotlightCenter: Boolean = false,
    /**
     * If set, the placeholder first shows [placeholderLayoutId] (with the optional pointer tap),
     * then after [secondaryPlaceholderDelayMs] crossfades to this layout. Used by Send step 7 to
     * show the Send-Via sheet, tap NFC/QR, then reveal the real QR screen.
     */
    val secondaryPlaceholderLayoutId: Int? = null,
    /** Delay before crossfading to [secondaryPlaceholderLayoutId]. */
    val secondaryPlaceholderDelayMs: Long = 1600L,
    /** Invoked with the inflated secondary placeholder root after the crossfade (e.g. to inject a real QR). */
    val onPlaceholderSwapped: ((View) -> Unit)? = null,
    /**
     * If set, after the crossfade to [secondaryPlaceholderLayoutId] the spotlight expands to this
     * height. Lets a step keep a tight, pointer-focused spotlight on the tapped control first
     * (so the finger-tap reads clearly), then grow to fit the destination the crossfade reveals.
     */
    val secondaryMinSpotlightHeightDp: Int? = null,
)

/**
 * Full-screen overlay that dims the background 60% and punches a spotlight hole
 * over the target view, then shows a tooltip bubble beside it.
 */
class TutorialOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val dimPaint = Paint().apply {
        color = 0x99000000.toInt() // 60% black
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }

    private val placeholderPaint = Paint().apply {
        color = 0x99FFFFFF.toInt()
        textSize = 14f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val spotlightRect = RectF()        // the freshly computed target
    private val drawnRect = RectF()            // the animated copy actually rendered
    private val animFrom = RectF()
    private var spotlightAnimator: ValueAnimator? = null
    private val cornerRadius = 16f * resources.displayMetrics.density
    private val PADDING = (12 * resources.displayMetrics.density).toInt()

    private var steps: List<TutorialStep> = emptyList()
    private var currentIndex = 0
    private var onComplete: (() -> Unit)? = null
    /** Invoked whenever the overlay is removed (skipped or completed). */
    var onDismissed: (() -> Unit)? = null
    private var currentStep: TutorialStep? = null
    /** The view the current step is spotlighting — kept so a placeholder swap can re-spotlight it. */
    private var currentTargetView: View? = null
    private var stepIndexOffset = 0
    private var totalStepsOverride: Int? = null

    private val tooltipView: View
    private val tvText: TextView
    private val tvCounter: TextView
    private val tvNextLabel: TextView
    private val btnNext: CardView
    private val btnSkip: TextView

    private val placeholderContainer: FrameLayout

    /** A finger pointer that animates a click (with ripple waves) on the control the next step opens. */
    private val pointerView: ImageView
    private val pointerSizePx = (60 * resources.displayMetrics.density).toInt()
    // Touch point of ic_tutorial_pointer within the drawable (halo centre ≈ (11.5, 7) of a 24 viewport).
    private val TIP_FRACTION_X = 0.48f
    private val TIP_FRACTION_Y = 0.29f
    private var pointerAnimator: ValueAnimator? = null
    private var pointerTravelAnimator: ValueAnimator? = null
    private var secondaryRunnable: Runnable? = null

    // Click "waves" drawn under the pointer tip while it taps.
    private var pointerRippleActive = false
    private var pointerRippleCx = 0f
    private var pointerRippleCy = 0f
    private var pointerRipplePhase = 0f
    private val pointerRipplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // Neutral "click" waves — grey on light surfaces, near-white on dark — the familiar
        // tap-feedback look. (Brand green here read as a vague "green pulse" on the button.)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        color = if (night) 0xFFE0E0E0.toInt() else 0xFF757575.toInt()
    }

    /** When false, tapping the dim background does NOT advance — used by the forced tour. */
    var advanceOnOutsideTap: Boolean = true

    init {
        // Hardware layer required for PorterDuff CLEAR to work correctly
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setWillNotDraw(false)
        // Stay above elevated siblings (e.g. a bottom sheet) so taps hit the overlay,
        // not the UI underneath — otherwise an outside tap can dismiss the host dialog
        elevation = 32f * resources.displayMetrics.density

        placeholderContainer = FrameLayout(context)
        addView(placeholderContainer)

        tooltipView = android.view.LayoutInflater.from(context)
            .inflate(R.layout.layout_tutorial_tooltip, this, false)
        tvText    = tooltipView.findViewById(R.id.tvTutorialText)
        tvCounter = tooltipView.findViewById(R.id.tvTutorialStepCounter)
        tvNextLabel = tooltipView.findViewById(R.id.tvTutorialNextLabel)
        btnNext   = tooltipView.findViewById(R.id.btnTutorialNext)
        btnSkip   = tooltipView.findViewById(R.id.btnTutorialSkip)

        addView(tooltipView)

        pointerView = ImageView(context).apply {
            // Finger-tap pointer (neutral halo + white glyph) reads far more clearly than a thin arrow cursor.
            setImageResource(R.drawable.ic_tutorial_pointer)
            visibility = View.GONE
        }
        addView(pointerView, LayoutParams(pointerSizePx, pointerSizePx))

        btnNext.setOnClickListener  { showNextStep() }
        btnSkip.setOnClickListener  { dismiss() }
        // Tapping the dim background (outside tooltip) also advances the tutorial
        setOnClickListener { if (advanceOnOutsideTap) showNextStep() }
    }

    fun start(
        steps: List<TutorialStep>,
        stepIndexOffset: Int = 0,
        totalStepsOverride: Int? = null,
        onComplete: (() -> Unit)? = null
    ) {
        this.steps = steps
        this.onComplete = onComplete
        this.stepIndexOffset = stepIndexOffset
        this.totalStepsOverride = totalStepsOverride
        currentIndex = 0
        
        if (isLaidOut && width > 0 && height > 0) {
            showStep(currentIndex)
        } else {
            doOnLayout { showStep(currentIndex) }
        }
    }

    fun getPlaceholderRootView(): View? =
        if (placeholderContainer.childCount > 0) placeholderContainer.getChildAt(0) else null

    private fun showStep(index: Int) {
        if (index > 0) steps[index - 1].onHide?.invoke()
        clearPointerAndSecondary()

        val step = steps[index]
        currentStep = step

        val resolveAndShow = Runnable {
            val total = totalStepsOverride ?: steps.size
            tvText.text      = step.text
            tvCounter.text   = "${index + 1 + stepIndexOffset} / $total"
            tvNextLabel.text = step.buttonLabel ?: if (index == steps.size - 1) "Done" else "Next"
            btnSkip.visibility = if (step.hideSkipButton) View.GONE else View.VISIBLE
            tooltipView.visibility = View.VISIBLE

            val tv = step.targetViewProvider?.invoke() ?: step.targetView
            currentTargetView = tv
            tv.doOnLayout { updateSpotlightForView(tv, step) }
            updateSpotlightForView(tv, step)
            enterContent()
            startPointerAndSecondary(step)
        }

        step.onShow?.invoke()

        if (step.delayMs > 0L) {
            postDelayed(resolveAndShow, step.delayMs)
        } else {
            resolveAndShow.run()
        }
    }

    private fun updateSpotlightForView(
        targetView: View,
        step: TutorialStep,
        inflatePlaceholder: Boolean = true,
        minHeightOverrideDp: Int? = null
    ) {
        val loc = IntArray(2)
        targetView.getLocationInWindow(loc)

        val overlayLoc = IntArray(2)
        this.getLocationInWindow(overlayLoc)

        var rawLeft   = (loc[0] - overlayLoc[0] - PADDING).toFloat()
        var rawTop    = (loc[1] - overlayLoc[1] - PADDING).toFloat()
        var rawRight  = (loc[0] - overlayLoc[0] + targetView.width  + PADDING).toFloat()
        var rawBottom = (loc[1] - overlayLoc[1] + targetView.height + PADDING).toFloat()

        val minHeightDp = minHeightOverrideDp ?: step.minSpotlightHeightDp
        if (minHeightDp != null) {
            val minHeightPx = minHeightDp * resources.displayMetrics.density
            if ((rawBottom - rawTop) < minHeightPx) {
                rawBottom = rawTop + minHeightPx
            }
        }

        val spotlightH = rawBottom - rawTop
        if (step.spotlightYOffsetFraction != 0f) {
            val offset = spotlightH * step.spotlightYOffsetFraction
            rawTop += offset
            rawBottom += offset
        }
        if (step.spotlightYOffsetDp != 0) {
            val offsetPx = step.spotlightYOffsetDp * resources.displayMetrics.density
            rawTop += offsetPx
            rawBottom += offsetPx
        }
        if (step.spotlightBottomTrimFraction != 0f) {
            rawBottom -= spotlightH * step.spotlightBottomTrimFraction
        }

        // Clamp to overlay bounds
        var clampedRight  = rawRight.coerceAtMost(width.toFloat())
        var clampedBottom = rawBottom.coerceAtMost(height.toFloat())

        if (step.spotlightHorizontalInsetDp != 0) {
            val insetPx = step.spotlightHorizontalInsetDp * resources.displayMetrics.density
            rawLeft += insetPx
            clampedRight -= insetPx
        }
        if (step.spotlightVerticalInsetDp != 0) {
            val insetPx = step.spotlightVerticalInsetDp * resources.displayMetrics.density
            rawTop += insetPx
            clampedBottom -= insetPx
        }
        if (step.spotlightBottomTrimDp != 0) {
            clampedBottom -= step.spotlightBottomTrimDp * resources.displayMetrics.density
        }
        if (step.spotlightScale != 1f) {
            val cx = (rawLeft + clampedRight) / 2f
            val cy = (rawTop + clampedBottom) / 2f
            val halfW = (clampedRight - rawLeft) / 2f * step.spotlightScale
            val halfH = (clampedBottom - rawTop) / 2f * step.spotlightScale
            rawLeft = cx - halfW
            clampedRight = cx + halfW
            rawTop = cy - halfH
            clampedBottom = cy + halfH
        }

        // Bottom-anchor: keep the spotlight height but slide it down so its bottom edge sits a fixed
        // margin above the overlay bottom — places a fake bottom sheet where the real one appears.
        if (step.spotlightBottomAnchorMarginDp != null) {
            val h = clampedBottom - rawTop
            val marginPx = step.spotlightBottomAnchorMarginDp * resources.displayMetrics.density
            clampedBottom = height - marginPx
            rawTop = clampedBottom - h
        }

        spotlightRect.set(rawLeft, rawTop, clampedRight, clampedBottom)

        // Update placeholder container
        placeholderContainer.layoutParams = LayoutParams(
            spotlightRect.width().toInt(),
            spotlightRect.height().toInt()
        ).apply {
            val alignView = step.placeholderAlignStartViewProvider?.invoke()
            leftMargin = if (alignView != null) {
                val alignLoc = IntArray(2)
                alignView.getLocationInWindow(alignLoc)
                alignLoc[0] - overlayLoc[0]
            } else {
                spotlightRect.left.toInt()
            }
            val pH = if (step.placeholderYOffsetDp != 0) step.placeholderYOffsetDp * resources.displayMetrics.density else 0f
            topMargin = (spotlightRect.top + pH).toInt()
        }

        // inflatePlaceholder=false keeps the already-swapped secondary content (used when a step
        // expands its spotlight after the crossfade) so the resize doesn't wipe it.
        if (inflatePlaceholder) {
            placeholderContainer.removeAllViews()
            if (step.placeholderLayoutId != null) {
                android.view.LayoutInflater.from(context).inflate(step.placeholderLayoutId, placeholderContainer, true)
            }
        }

        animateSpotlight()

        positionTooltip(step)
    }

    private fun positionTooltip(step: TutorialStep) {
        val forceAbove = step.forceTooltipAbove
        // Force-measure tooltip to get accurate dimensions before positioning
        val maxW = (width - (32 * resources.displayMetrics.density).toInt()).coerceAtLeast(1)
        tooltipView.measure(
            MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        )

        val tooltipH = tooltipView.measuredHeight
        val tooltipW = tooltipView.measuredWidth
        val overlayH = height
        val overlayW = width

        if (overlayW == 0 || overlayH == 0 || tooltipW == 0 || tooltipH == 0) {
            post { positionTooltip(step) }
            return
        }

        val margin = (16 * resources.displayMetrics.density)

        val topIfBelow = spotlightRect.bottom + margin
        val topIfAbove = spotlightRect.top - tooltipH - margin

        // Prefer BELOW the spotlight, near it. Only go above when the spotlight sits low on
        // the screen (no room below); for spotlights in the upper half, keep the tooltip below.
        val belowFits = topIfBelow + tooltipH <= overlayH - margin
        val tooltipTop = when {
            step.forceTooltipBelow -> topIfBelow                     // explicit below (clamped to screen)
            forceAbove -> topIfAbove
            belowFits -> topIfBelow                                  // fits below → below (near spotlight)
            spotlightRect.top >= tooltipH + 2 * margin -> topIfAbove // no room below → above if it fits there
            else -> topIfBelow                                       // tall spotlight: below, clamped
        }

        // Center on spotlight horizontally, clamped to screen edges
        var tooltipLeft = spotlightRect.centerX() - tooltipW / 2f
        val maxLeft = (overlayW - tooltipW - margin).toFloat()
        tooltipLeft = tooltipLeft.coerceIn(margin, if (maxLeft > margin) maxLeft else margin)

        val maxTop = (overlayH - tooltipH - margin).toFloat()
        var tooltipY = tooltipTop.coerceIn(margin, if (maxTop > margin) maxTop else margin)

        // Per-step vertical overrides, allowed to go closer to the screen top than the default clamp
        val minTop = 8f * resources.displayMetrics.density
        val bottomAlignView = step.tooltipBottomAlignViewProvider?.invoke()
        if (bottomAlignView != null) {
            val loc = IntArray(2)
            bottomAlignView.getLocationInWindow(loc)
            val overlayLoc = IntArray(2)
            getLocationInWindow(overlayLoc)
            val alignBottom = (loc[1] - overlayLoc[1] + bottomAlignView.height).toFloat()
            tooltipY = (alignBottom - tooltipH).coerceAtLeast(minTop)
        } else if (step.tooltipAboveSpotlight) {
            tooltipY = (spotlightRect.top - margin - tooltipH).coerceAtLeast(minTop)
        }
        if (step.tooltipPinTopMarginDp != null) {
            tooltipY = (step.tooltipPinTopMarginDp * resources.displayMetrics.density).coerceAtLeast(minTop)
        }
        // Place the tooltip just below a specific view (e.g. the last fake history card), centred on screen.
        val belowView = step.tooltipBelowViewProvider?.invoke()
        if (belowView != null && belowView.height > 0) {
            val bLoc = IntArray(2); belowView.getLocationInWindow(bLoc)
            val oLoc = IntArray(2); getLocationInWindow(oLoc)
            val viewBottom = (bLoc[1] - oLoc[1] + belowView.height).toFloat()
            tooltipY = (viewBottom + margin).coerceIn(minTop, if (maxTop > minTop) maxTop else minTop)
            tooltipLeft = ((overlayW - tooltipW) / 2f).coerceIn(margin, if (maxLeft > margin) maxLeft else margin)
        }

        // Position the tooltip via LAYOUT margins, NOT setX/setY. setY() maps to translationY, and
        // enterContent() animates translationY back to 0 on every step — which used to overwrite
        // this placement and pin every tooltip to the very top of the screen (the reported bug).
        tooltipView.translationX = 0f
        tooltipView.layoutParams = (tooltipView.layoutParams as LayoutParams).apply {
            width = tooltipW
            leftMargin = tooltipLeft.toInt()
            topMargin = tooltipY.toInt()
        }
    }

    private fun showNextStep() {
        currentIndex++
        if (currentIndex >= steps.size) {
            dismiss()
            onComplete?.invoke()
        } else {
            showStep(currentIndex)
        }
    }

    fun dismiss() {
        currentStep?.onHide?.invoke()
        clearPointerAndSecondary()
        val parent = this.parent as? ViewGroup
        parent?.removeView(this)
        onDismissed?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        // 60% transparent black dim
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // Punch rounded-rect spotlight hole (drawnRect glides toward spotlightRect)
        if (!drawnRect.isEmpty) {
            canvas.drawRoundRect(drawnRect, cornerRadius, cornerRadius, clearPaint)

            // Draw placeholder ghost text inside spotlight when container is empty
            val ph = currentStep?.placeholderText
            if (ph != null) {
                val textY = drawnRect.centerY() + placeholderPaint.textSize / 3f
                canvas.drawText(ph, drawnRect.centerX(), textY, placeholderPaint)
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // Draw the click waves AFTER children so they sit on top of the opaque placeholder
        // (in onDraw they were painted under the placeholder and never showed).
        if (pointerRippleActive) drawPointerRipples(canvas)
    }

    /** Concentric "click" waves rippling from the pointer tip. */
    private fun drawPointerRipples(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val rMin = 5f * density
        val rMax = 34f * density
        for (k in 0..1) {
            val t = (pointerRipplePhase + k * 0.5f) % 1f
            val r = rMin + (rMax - rMin) * t
            pointerRipplePaint.alpha = ((1f - t) * 190f).toInt().coerceIn(0, 255)
            pointerRipplePaint.strokeWidth = (2.6f - 1.4f * t).coerceAtLeast(1f) * density
            canvas.drawCircle(pointerRippleCx, pointerRippleCy, r, pointerRipplePaint)
        }
        pointerRipplePaint.alpha = 255
    }

    override fun onDetachedFromWindow() {
        spotlightAnimator?.cancel()
        spotlightAnimator = null
        clearPointerAndSecondary()
        super.onDetachedFromWindow()
    }

    private fun reducedMotion(): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    } catch (_: Exception) {
        false
    }

    /** Glide the rendered spotlight from its current rect to the freshly computed target. */
    private fun animateSpotlight() {
        spotlightAnimator?.cancel()
        // First reveal, or reduced motion: snap (no jarring glide from the corner).
        if (drawnRect.isEmpty || reducedMotion()) {
            drawnRect.set(spotlightRect)
            invalidate()
            return
        }
        animFrom.set(drawnRect)
        spotlightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedFraction
                drawnRect.set(
                    animFrom.left + (spotlightRect.left - animFrom.left) * f,
                    animFrom.top + (spotlightRect.top - animFrom.top) * f,
                    animFrom.right + (spotlightRect.right - animFrom.right) * f,
                    animFrom.bottom + (spotlightRect.bottom - animFrom.bottom) * f
                )
                invalidate()
            }
            start()
        }
    }

    /** Fade + slide the tooltip and placeholder in when a step appears. */
    private fun enterContent() {
        if (reducedMotion()) {
            tooltipView.alpha = 1f; tooltipView.translationY = 0f
            placeholderContainer.alpha = 1f
            return
        }
        tooltipView.animate().cancel()
        tooltipView.alpha = 0f
        tooltipView.translationY = 10f * resources.displayMetrics.density
        tooltipView.animate().alpha(1f).translationY(0f).setDuration(220).start()
        placeholderContainer.animate().cancel()
        placeholderContainer.alpha = 0f
        placeholderContainer.animate().alpha(1f).setDuration(220).start()
    }

    /** Kick off the per-step finger-tap hint and any delayed placeholder swap. */
    private fun startPointerAndSecondary(step: TutorialStep) {
        step.pointerTapTargetId?.let { startPointerTap(it) }
        // Finger-tap hint on a real spotlighted card (no placeholder view to resolve) — e.g. "tap a record".
        if (step.pointerTapSpotlightCenter) {
            post { if (currentStep === step) showPointerAt(spotlightRect.centerX(), spotlightRect.centerY()) }
        }
        val layoutId = step.secondaryPlaceholderLayoutId ?: return
        val r = Runnable { swapPlaceholder(layoutId) }
        secondaryRunnable = r
        postDelayed(r, step.secondaryPlaceholderDelayMs)
    }

    /** Position + animate the finger pointer over a target view inside the placeholder. */
    private fun startPointerTap(targetId: Int) {
        val step = currentStep
        placeholderContainer.doOnLayout { tryShowPointer(targetId, step, retriesLeft = 5) }
    }

    /**
     * Resolve the target inside the placeholder and show the pointer over it. The placeholder is
     * inflated then laid out asynchronously, so retry a few frames if the target isn't sized yet —
     * otherwise the pointer would silently never appear (the reported "no finger" bug).
     */
    private fun tryShowPointer(targetId: Int, step: TutorialStep?, retriesLeft: Int) {
        if (currentStep !== step) return // step advanced or dismissed: drop this stale attempt
        val target = placeholderContainer.findViewById<View>(targetId)
        if (target == null || target.width == 0 || target.height == 0) {
            if (retriesLeft > 0) post { tryShowPointer(targetId, step, retriesLeft - 1) }
            return
        }
        val tLoc = IntArray(2); target.getLocationInWindow(tLoc)
        val oLoc = IntArray(2); getLocationInWindow(oLoc)
        val cx = (tLoc[0] - oLoc[0] + target.width / 2f)
        val cy = (tLoc[1] - oLoc[1] + target.height / 2f)
        showPointerAt(cx, cy)
    }

    /**
     * Show the finger pointer for the current step: glide it in from the centre of the overlay to
     * the target control, THEN loop a press-dip + click waves on it. Travelling first reads as a
     * deliberate "move here and tap" rather than a static pulse sitting on the button.
     */
    private fun showPointerAt(cx: Float, cy: Float) {
        pointerAnimator?.cancel()
        pointerTravelAnimator?.cancel()
        pointerView.animate().cancel()   // drop any in-flight fade from a superseded show
        pointerView.visibility = View.VISIBLE
        // The finger's touch point is near the halo centre (~0.48, 0.29 of the drawable), so nudge
        // the view so that point lands on the target centre and the press dip pivots around it.
        val baseX = cx - pointerSizePx * TIP_FRACTION_X
        val baseY = cy - pointerSizePx * TIP_FRACTION_Y
        pointerView.pivotX = pointerSizePx * TIP_FRACTION_X
        pointerView.pivotY = pointerSizePx * TIP_FRACTION_Y
        pointerRippleCx = cx
        pointerRippleCy = cy

        // Reduced motion: no travel, no looping — a single static tap hint on the target.
        if (reducedMotion()) {
            pointerView.translationX = baseX
            pointerView.translationY = baseY
            pointerView.alpha = 1f
            pointerView.scaleX = 1f; pointerView.scaleY = 1f
            pointerRippleActive = true
            pointerRipplePhase = 0.35f
            invalidate()
            return
        }

        // Not laid out yet (degenerate size): skip the glide, just tap in place.
        if (width == 0 || height == 0) {
            pointerView.translationX = baseX
            pointerView.translationY = baseY
            pointerView.alpha = 1f
            startPointerClickLoop(baseX, baseY)
            return
        }

        // Glide in from the centre of the overlay; the click waves stay off until it arrives.
        val startX = width / 2f - pointerSizePx * TIP_FRACTION_X
        val startY = height / 2f - pointerSizePx * TIP_FRACTION_Y
        pointerView.translationX = startX
        pointerView.translationY = startY
        pointerView.scaleX = 1f; pointerView.scaleY = 1f
        pointerRippleActive = false
        pointerView.alpha = 0f
        pointerView.animate().alpha(1f).setDuration(200).start()

        var travelCancelled = false
        pointerTravelAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedFraction
                pointerView.translationX = startX + (baseX - startX) * f
                pointerView.translationY = startY + (baseY - startY) * f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { travelCancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!travelCancelled) startPointerClickLoop(baseX, baseY)
                }
            })
            start()
        }
    }

    /** Loop the finger's press-dip + expanding click waves on the resting target. */
    private fun startPointerClickLoop(baseX: Float, baseY: Float) {
        pointerAnimator?.cancel()
        pointerView.translationX = baseX
        pointerView.translationY = baseY
        pointerView.scaleX = 1f          // clear any residual press-dip scale from a cancelled loop
        pointerView.scaleY = 1f
        pointerView.alpha = 1f
        pointerRippleActive = true
        val dip = 6f * resources.displayMetrics.density
        pointerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1150
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = null
            addUpdateListener {
                val f = it.animatedFraction
                pointerRipplePhase = f
                // A short press dip at the start of each loop = the "click".
                val press = if (f < 0.3f) sin(f / 0.3f * PI).toFloat() else 0f
                pointerView.translationY = baseY + dip * press
                val scale = 1f - 0.12f * press
                pointerView.scaleX = scale
                pointerView.scaleY = scale
                invalidate()
            }
            start()
        }
    }

    /** Crossfade the placeholder content to a follow-up layout (e.g. Send-Via → destination). */
    private fun swapPlaceholder(layoutId: Int) {
        clearPointer()
        placeholderContainer.removeAllViews()
        android.view.LayoutInflater.from(context).inflate(layoutId, placeholderContainer, true)
        placeholderContainer.getChildAt(0)?.let { currentStep?.onPlaceholderSwapped?.invoke(it) }
        // A step can keep a tight, pointer-focused spotlight on the tapped control first, then expand
        // it here to fit the destination interface the crossfade reveals. Re-spotlight, keep content.
        val step = currentStep
        val tv = currentTargetView
        if (step?.secondaryMinSpotlightHeightDp != null && tv != null) {
            updateSpotlightForView(
                tv, step,
                inflatePlaceholder = false,
                minHeightOverrideDp = step.secondaryMinSpotlightHeightDp
            )
        }
        if (reducedMotion()) {
            placeholderContainer.alpha = 1f
        } else {
            placeholderContainer.animate().cancel()
            placeholderContainer.alpha = 0f
            placeholderContainer.animate().alpha(1f).setDuration(260).start()
        }
    }

    private fun clearPointer() {
        pointerAnimator?.cancel()
        pointerAnimator = null
        pointerTravelAnimator?.cancel()
        pointerTravelAnimator = null
        pointerView.animate().cancel()
        pointerView.visibility = View.GONE
        pointerRippleActive = false
        invalidate()
    }

    private fun clearPointerAndSecondary() {
        clearPointer()
        secondaryRunnable?.let { removeCallbacks(it) }
        secondaryRunnable = null
    }
}
