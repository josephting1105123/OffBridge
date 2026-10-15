package com.joseph.clipboard.windows.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class PublishedChecksumTest {
    private val sha = "f50b42e8c2a63e1d8ca56a879d2355736e096588b5ee6e26894009b64c2f2a85"
    private val config = """
        android: {
          version: "3.7.6",
          sha256: "0fee13955fe3407ec8f9d6f2a7c02b00dce192f98f6204d452d12f1c49293b40",
        },
        windows: {
          version: "3.7.10",
          // comment line
          sha256: "$sha",
        },
    """.trimIndent()

    @Test
    fun parsesWindowsBlockNotAndroid() {
        assertEquals("3.7.10" to sha, PublishedChecksum.parseWindows(config))
    }

    @Test
    fun parsesTheRealWebsiteConfig() {
        // Guards against the website's config.js drifting into a shape the updater can't read.
        val real = File("../website/js/config.js").takeIf { it.exists() } ?: File("website/js/config.js")
        val parsed = PublishedChecksum.parseWindows(real.readText())
        requireNotNull(parsed)
        assertEquals(64, parsed.second.length)
    }

    @Test
    fun missingOrMalformedHashIsNull() {
        assertNull(PublishedChecksum.parseWindows("windows: {\n version: \"1.0\",\n sha256: \"\",\n}"))
        assertNull(PublishedChecksum.parseWindows("windows: {\n version: \"1.0\",\n sha256: \"XYZ\",\n}"))
        assertNull(PublishedChecksum.parseWindows("android: { version: \"1.0\" }"))
    }

    @Test
    fun matchingHashPasses() {
        PublishedChecksum.check(config, "v3.7.10", sha)
        PublishedChecksum.check(config, "3.7.10", sha.uppercase())
    }

    @Test
    fun mismatchedHashIsRefused() {
        assertThrows(PublishedChecksum.ChecksumMismatchException::class.java) {
            PublishedChecksum.check(config, "3.7.10", "0".repeat(64))
        }
    }

    @Test
    fun unpublishedVersionIsRefused() {
        assertThrows(PublishedChecksum.ChecksumUnavailableException::class.java) {
            PublishedChecksum.check(config, "v3.7.11", sha)
        }
    }

    @Test
    fun hashesFileContents() {
        val f = File.createTempFile("checksum", ".bin")
        try {
            f.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                PublishedChecksum.sha256Hex(f),
            )
        } finally {
            f.delete()
        }
    }
}
