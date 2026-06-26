package com.EntropicSoftwareLab.ClipboardShare.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for the hotspot-bridge decision inputs ([DeviceCaps.canJoinAp] / [canHostAp]).
 * These two derived flags are what [SenderSessionController.buildPlan] keys off, so they are the
 * heart of the "hotspot is on" logic — host = hotspot + saved creds; join = free STA, which a
 * hotspot host still has iff its chip supports STA+AP concurrency.
 */
class DeviceCapsTest {

    private fun caps(hotspot: Boolean, concurrency: Boolean, creds: Boolean) =
        DeviceCaps(hotspotActive = hotspot, staApConcurrency = concurrency, hasCreds = creds, staConnected = false, hotspotIp = null, lanIp = null)

    @Test
    fun `no hotspot can always join and never hosts`() {
        assertTrue(caps(hotspot = false, concurrency = false, creds = false).canJoinAp)
        assertTrue(caps(hotspot = false, concurrency = false, creds = true).canJoinAp)
        assertFalse(caps(hotspot = false, concurrency = false, creds = true).canHostAp)
    }

    @Test
    fun `hotspot with concurrency keeps a free STA so it can still join`() {
        assertTrue(caps(hotspot = true, concurrency = true, creds = false).canJoinAp)
    }

    @Test
    fun `hotspot without concurrency cannot join another AP`() {
        assertFalse(caps(hotspot = true, concurrency = false, creds = true).canJoinAp)
    }

    @Test
    fun `hosting requires both a live hotspot and saved creds`() {
        assertTrue(caps(hotspot = true, concurrency = false, creds = true).canHostAp)
        assertFalse(caps(hotspot = true, concurrency = false, creds = false).canHostAp)
        assertFalse(caps(hotspot = false, concurrency = true, creds = true).canHostAp)
    }

    @Test
    fun `legacy default is conservative - can join, cannot host`() {
        val d = DeviceCaps.legacyDefault()
        assertTrue(d.canJoinAp)
        assertFalse(d.canHostAp)
    }

    @Test
    fun `fromPeer reconstructs the derived flags`() {
        val bothHotspotsConcurrency = DeviceCaps.fromPeer(hotspotActive = true, staApConcurrency = true, hasCreds = true, staConnected = false)
        assertTrue(bothHotspotsConcurrency.canJoinAp)
        assertTrue(bothHotspotsConcurrency.canHostAp)

        val hotspotNoConcurrencyNoCreds = DeviceCaps.fromPeer(hotspotActive = true, staApConcurrency = false, hasCreds = false, staConnected = false)
        assertFalse(hotspotNoConcurrencyNoCreds.canJoinAp)
        assertFalse(hotspotNoConcurrencyNoCreds.canHostAp)
    }

    @Test
    fun `NetKind round-trips by id and defaults to P2P_GROUP`() {
        assertEquals(NetKind.SOFTAP, NetKind.fromId(NetKind.SOFTAP.id))
        assertEquals(NetKind.P2P_GROUP, NetKind.fromId(NetKind.P2P_GROUP.id))
        assertEquals(NetKind.P2P_GROUP, NetKind.fromId(999))
    }
}
