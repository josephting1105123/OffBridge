package com.EntropicSoftwareLab.ClipboardShare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Interop contract for the LAN pairing frames. Every golden string here is duplicated verbatim in
 * the Windows module's `WireFramesTest` — if the two drift, the platforms misparse each other's
 * handshake. Do not update an expectation on one side alone.
 */
class PairingWireTest {

    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun pairReqKeepsPositionalFieldsAndAppendsDeviceId() {
        assertEquals("PAIR_REQ|My PC|cGs=|pv2|did:$id", PairingWire.pairReq("My PC", "cGs=", id))
    }

    @Test
    fun pairAckKeepsPositionalFieldsAndAppendsDeviceId() {
        assertEquals("PAIR_ACK|cGs=|pv2|did:$id", PairingWire.pairAck("cGs=", id))
    }

    /**
     * The positional prefix an older peer reads must be untouched by the new token: name at [1] and
     * public key at [2] of a PAIR_REQ, public key at [1] of a PAIR_ACK.
     */
    @Test
    fun legacyPositionalParsingStillWorksOnNewFrames() {
        val req = PairingWire.pairReq("My PC", "cGs=", id).split("|")
        assertEquals("PAIR_REQ", req[0])
        assertEquals("My PC", req[1])
        assertEquals("cGs=", req[2])
        assertEquals(true, req.drop(3).contains("pv2"))

        val ack = PairingWire.pairAck("cGs=", id).split("|")
        assertEquals("PAIR_ACK", ack[0])
        assertEquals("cGs=", ack[1])
        assertEquals(true, ack.drop(2).contains("pv2"))
    }

    /** An older peer's frame has no did token — parsing must yield null, not a crash or a stray value. */
    @Test
    fun parseDeviceIdReturnsNullForLegacyFrames() {
        assertNull(PairingWire.parseDeviceId("PAIR_ACK|cGs=|pv2".split("|").drop(2)))
        assertNull(PairingWire.parseDeviceId(emptyList()))
    }

    @Test
    fun parseDeviceIdReadsTheTokenRegardlessOfOrder() {
        assertEquals(id, PairingWire.parseDeviceId(listOf("pv2", "did:$id")))
        assertEquals(id, PairingWire.parseDeviceId(listOf("did:$id", "pv2")))
    }

    /** The value is persisted and compared, so anything off-format is dropped rather than stored. */
    @Test
    fun parseDeviceIdRejectsMalformedValues() {
        assertNull(PairingWire.parseDeviceId(listOf("did:")))
        assertNull(PairingWire.parseDeviceId(listOf("did:abc")))
        assertNull(PairingWire.parseDeviceId(listOf("did:${id}00")))
        assertNull(PairingWire.parseDeviceId(listOf("did:${id.uppercase()}")))
        assertNull(PairingWire.parseDeviceId(listOf("did:0123456789abcdef0123456789abcdeg")))
    }
}
