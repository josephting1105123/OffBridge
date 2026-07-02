package com.joseph.clipboard.windows.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden-string contract for the outbound control frames. Every literal here was copied from the
 * pre-extraction inline concatenations in ClipboardAppController/OffGridQrSender — if one of these
 * assertions fails, the Android peer will misparse the frame. Do not update an expectation without
 * a matching Android-side change and an interop check.
 */
class WireFramesTest {

    @Test
    fun offerLine() {
        assertEquals(
            "OFFER|sid-123|My PC|3\n",
            WireFrames.offerLine("sid-123", "My PC", 3)
        )
    }

    @Test
    fun offerProofLine() {
        assertEquals("OFFER_PROOF|cHJvb2Y=\n", WireFrames.offerProofLine("cHJvb2Y="))
    }

    @Test
    fun unpairReqIsBareLineWithTrailingPipe() {
        // Written with PrintWriter.println — the terminator is NOT part of the frame string.
        assertEquals("UNPAIR_REQ|", WireFrames.unpairReq())
    }

    @Test
    fun unpairProofIsBareLine() {
        assertEquals("UNPAIR_PROOF|cHJvb2Y=", WireFrames.unpairProof("cHJvb2Y="))
    }
}
