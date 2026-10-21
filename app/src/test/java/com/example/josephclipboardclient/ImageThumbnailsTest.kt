package com.EntropicSoftwareLab.ClipboardShare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3.7.6 crash was `Canvas: trying to draw too large(200540160bytes) bitmap`: a picked 50 MP
 * photo (8160 x 6144) decoded whole for a 240 px thumbnail. A hardware canvas refuses anything over
 * 100 MB and throws from the draw pass, so the bound has to hold *before* the bitmap exists — these
 * pin the pure sizing that [ImageThumbnails] decodes against.
 */
class ImageThumbnailsTest {

    /** android.graphics.RecordingCanvas.MAX_BITMAP_SIZE — the limit that killed the app. */
    private val canvasLimitBytes = 100 * 1024 * 1024

    /** The exact source of the field crash. */
    private val crashWidth = 8160
    private val crashHeight = 6144

    private fun bytes(size: Pair<Int, Int>) = size.first.toLong() * size.second.toLong() * 4L

    @Test
    fun theCrashingPhotoIsBoundedFarBelowTheCanvasLimit() {
        // Sanity: undownsampled, this really is the 200 540 160 bytes from the crash report.
        assertEquals(200_540_160L, crashWidth.toLong() * crashHeight.toLong() * 4L)

        for (maxPx in listOf(ImageThumbnails.GRID_MAX_PX, ImageThumbnails.FULLSCREEN_MAX_PX)) {
            val size = ImageThumbnails.targetSizeFor(crashWidth, crashHeight, maxPx)
            assertEquals(maxPx, maxOf(size.first, size.second))
            assertTrue("$maxPx px must stay far under the canvas limit", bytes(size) < canvasLimitBytes / 4)
        }
    }

    @Test
    fun boundingKeepsTheAspectRatio() {
        val (w, h) = ImageThumbnails.targetSizeFor(crashWidth, crashHeight, ImageThumbnails.GRID_MAX_PX)
        val sourceRatio = crashWidth.toDouble() / crashHeight
        assertEquals(sourceRatio, w.toDouble() / h, 0.02)
    }

    @Test
    fun imagesThatAlreadyFitAreNotUpscaled() {
        assertEquals(120 to 90, ImageThumbnails.targetSizeFor(120, 90, ImageThumbnails.GRID_MAX_PX))
        assertEquals(240 to 240, ImageThumbnails.targetSizeFor(240, 240, ImageThumbnails.GRID_MAX_PX))
    }

    @Test
    fun extremeAspectRatiosNeverProduceAZeroDimension() {
        // A 0-pixel decode target throws, so a panorama must round up to 1 rather than down to 0.
        val (w, h) = ImageThumbnails.targetSizeFor(20_000, 3, ImageThumbnails.GRID_MAX_PX)
        assertEquals(ImageThumbnails.GRID_MAX_PX, w)
        assertTrue("height rounded to $h", h >= 1)
        val (w2, h2) = ImageThumbnails.targetSizeFor(3, 20_000, ImageThumbnails.GRID_MAX_PX)
        assertTrue("width rounded to $w2", w2 >= 1)
        assertEquals(ImageThumbnails.GRID_MAX_PX, h2)
    }

    @Test
    fun degenerateInputsAreSafe() {
        assertEquals(1 to 1, ImageThumbnails.targetSizeFor(0, 0, 240))
        assertEquals(1 to 1, ImageThumbnails.targetSizeFor(-4, 10, 240))
        assertEquals(1 to 1, ImageThumbnails.targetSizeFor(100, 100, 0))
        assertEquals(1, ImageThumbnails.sampleSizeFor(0, 0, 240))
        assertEquals(1, ImageThumbnails.sampleSizeFor(100, 100, 0))
    }

    @Test
    fun sampleSizeIsAPowerOfTwoThatLandsWithinTwiceTheBound() {
        for (maxPx in listOf(ImageThumbnails.GRID_MAX_PX, ImageThumbnails.FULLSCREEN_MAX_PX)) {
            for ((w, h) in listOf(crashWidth to crashHeight, 4032 to 3024, 1920 to 1080, 300 to 200)) {
                val sample = ImageThumbnails.sampleSizeFor(w, h, maxPx)
                assertTrue("$sample is not a power of two", sample > 0 && (sample and (sample - 1)) == 0)
                val largest = maxOf(w, h) / sample
                // inSampleSize is power-of-two only, so it can overshoot by up to 2x — never more,
                // and clampToMax trims the remainder at decode time.
                assertTrue("$w x $h /$sample = $largest px", largest <= 2 * maxPx)
            }
        }
    }

    @Test
    fun smallImagesAreNotSubsampled() {
        assertEquals(1, ImageThumbnails.sampleSizeFor(200, 150, ImageThumbnails.GRID_MAX_PX))
    }

    @Test
    fun theFullscreenBoundIsWorstCaseSafe() {
        val worstCase = ImageThumbnails.FULLSCREEN_MAX_PX.toLong() * ImageThumbnails.FULLSCREEN_MAX_PX * 4L
        assertTrue("a square fullscreen preview is $worstCase bytes", worstCase < canvasLimitBytes)
    }
}
