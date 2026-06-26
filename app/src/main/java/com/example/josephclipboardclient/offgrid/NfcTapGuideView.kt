package com.EntropicSoftwareLab.ClipboardShare.offgrid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.EntropicSoftwareLab.ClipboardShare.R
import kotlin.math.min

/**
 * A clean "tap here" guide for an NFC hand-off.
 *
 * A single portrait phone is drawn showing its BACK (the camera side faces the viewer — this
 * is the side you actually hold against the other device for NFC). No 3D tilt, no orbit, no
 * second phone. The back carries a classic camera module (per-model artwork isn't available
 * generically, so a generic dual-lens module is drawn). At the device's detected NFC antenna
 * spot — horizontally centred, vertically per [antennaFraction] (and kept clear of the camera
 * module) — a glowing dot emits big concentric rings that ripple outward in the plane of the
 * back and fade, looping seamlessly. The rings may grow beyond the phone body but fade out
 * before the view edge.
 *
 * One [ValueAnimator] drives the ripple. Reduced-motion draws a single static ring.
 */
class NfcTapGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * Vertical position of the NFC antenna on the phone, 1.0 = top edge of the body,
     * 0.0 = bottom edge. The activity sets this from [NfcAntennaLocator]. Default ~0.9 (top).
     */
    var antennaFraction: Float = 0.9f
        set(value) {
            val v = value.coerceIn(0.05f, 0.95f)
            if (field != v) {
                field = v
                recomputeOrigin()
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val accent = Color.parseColor("#34D399")

    private var wavePhase = 0f
    private var animator: ValueAnimator? = null

    // ---- geometry, recomputed on resize -------------------------------------------------
    private val phoneRect = RectF()      // outer body (back)
    private val moduleRect = RectF()     // camera-module bump (top-left of the back)
    private var bodyCorner = 0f
    private var moduleCorner = 0f
    private var moduleBottom = 0f
    private var originX = 0f             // antenna dot, view coords
    private var originY = 0f
    private var maxRingRadius = 0f       // rings fade out by here (within view bounds)

    // Subtle dark "glass back" gradient (top a touch lighter than the bottom).
    private val backColors = intArrayOf(0xFF2B323C.toInt(), 0xFF222831.toInt(), 0xFF171C23.toInt())
    private val backStops = floatArrayOf(0f, 0.55f, 1f)

    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = resolveThemeColor(R.attr.appTextPrimaryColor, Color.LTGRAY).withAlpha(0x55)
    }
    // Camera module
    private val modulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF14181E.toInt()
    }
    private val moduleRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
        color = Color.WHITE.withAlpha(0x22)
    }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF090C11.toInt()
    }
    private val lensRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = resolveThemeColor(R.attr.appTextSecondaryColor, Color.GRAY).withAlpha(0x99)
    }
    private val lensGlintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF3B82F6.toInt().withAlpha(0x66)
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFFFF3C4.toInt()
    }
    private val sensorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF202632.toInt()
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = accent
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }

    init {
        contentDescription = context.getString(R.string.nfc_guide_subtitle_send)
    }

    private fun resolveThemeColor(attr: Int, fallback: Int): Int = try {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    } catch (_: Exception) { fallback }

    private fun Int.withAlpha(a: Int): Int = (this and 0x00FFFFFF) or (a shl 24)

    private fun isReducedMotion(): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    } catch (_: Exception) { false }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        // Portrait phone, ~0.46 of view width wide, ~2.2:1 tall (10% longer than before), centred
        // and bounded by height. Width is unchanged, so the body just gets longer.
        val bodyW = (w * 0.46f).coerceAtMost(h * 0.46f)
        val bodyH = (bodyW * 2.22f).coerceAtMost(h * 0.90f)
        val cx = w / 2f
        val cy = h / 2f
        phoneRect.set(cx - bodyW / 2f, cy - bodyH / 2f, cx + bodyW / 2f, cy + bodyH / 2f)
        bodyCorner = bodyW * 0.16f

        // Classic square camera module in the top-left corner of the back.
        val modSize = bodyW * 0.42f
        val modLeft = phoneRect.left + bodyW * 0.09f
        val modTop = phoneRect.top + bodyW * 0.09f
        moduleRect.set(modLeft, modTop, modLeft + modSize, modTop + modSize)
        moduleCorner = modSize * 0.26f
        moduleBottom = moduleRect.bottom

        recomputeOrigin()

        // Big rings: allow them to grow well past the phone, but fade before the view edge.
        val edgeMargin = 8f * density
        maxRingRadius = min(
            min(originX - edgeMargin, w - originX - edgeMargin),
            min(originY - edgeMargin, h - originY - edgeMargin)
        ).coerceAtLeast(bodyW * 0.6f)

        invalidate()
    }

    private fun recomputeOrigin() {
        if (phoneRect.width() <= 0f) return
        originX = phoneRect.centerX()
        // antennaFraction: 1.0 = top of body, 0.0 = bottom.
        originY = phoneRect.bottom - antennaFraction * phoneRect.height()
        // Keep the glowing antenna spot clear of the camera module so it never sits under a lens.
        if (moduleBottom > 0f) {
            val minBelow = moduleBottom + phoneRect.width() * 0.14f
            if (originY < minBelow) originY = minBelow
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isReducedMotion()) {
            wavePhase = 0.5f          // a single mid-size static ring
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = null       // linear, so the ripple loops seamlessly
            addUpdateListener { wavePhase = it.animatedFraction; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (phoneRect.width() <= 0f) return

        drawPhoneBack(canvas)
        drawRipples(canvas)
        drawOrigin(canvas)
    }

    private fun drawPhoneBack(canvas: Canvas) {
        // Back body with a subtle dark glass gradient
        backPaint.shader = LinearGradient(
            0f, phoneRect.top, 0f, phoneRect.bottom,
            backColors, backStops, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(phoneRect, bodyCorner, bodyCorner, backPaint)
        backPaint.shader = null

        // Thin rim around the body
        canvas.drawRoundRect(phoneRect, bodyCorner, bodyCorner, rimPaint)

        drawCameraModule(canvas)
    }

    /** A generic dual-lens camera module (classic back design — per-model artwork unavailable). */
    private fun drawCameraModule(canvas: Canvas) {
        canvas.drawRoundRect(moduleRect, moduleCorner, moduleCorner, modulePaint)
        canvas.drawRoundRect(moduleRect, moduleCorner, moduleCorner, moduleRimPaint)

        val s = moduleRect.width()
        val l = moduleRect.left
        val t = moduleRect.top
        // Two lenses on the diagonal.
        drawLens(canvas, l + s * 0.34f, t + s * 0.34f, s * 0.17f)
        drawLens(canvas, l + s * 0.63f, t + s * 0.64f, s * 0.17f)
        // Flash (pale yellow) top-right.
        canvas.drawCircle(l + s * 0.74f, t + s * 0.26f, s * 0.055f, flashPaint)
        // Tiny sensor/mic dot bottom-left.
        canvas.drawCircle(l + s * 0.26f, t + s * 0.72f, s * 0.035f, sensorPaint)
    }

    private fun drawLens(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, lensPaint)                     // dark glass
        lensRingPaint.strokeWidth = r * 0.22f
        canvas.drawCircle(cx, cy, r * 0.86f, lensRingPaint)        // metal ring
        canvas.drawCircle(cx - r * 0.3f, cy - r * 0.3f, r * 0.3f, lensGlintPaint) // glint
    }

    /** Big concentric rings rippling outward from the antenna origin, looping seamlessly. */
    private fun drawRipples(canvas: Canvas) {
        val minR = (10f * density)
        val span = maxRingRadius - minR
        if (span <= 0f) return

        val count = 4
        ringPaint.style = Paint.Style.STROKE
        for (k in 0 until count) {
            // Evenly spaced, continuously advancing phases; wraps for a seamless loop.
            val t = (wavePhase + k.toFloat() / count) % 1f
            val r = minR + span * t
            // Fade in quickly, fade out as the ring reaches its maximum.
            val fadeIn = (t / 0.12f).coerceAtMost(1f)
            val fadeOut = 1f - t
            val alpha = (fadeIn * fadeOut).coerceIn(0f, 1f)
            if (alpha <= 0.01f) continue
            ringPaint.alpha = (alpha * 210f).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = (3.4f - 1.6f * t).coerceAtLeast(1.2f) * density
            canvas.drawCircle(originX, originY, r, ringPaint)
        }
        ringPaint.alpha = 255
    }

    /** The glowing antenna spot the rings emanate from. */
    private fun drawOrigin(canvas: Canvas) {
        val glowR = phoneRect.width() * 0.30f
        glowPaint.shader = RadialGradient(
            originX, originY, glowR,
            intArrayOf(accent.withAlpha(0x80), accent.withAlpha(0)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(originX, originY, glowR, glowPaint)
        glowPaint.shader = null

        val dotR = phoneRect.width() * 0.045f
        canvas.drawCircle(originX, originY, dotR, dotPaint)
        // A soft white core for a "live" highlight.
        dotPaint.color = Color.WHITE.withAlpha(0xCC)
        canvas.drawCircle(originX, originY, dotR * 0.42f, dotPaint)
        dotPaint.color = accent
    }
}
