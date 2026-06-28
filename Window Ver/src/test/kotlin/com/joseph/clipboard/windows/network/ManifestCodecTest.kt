package com.joseph.clipboard.windows.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract for the LAN file-offer MANIFEST parser, including the cross-platform parity fix: split on
 * the LAST ':' so a filename containing ':' is preserved (matches Android's lastIndexOf(':')).
 */
class ManifestCodecTest {

    @Test
    fun parsesSimpleEntries() {
        assertEquals(
            listOf("a.txt" to 10L, "b.bin" to 2048L),
            ManifestCodec.parse("MANIFEST|a.txt:10|b.bin:2048|")
        )
    }

    @Test
    fun splitsOnLastColonSoNameMayContainColon() {
        // The parity fix: an earlier split(":") would have truncated this name to "10".
        assertEquals(
            listOf("10:30:report.txt" to 512L),
            ManifestCodec.parse("MANIFEST|10:30:report.txt:512|")
        )
    }

    @Test
    fun skipsMalformedEntries() {
        assertEquals(
            listOf("good.txt" to 7L),
            ManifestCodec.parse("MANIFEST|noColon|x.txt:notanumber|good.txt:7|")
        )
    }

    @Test
    fun emptyForNonManifestLine() {
        assertEquals(emptyList<Pair<String, Long>>(), ManifestCodec.parse("OFFER|sid|name|1"))
    }
}
