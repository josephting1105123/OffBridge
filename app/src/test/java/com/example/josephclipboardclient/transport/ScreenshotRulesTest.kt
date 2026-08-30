package com.EntropicSoftwareLab.ClipboardShare.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which MediaStore rows count as a screenshot worth telling the laptop about. */
class ScreenshotRulesTest {

    @Test
    fun matchesTheUsualScreenshotFolders() {
        assertTrue(ScreenshotRules.isScreenshotPath("Pictures/Screenshots/"))
        assertTrue(ScreenshotRules.isScreenshotPath("DCIM/Screenshots/"))
        assertTrue(ScreenshotRules.isScreenshotPath("pictures/screenshots"))
        assertTrue(ScreenshotRules.isScreenshotPath("Pictures/Screenshot/"))
    }

    @Test
    fun ignoresEveryOtherFolder() {
        // The user asked for screenshots, not for every photo they take to appear on the laptop.
        assertFalse(ScreenshotRules.isScreenshotPath("DCIM/Camera/"))
        assertFalse(ScreenshotRules.isScreenshotPath("Pictures/WhatsApp/"))
        assertFalse(ScreenshotRules.isScreenshotPath("Download/"))
        assertFalse(ScreenshotRules.isScreenshotPath(""))
        assertFalse(ScreenshotRules.isScreenshotPath(null))
    }

    @Test
    fun doesNotMatchAFolderThatMerelyContainsTheWord() {
        assertFalse(ScreenshotRules.isScreenshotPath("Pictures/MyScreenshotsBackup/"))
    }

    @Test
    fun treatsDateAddedAsSecondsNotMilliseconds() {
        val nowMs = 1_756_600_000_000L
        val nowSeconds = nowMs / 1000L
        assertTrue(ScreenshotRules.isRecent(nowSeconds, nowMs))

        // The bug this guards: passing milliseconds straight through makes every row look ~55 years
        // old, nothing ever matches, and the feature appears enabled while doing nothing.
        assertFalse(ScreenshotRules.isRecent(nowMs, nowMs))
    }

    @Test
    fun rejectsRowsOutsideTheWindow() {
        val nowMs = 1_756_600_000_000L
        val staleSeconds = (nowMs - ScreenshotRules.RECENCY_WINDOW_MS - 1000L) / 1000L
        assertFalse(ScreenshotRules.isRecent(staleSeconds, nowMs))
        assertFalse(ScreenshotRules.isRecent(0L, nowMs))
        assertFalse(ScreenshotRules.isRecent(-5L, nowMs))
    }

    @Test
    fun toleratesSmallClockJitterButNotAFutureTimestamp() {
        val nowMs = 1_756_600_000_000L
        // MediaStore writes the row a moment before we read it; a second of skew is normal.
        assertTrue(ScreenshotRules.isRecent((nowMs + 1000L) / 1000L, nowMs))
        assertFalse(ScreenshotRules.isRecent((nowMs + 60_000L) / 1000L, nowMs))
    }

    @Test
    fun bothConditionsMustHold() {
        val nowMs = 1_756_600_000_000L
        val fresh = nowMs / 1000L
        val stale = (nowMs - 600_000L) / 1000L
        assertTrue(ScreenshotRules.isFreshScreenshot("Pictures/Screenshots/", fresh, nowMs))
        assertFalse(ScreenshotRules.isFreshScreenshot("DCIM/Camera/", fresh, nowMs))
        assertFalse(ScreenshotRules.isFreshScreenshot("Pictures/Screenshots/", stale, nowMs))
    }
}
