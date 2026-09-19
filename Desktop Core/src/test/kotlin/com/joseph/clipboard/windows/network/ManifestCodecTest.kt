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
    fun skipsEmptyNameEntry_crossPlatformParity() {
        // ":512" has lastColonIndex == 0 (empty name) → skipped. Mirror of the Android 3.5.1 parity
        // fix (ManifestParseTest): Android's old LAN parser accepted this as ("", 512); now both skip it.
        assertEquals(
            listOf("real.txt" to 1L),
            ManifestCodec.parse("MANIFEST|:512|real.txt:1|")
        )
    }

    @Test
    fun emptyForNonManifestLine() {
        assertEquals(emptyList<Pair<String, Long>>(), ManifestCodec.parse("OFFER|sid|name|1"))
    }

    // --- encode: golden wire bytes. These literals are the frozen cross-platform format ---

    @Test
    fun encodeEmitsTrailingPipeAndNewline() {
        assertEquals("MANIFEST|a.txt:10|b.bin:2048|\n", ManifestCodec.encode(listOf("a.txt" to 10L, "b.bin" to 2048L)))
    }

    @Test
    fun encodeEmptyManifest() {
        assertEquals("MANIFEST|\n", ManifestCodec.encode(emptyList()))
    }

    @Test
    fun encodePreservesColonInName() {
        assertEquals("MANIFEST|10:30:report.txt:512|\n", ManifestCodec.encode(listOf("10:30:report.txt" to 512L)))
    }

    @Test
    fun parseRoundTripsEncode() {
        val entries = listOf("a.txt" to 10L, "10:30:report.txt" to 512L, "b.bin" to 0L)
        assertEquals(entries, ManifestCodec.parse(ManifestCodec.encode(entries).trimEnd('\n')))
    }

    @Test
    fun parseAcceptsEncodedLineWithNewline() {
        // readLineUtf8 strips the newline in practice, but parse must tolerate it either way.
        val entries = listOf("x.dat" to 7L)
        assertEquals(entries, ManifestCodec.parse(ManifestCodec.encode(entries)))
    }
}
