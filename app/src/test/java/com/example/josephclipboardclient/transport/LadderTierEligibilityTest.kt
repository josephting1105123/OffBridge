package com.EntropicSoftwareLab.ClipboardShare.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local ladder must not run tiers whose inputs it does not hold.
 *
 * From the trace: after `Wi-Fi-only degrade` the ladder announced `Wi-Fi tier 1/1: P2P_CRED_JOIN`,
 * failed with CONNECT_TIMEOUT in about a second, and reported `all Wi-Fi tiers exhausted`. The
 * sender was LAN-only, so the SSID and PSK being joined were both the empty string.
 */
class LadderTierEligibilityTest {

    private val all = listOf(
        TransportDescriptor.P2P_CRED_JOIN,
        TransportDescriptor.SPECIFIER,
        TransportDescriptor.P2P_NEGOTIATED,
        TransportDescriptor.LOHS,
        TransportDescriptor.SOFTAP_JOIN,
        TransportDescriptor.RECEIVER_AS_GO,
        TransportDescriptor.SENDER_JOINS_AP,
        TransportDescriptor.BT_L2CAP,
        TransportDescriptor.AWARE
    )

    @Test
    fun lanOnlySenderLeavesNothingRunnable() {
        val eligible = TransportLadder.eligibleLocalTiers(
            candidates = listOf(TransportDescriptor.P2P_CRED_JOIN),
            hasApCredentials = false,
            hasGroupOwnerMac = false,
            controlDegraded = true
        )
        assertTrue("joining a group with an empty SSID can never succeed", eligible.isEmpty())
    }

    @Test
    fun credentialBearingTiersSurviveAControlDegrade() {
        // P2P_CRED_JOIN takes its SSID/PSK from the QR payload, so a dead control channel does not
        // disqualify it — only missing credentials do.
        val eligible = TransportLadder.eligibleLocalTiers(
            candidates = listOf(TransportDescriptor.P2P_CRED_JOIN, TransportDescriptor.SOFTAP_JOIN),
            hasApCredentials = true,
            hasGroupOwnerMac = false,
            controlDegraded = true
        )
        assertEquals(
            listOf(TransportDescriptor.P2P_CRED_JOIN, TransportDescriptor.SOFTAP_JOIN),
            eligible
        )
    }

    @Test
    fun controlDependentTiersAreDroppedOnceControlDegrades() {
        val degraded = TransportLadder.eligibleLocalTiers(
            candidates = listOf(TransportDescriptor.RECEIVER_AS_GO, TransportDescriptor.SENDER_JOINS_AP),
            hasApCredentials = true,
            hasGroupOwnerMac = true,
            controlDegraded = true
        )
        assertTrue("HOST_READY needs the control channel that just died", degraded.isEmpty())

        val healthy = TransportLadder.eligibleLocalTiers(
            candidates = listOf(TransportDescriptor.RECEIVER_AS_GO, TransportDescriptor.SENDER_JOINS_AP),
            hasApCredentials = true,
            hasGroupOwnerMac = true,
            controlDegraded = false
        )
        assertEquals(2, healthy.size)
    }

    @Test
    fun negotiatedTierNeedsTheGroupOwnerMacNotCredentials() {
        assertTrue(
            TransportLadder.eligibleLocalTiers(
                listOf(TransportDescriptor.P2P_NEGOTIATED),
                hasApCredentials = true, hasGroupOwnerMac = false, controlDegraded = false
            ).isEmpty()
        )
        assertEquals(
            listOf(TransportDescriptor.P2P_NEGOTIATED),
            TransportLadder.eligibleLocalTiers(
                listOf(TransportDescriptor.P2P_NEGOTIATED),
                hasApCredentials = false, hasGroupOwnerMac = true, controlDegraded = false
            )
        )
    }

    @Test
    fun retiredDataPathsAreNeverEligible() {
        val eligible = TransportLadder.eligibleLocalTiers(
            candidates = all,
            hasApCredentials = true,
            hasGroupOwnerMac = true,
            controlDegraded = false
        )
        assertFalse(eligible.contains(TransportDescriptor.BT_L2CAP))
        assertFalse(eligible.contains(TransportDescriptor.AWARE))
    }

    @Test
    fun filteringPreservesTheIncomingOrder() {
        // The per-peer learner has already sorted the candidates; the filter must not reshuffle them.
        val ordered = listOf(
            TransportDescriptor.SOFTAP_JOIN,
            TransportDescriptor.P2P_NEGOTIATED,
            TransportDescriptor.P2P_CRED_JOIN
        )
        assertEquals(
            ordered,
            TransportLadder.eligibleLocalTiers(
                ordered, hasApCredentials = true, hasGroupOwnerMac = true, controlDegraded = false
            )
        )
    }

    @Test
    fun everyTierIsClassified() {
        // The filter's `when` is exhaustive over the enum; this fails loudly if a new tier is added
        // without deciding what it depends on.
        val eligible = TransportLadder.eligibleLocalTiers(
            TransportDescriptor.values().toList(),
            hasApCredentials = true, hasGroupOwnerMac = true, controlDegraded = false
        )
        assertEquals(TransportDescriptor.values().size - 2, eligible.size) // minus BT_L2CAP + AWARE
    }
}
