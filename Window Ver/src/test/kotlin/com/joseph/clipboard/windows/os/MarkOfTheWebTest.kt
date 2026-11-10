package com.joseph.clipboard.windows.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MarkOfTheWebTest {
    private val isWindows = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)

    @Test
    fun taggingLeavesMainContentUnchanged() {
        val f = File.createTempFile("motw", ".txt")
        try {
            f.writeText("hello")
            MarkOfTheWeb.tag(f)
            assertEquals("hello", f.readText())
        } finally {
            f.delete()
        }
    }

    @Test
    fun taggedFileCarriesZoneIdOnWindows() {
        assumeTrue(isWindows)
        val f = File.createTempFile("motw", ".txt")
        try {
            f.writeText("hello")
            val tagged = MarkOfTheWeb.tag(f)
            // The temp dir could be on a volume without alternate data streams; nothing to assert then.
            assumeTrue(tagged)
            val zone = File(f.path + ":Zone.Identifier").readText()
            assertTrue(zone, zone.contains("ZoneId=3"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun taggingMissingFileReturnsFalse() {
        val f = File.createTempFile("motw", ".txt")
        f.delete()
        assertFalse(MarkOfTheWeb.tag(f))
    }
}
