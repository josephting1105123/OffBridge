package com.EntropicSoftwareLab.ClipboardShare.transport

import com.EntropicSoftwareLab.ClipboardShare.Net
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-link routing maths behind the LAN-probe gate. The trace this came from: a phone on mobile data
 * with Wi-Fi off spent 2.5 s connecting to the sender's `192.168.68.51` before falling through to
 * the ladder, because "probe over the default network" was never checked against a real route.
 */
class Ipv4SubnetTest {

    @Test
    fun matchesInsideTheSameSlash24() {
        assertTrue(Net.sameIpv4Subnet("192.168.68.7", 24, "192.168.68.51"))
        assertTrue(Net.sameIpv4Subnet("192.168.68.1", 24, "192.168.68.1"))
    }

    @Test
    fun rejectsANeighbouringSlash24() {
        assertFalse(Net.sameIpv4Subnet("192.168.1.7", 24, "192.168.68.51"))
        assertFalse(Net.sameIpv4Subnet("10.0.0.5", 24, "192.168.68.51"))
    }

    @Test
    fun honoursNonByteAlignedPrefixes() {
        // 10.0.0.0/22 spans 10.0.0.0 – 10.0.3.255.
        assertTrue(Net.sameIpv4Subnet("10.0.0.5", 22, "10.0.3.200"))
        assertFalse(Net.sameIpv4Subnet("10.0.0.5", 22, "10.0.4.1"))
        // /32 is a host route: only the address itself.
        assertTrue(Net.sameIpv4Subnet("172.16.4.9", 32, "172.16.4.9"))
        assertFalse(Net.sameIpv4Subnet("172.16.4.9", 32, "172.16.4.10"))
    }

    @Test
    fun rejectsOutOfRangePrefixes() {
        // A /0 would claim every address on earth is on-link; it is never a real interface prefix.
        assertFalse(Net.sameIpv4Subnet("192.168.1.1", 0, "8.8.8.8"))
        assertFalse(Net.sameIpv4Subnet("192.168.1.1", 33, "192.168.1.2"))
        assertFalse(Net.sameIpv4Subnet("192.168.1.1", -1, "192.168.1.2"))
    }

    @Test
    fun rejectsMalformedAddresses() {
        assertFalse(Net.sameIpv4Subnet("192.168.1", 24, "192.168.1.5"))
        assertFalse(Net.sameIpv4Subnet("192.168.1.256", 24, "192.168.1.5"))
        assertFalse(Net.sameIpv4Subnet("192.168.1.1", 24, "not-an-ip"))
        assertFalse(Net.sameIpv4Subnet("192.168.1.1", 24, "fe80::1"))
        assertFalse(Net.sameIpv4Subnet("192.168.1.1", 24, ""))
        assertFalse(Net.sameIpv4Subnet("192.168..1", 24, "192.168.0.1"))
    }

    @Test
    fun isIpv4LiteralAcceptsOnlyDottedQuads() {
        assertTrue(Net.isIpv4Literal("0.0.0.0"))
        assertTrue(Net.isIpv4Literal("255.255.255.255"))
        assertFalse(Net.isIpv4Literal("laptop.local"))
        assertFalse(Net.isIpv4Literal("192.168.1"))
        assertFalse(Net.isIpv4Literal("1.2.3.4.5"))
        assertFalse(Net.isIpv4Literal("1.2.3.-4"))
    }

    @Test
    fun mobileDataOnlyDeviceHasNoRouteToTheSendersLanIp() {
        // Cellular addresses are excluded from localIpv4Cidrs, so the list a mobile-data-only phone
        // produces looks like this — nothing on the sender's subnet.
        val onlyWifiDirect = listOf(Net.Ipv4Cidr("192.168.49.1", 24))
        assertFalse(Net.hasOnLinkRoute(onlyWifiDirect, "192.168.68.51"))
    }

    @Test
    fun anyMatchingInterfaceIsEnough() {
        val cidrs = listOf(
            Net.Ipv4Cidr("192.168.49.1", 24),   // Wi-Fi Direct GO
            Net.Ipv4Cidr("192.168.68.14", 24)   // station on the sender's LAN
        )
        assertTrue(Net.hasOnLinkRoute(cidrs, "192.168.68.51"))
    }

    @Test
    fun failsOpenWhenInterfaceEnumerationYieldedNothing() {
        // An empty list means we learned nothing, not that we are certainly off-subnet. Refusing a
        // reachable peer on missing evidence costs more than one wasted probe.
        assertTrue(Net.hasOnLinkRoute(emptyList(), "192.168.68.51"))
    }

    @Test
    fun cidrsCarryBothHalvesOfTheAddress() {
        val c = Net.Ipv4Cidr("192.168.68.14", 24)
        assertEquals("192.168.68.14", c.ip)
        assertEquals(24, c.prefixLength)
    }
}
