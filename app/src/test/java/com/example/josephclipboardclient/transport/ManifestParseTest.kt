package com.EntropicSoftwareLab.ClipboardShare.transport

import com.EntropicSoftwareLab.ClipboardShare.TransferUtils
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract for the LAN file-offer MANIFEST parser. MUST stay byte-for-byte in step with the Windows
 * client's ManifestCodecTest — the same inputs must produce the same (name, size) list on both
 * platforms, or the positional bulk-byte stream desyncs across a cross-platform transfer.
 *
 * The `:N` (empty-name) case is the parity fix in 3.5.1: Android's old LAN parser accepted such an
 * entry (`contains(':')`, lastColonIndex == 0) while Windows and Android's own off-grid path skipped
 * it (`lastColonIndex > 0`). All three now skip it.
 */
class ManifestParseTest {

    @Test
    fun parsesSimpleEntries() {
        assertEquals(
            listOf("a.txt" to 10L, "b.bin" to 2048L),
            TransferUtils.parseManifestEntries("MANIFEST|a.txt:10|b.bin:2048|")
        )
    }

    @Test
    fun splitsOnLastColonSoNameMayContainColon() {
        assertEquals(
            listOf("10:30:report.txt" to 512L),
            TransferUtils.parseManifestEntries("MANIFEST|10:30:report.txt:512|")
        )
    }

    @Test
    fun skipsMalformedEntries() {
        assertEquals(
            listOf("good.txt" to 7L),
            TransferUtils.parseManifestEntries("MANIFEST|noColon|x.txt:notanumber|good.txt:7|")
        )
    }

    @Test
    fun skipsEmptyNameEntry_crossPlatformParity() {
        // ":512" has lastColonIndex == 0 (empty name) → skipped, matching Windows. The one file with
        // a real name survives; the degenerate entry does not become ("", 512).
        assertEquals(
            listOf("real.txt" to 1L),
            TransferUtils.parseManifestEntries("MANIFEST|:512|real.txt:1|")
        )
    }

    @Test
    fun emptyForNonManifestLine() {
        assertEquals(emptyList<Pair<String, Long>>(), TransferUtils.parseManifestEntries("OFFER|sid|name|1"))
    }
}
