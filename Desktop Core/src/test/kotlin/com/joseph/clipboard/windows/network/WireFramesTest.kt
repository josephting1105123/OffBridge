package com.joseph.clipboard.windows.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ---- Pairing handshake: byte-identical to Android's PairingWire -----------------------------

    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun pairReqKeepsPositionalFieldsAndAppendsDeviceId() {
        assertEquals("PAIR_REQ|My PC|cGs=|pv2|did:$id", WireFrames.pairReq("My PC", "cGs=", id))
    }

    @Test
    fun pairAckKeepsPositionalFieldsAndAppendsDeviceId() {
        assertEquals("PAIR_ACK|cGs=|pv2|did:$id", WireFrames.pairAck("cGs=", id))
    }

    /** An older peer's frame has no did token — parsing must yield null, not a crash or a stray value. */
    @Test
    fun parseDeviceIdReturnsNullForLegacyFrames() {
        assertNull(WireFrames.parseDeviceId("PAIR_ACK|cGs=|pv2".split("|").drop(2)))
        assertNull(WireFrames.parseDeviceId(emptyList()))
    }

    @Test
    fun parseDeviceIdReadsTheTokenRegardlessOfOrder() {
        assertEquals(id, WireFrames.parseDeviceId(listOf("pv2", "did:$id")))
        assertEquals(id, WireFrames.parseDeviceId(listOf("did:$id", "pv2")))
    }

    /** The value is persisted and compared, so anything off-format is dropped rather than stored. */
    @Test
    fun parseDeviceIdRejectsMalformedValues() {
        assertNull(WireFrames.parseDeviceId(listOf("did:")))
        assertNull(WireFrames.parseDeviceId(listOf("did:abc")))                      // too short
        assertNull(WireFrames.parseDeviceId(listOf("did:${id}00")))                  // too long
        assertNull(WireFrames.parseDeviceId(listOf("did:${id.uppercase()}")))        // wrong case
        assertNull(WireFrames.parseDeviceId(listOf("did:0123456789abcdef0123456789abcdeg"))) // non-hex
    }

    /** A pv2-only tail must not be mistaken for a device id, and vice versa. */
    @Test
    fun pv2AndDidTokensDoNotShadowEachOther() {
        val tail = "PAIR_REQ|My PC|cGs=|pv2|did:$id".split("|").drop(3)
        assertEquals(true, tail.contains("pv2"))
        assertEquals(id, WireFrames.parseDeviceId(tail))
    }
}
