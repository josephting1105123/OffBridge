package com.joseph.clipboard.windows.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors for the screenshot control frames, duplicated verbatim on Android and Windows.
 *
 * The two clients hand-roll the same wire protocol with no shared module, so the only thing keeping
 * them speaking it is a pair of tests asserting the same literal bytes. Change a string here and
 * the other platform's copy has to change with it, or one of the two suites goes red.
 */
class ScreenshotWireTest {

    @Test
    fun availableLineIsExactlyThisShape() {
        assertEquals(
            "SCREENSHOT_AVAILABLE|a1b2c3d4|Q1MxAAABBBB=",
            ScreenshotWire.availableLine("a1b2c3d4", "Q1MxAAABBBB=")
        )
    }

    @Test
    fun fetchLineIsExactlyThisShape() {
        assertEquals("SCREENSHOT_FETCH|a1b2c3d4", ScreenshotWire.fetchLine("a1b2c3d4"))
    }

    @Test
    fun availableRoundTrips() {
        val line = ScreenshotWire.availableLine("sess01", "c2VhbGVk")
        assertEquals("sess01" to "c2VhbGVk", ScreenshotWire.parseAvailable(line))
    }

    @Test
    fun fetchRoundTrips() {
        assertEquals("sess01", ScreenshotWire.parseFetch(ScreenshotWire.fetchLine("sess01")))
    }

    @Test
    fun parsersRejectOtherFrames() {
        // The dispatchers try these prefixes alongside OFFER/ACCEPT_OFFER on the same socket, so a
        // parser that accepted a neighbouring frame would hijack a real transfer.
        assertNull(ScreenshotWire.parseAvailable("OFFER|abc|Pixel|1"))
        assertNull(ScreenshotWire.parseFetch("ACCEPT_OFFER|abc"))
        assertNull(ScreenshotWire.parseAvailable("SCREENSHOT_AVAILABLE|onlysession"))
        assertNull(ScreenshotWire.parseFetch("SCREENSHOT_FETCH|"))
        assertNull(ScreenshotWire.parseFetch("SCREENSHOT_FETCH|a|b"))
    }

    @Test
    fun metadataEncodingIsExactlyThisShape() {
        assertEquals(
            "Screenshot_20260831.png|204800|1756600000000|dGh1bWI=",
            ScreenshotWire.encodeMetadata(
                ScreenshotWire.Metadata("Screenshot_20260831.png", 204800L, 1756600000000L, "dGh1bWI=")
            )
        )
    }

    @Test
    fun metadataRoundTripsWithAPipeInTheFilename() {
        // Fields are split from the right precisely so this survives; splitting left-to-right would
        // truncate the name and mis-read the size.
        val original = ScreenshotWire.Metadata("weird|name.png", 1L, 2L, "dGh1bWI=")
        assertEquals(original, ScreenshotWire.decodeMetadata(ScreenshotWire.encodeMetadata(original)))
    }

    @Test
    fun metadataToleratesAnAbsentThumbnail() {
        val original = ScreenshotWire.Metadata("shot.png", 10L, 20L, "")
        assertEquals(original, ScreenshotWire.decodeMetadata(ScreenshotWire.encodeMetadata(original)))
    }

    @Test
    fun malformedMetadataIsNullNotPartial() {
        assertNull(ScreenshotWire.decodeMetadata(""))
        assertNull(ScreenshotWire.decodeMetadata("only|three|fields"))
        assertNull(ScreenshotWire.decodeMetadata("name|notanumber|2|t"))
        assertNull(ScreenshotWire.decodeMetadata("name|1|notanumber|t"))
        assertNull(ScreenshotWire.decodeMetadata("|1|2|t"))
        assertNull(ScreenshotWire.decodeMetadata("name|-1|2|t"))
    }

    @Test
    fun oversizedThumbnailIsRejected() {
        val huge = "A".repeat(ScreenshotWire.MAX_THUMBNAIL_B64 + 1)
        assertNull(ScreenshotWire.decodeMetadata("shot.png|1|2|$huge"))
    }

    @Test
    fun onlyImagesMayBeOpenedUnattended() {
        assertTrue(ScreenshotWire.isOpenableImage("shot.PNG"))
        assertTrue(ScreenshotWire.isOpenableImage("shot.jpeg"))
        assertTrue(ScreenshotWire.isOpenableImage("shot.webp"))
        // The whole point of the allowlist: an auto-accepted, auto-opened transfer must not be able
        // to hand the shell something executable.
        assertFalse(ScreenshotWire.isOpenableImage("shot.exe"))
        assertFalse(ScreenshotWire.isOpenableImage("shot.png.exe"))
        assertFalse(ScreenshotWire.isOpenableImage("shot.svg"))
        assertFalse(ScreenshotWire.isOpenableImage("shot"))
        assertFalse(ScreenshotWire.isOpenableImage("shot."))
    }
}
