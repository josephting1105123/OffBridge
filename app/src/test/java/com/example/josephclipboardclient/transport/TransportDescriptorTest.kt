package com.EntropicSoftwareLab.ClipboardShare.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stable-id codec coverage for the transport enums. These ids are persisted in the Room
 * `transport_attempt` table and carried in TRY_TRANSPORT / TRANSPORT_RESULT control frames,
 * so a renumber would silently corrupt history and break wire compatibility. These tests lock
 * the id <-> enum mapping and the documented unknown-id fallbacks.
 */
class TransportDescriptorTest {

    @Test
    fun everyDescriptorRoundTripsById() {
        for (d in TransportDescriptor.values()) {
            assertEquals(d, TransportDescriptor.fromId(d.id))
        }
    }

    @Test
    fun descriptorIdsAreUniqueAndPinned() {
        val ids = TransportDescriptor.values().map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        // Pin the documented wire ids — these must never be renumbered.
        assertEquals(0, TransportDescriptor.P2P_CRED_JOIN.id)
        assertEquals(1, TransportDescriptor.SPECIFIER.id)
        assertEquals(2, TransportDescriptor.P2P_NEGOTIATED.id)
        assertEquals(3, TransportDescriptor.LOHS.id)
        assertEquals(4, TransportDescriptor.BT_L2CAP.id)
        assertEquals(5, TransportDescriptor.AWARE.id)
        assertEquals(6, TransportDescriptor.RECEIVER_AS_GO.id)
        assertEquals(7, TransportDescriptor.SOFTAP_JOIN.id)
        assertEquals(8, TransportDescriptor.SENDER_JOINS_AP.id)
    }

    @Test
    fun unknownDescriptorIdIsNull() {
        assertNull(TransportDescriptor.fromId(99))
        assertNull(TransportDescriptor.fromId(-1))
    }

    @Test
    fun netKindRoundTripsAndFallsBackToP2pGroup() {
        for (k in NetKind.values()) assertEquals(k, NetKind.fromId(k.id))
        assertEquals(NetKind.P2P_GROUP, NetKind.fromId(999))
        assertEquals(NetKind.P2P_GROUP, NetKind.fromId(-1))
    }

    @Test
    fun failureCodeRoundTripsAndFallsBackToNone() {
        for (c in FailureCode.values()) assertEquals(c, FailureCode.fromId(c.id))
        assertEquals(FailureCode.NONE, FailureCode.fromId(999))
        assertEquals(FailureCode.NONE, FailureCode.fromId(-7))
    }
}
