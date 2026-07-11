package com.EntropicSoftwareLab.ClipboardShare

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sender maps '|' and ':' out of a manifest leaf name so they can't corrupt the pipe-delimited,
 * last-colon-split MANIFEST framing. Pure host-JVM test (no Android API touched).
 */
class OutgoingManifestNameTest {

    @Test
    fun replacesPipeAndColon() {
        assertEquals("a_b.txt", TransferUtils.sanitizeOutgoingManifestName("a|b.txt"))
        assertEquals("10_30.log", TransferUtils.sanitizeOutgoingManifestName("10:30.log"))
        assertEquals("a_b_c", TransferUtils.sanitizeOutgoingManifestName("a|b:c"))
    }

    @Test
    fun leavesOrdinaryNamesUnchanged() {
        assertEquals("report.pdf", TransferUtils.sanitizeOutgoingManifestName("report.pdf"))
        assertEquals("sub/dir/file.jpg", TransferUtils.sanitizeOutgoingManifestName("sub/dir/file.jpg"))
        assertEquals("", TransferUtils.sanitizeOutgoingManifestName(""))
    }
}
