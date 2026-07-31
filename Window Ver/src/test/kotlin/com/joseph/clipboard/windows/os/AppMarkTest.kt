package com.joseph.clipboard.windows.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The Windows client must show the SAME mark as the phone — the white clipboard glyph on the
 * #1E1E1E squircle — everywhere the user sees it. The tray used to draw a flat emerald square
 * instead, which is the one place the two clients disagreed on their own logo.
 *
 * These PNGs are generated from the Android vector by installer/make-app-icon.ps1, so this test is
 * really asserting that the generated files are present, packaged, and are that mark. If it fails
 * after an icon change, re-run the script rather than hand-editing artwork.
 */
class AppMarkTest {

    private val sizes = listOf(16, 24, 32, 48, 256)

    private fun load(size: Int): BufferedImage {
        val stream = javaClass.classLoader.getResourceAsStream("windows/app-$size.png")
        assertNotNull("windows/app-$size.png must be on the classpath (run make-app-icon.ps1)", stream)
        return stream!!.use { ImageIO.read(it) }
    }

    @Test
    fun everyTraySizeIsPackagedAtItsNominalDimensions() {
        for (size in sizes) {
            val image = load(size)
            assertEquals("app-$size.png width", size, image.width)
            assertEquals("app-$size.png height", size, image.height)
        }
    }

    @Test
    fun theMarkIsCharcoalAndWhite_neverTheRetiredGreenPlaceholder() {
        val green = SystemTrayManager.RETIRED_PLACEHOLDER.rgb and 0xFFFFFF
        for (size in sizes) {
            val image = load(size)
            var dark = 0
            var white = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val argb = image.getRGB(x, y)
                    val alpha = (argb ushr 24) and 0xFF
                    if (alpha < 200) continue
                    assertTrue(
                        "app-$size.png still contains the retired tray placeholder at ($x,$y)",
                        (argb and 0xFFFFFF) != green
                    )
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF
                    if (r < 60 && g < 60 && b < 60) dark++
                    if (r > 200 && g > 200 && b > 200) white++
                }
            }
            // Both layers must actually be there: a blank or single-colour file would pass a
            // dimensions check and still ship the wrong logo.
            assertTrue("app-$size.png has no charcoal background pixels", dark > 0)
            assertTrue("app-$size.png has no white glyph pixels", white > 0)
        }
    }

    @Test
    fun theMarkIsASquircle_soTheCornersStayTransparent() {
        for (size in sizes) {
            val alpha = (load(size).getRGB(0, 0) ushr 24) and 0xFF
            assertTrue("app-$size.png corner should be transparent, alpha was $alpha", alpha < 40)
        }
    }

    @Test
    fun theTrayResolvesTheSmallestEntryThatCoversTheRequestedSize() {
        assertEquals(16, (SystemTrayManager.loadAppMark(16) as BufferedImage).width)
        assertEquals(24, (SystemTrayManager.loadAppMark(20) as BufferedImage).width)
        assertEquals(32, (SystemTrayManager.loadAppMark(32) as BufferedImage).width)
        // Beyond the largest entry, fall back to the largest rather than to nothing.
        assertEquals(256, (SystemTrayManager.loadAppMark(512) as BufferedImage).width)
    }
}
