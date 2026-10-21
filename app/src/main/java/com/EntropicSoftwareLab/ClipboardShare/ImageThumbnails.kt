package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri

/**
 * Bounded image decoding for every preview surface (the send-queue grid, the send-via sheet, the
 * fullscreen tap-to-view dialog).
 *
 * A preview must never decode at full resolution. A modern phone camera writes 50 MP files, which
 * decode to ~200 MB of ARGB_8888 pixels, and a hardware-accelerated Canvas refuses to draw a bitmap
 * over 100 MB: it throws `RuntimeException: Canvas: trying to draw too large(N bytes) bitmap` from
 * the *draw* pass (BitmapPainter.onDraw / ImageView.onDraw), where nothing can catch it — the app
 * dies. That is exactly the 3.7.6 field crash: 200 540 160 bytes = 8160 x 6144 x 4, the 50 MP main
 * camera of the reporting device, decoded whole for a 240 px grid thumbnail.
 *
 * Everything here decodes downsampled and clamps the result, so no preview can hand an oversized
 * bitmap to a canvas however large the source is.
 */
object ImageThumbnails {

    /** Longest edge for a grid/list thumbnail — the cards are ~120 dp, so 240 px is sharp at 3x. */
    const val GRID_MAX_PX = 240

    /** Longest edge for the fullscreen viewer: sharp on any phone display, and 16 MB at worst
     *  (2048 x 2048 x 4) against the platform's 100 MB draw limit. */
    const val FULLSCREEN_MAX_PX = 2048

    /**
     * Aspect-preserving target size for a [width] x [height] source bounded to [maxPx] on its
     * longest edge. A source that already fits is returned unchanged (never upscaled), and neither
     * dimension is ever rounded down to 0 — a 0-pixel decode target throws. Pure, so the bound the
     * crash fix rests on is unit-testable without a device.
     */
    fun targetSizeFor(width: Int, height: Int, maxPx: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxPx <= 0) return 1 to 1
        val largest = maxOf(width, height)
        if (largest <= maxPx) return width to height
        val scale = maxPx.toDouble() / largest
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    /**
     * BitmapFactory's [BitmapFactory.Options.inSampleSize] (a power of two, the only values the
     * decoder honours) that brings a [width] x [height] source to at most [maxPx] on its longest
     * edge. Power-of-two rounding can leave the result up to 2x [maxPx], which [clampToMax] then
     * trims.
     */
    fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
        if (width <= 0 || height <= 0 || maxPx <= 0) return 1
        var sample = 1
        var largest = maxOf(width, height)
        while (largest / 2 >= maxPx) {
            largest /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * Decodes [uri] downsampled so its longest edge is at most [maxPx]. Returns null when the
     * source isn't a decodable image (or can't be read) — callers show their file icon instead.
     * Always call this off the main thread: a decode reads and resamples the whole file.
     */
    fun decode(context: Context, uri: Uri, maxPx: Int): Bitmap? {
        val bitmap = decodeWithImageDecoder(context, uri, maxPx)
            ?: decodeWithBitmapFactory(context, uri, maxPx)
        return bitmap?.let { clampToMax(it, maxPx) }
    }

    /** Preferred path: ImageDecoder resamples while decoding (minSdk 29, so always available) and
     *  applies the file's EXIF orientation for us. */
    private fun decodeWithImageDecoder(context: Context, uri: Uri, maxPx: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = targetSizeFor(info.size.width, info.size.height, maxPx)
            decoder.setTargetSize(w, h)
            // Both consumers read the pixels back (Compose's asImageBitmap, a software-drawn
            // dialog), and a HARDWARE-allocated bitmap throws there.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // A file still being received is truncated; show what arrived rather than nothing.
            decoder.setOnPartialImageListener { true }
        }
    } catch (_: Throwable) {
        null
    }

    /** Fallback for sources ImageDecoder rejects: bounds-only pass, then a subsampled decode.
     *  No EXIF rotation on this path — a sideways thumbnail beats no thumbnail. */
    private fun decodeWithBitmapFactory(context: Context, uri: Uri, maxPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (_: Throwable) {
        null
    }

    /** Last line of defence: a decoder that ignored the requested size (or a power-of-two
     *  inSampleSize that couldn't land exactly) still leaves here within [maxPx]. */
    private fun clampToMax(bitmap: Bitmap, maxPx: Int): Bitmap {
        if (maxOf(bitmap.width, bitmap.height) <= maxPx) return bitmap
        val (w, h) = targetSizeFor(bitmap.width, bitmap.height, maxPx)
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (scaled != bitmap) bitmap.recycle()
            scaled
        } catch (_: Throwable) {
            bitmap
        }
    }
}
