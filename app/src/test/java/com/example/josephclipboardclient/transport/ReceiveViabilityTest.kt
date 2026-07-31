package com.EntropicSoftwareLab.ClipboardShare.transport

import com.EntropicSoftwareLab.ClipboardShare.transport.ReceiveViability.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receive screen's blocking decision. Named cases mirror the field reports that motivated moving
 * it after the decode: a hotspot being on is a reason a transfer WORKS at least as often as it is a
 * reason it can't.
 */
class ReceiveViabilityTest {

    /** Wi-Fi Direct sender (QR carries group creds), nothing else going on. */
    private fun routes(
        lanReachable: Boolean = false,
        senderIsSoftApBridge: Boolean = false,
        senderHasApCredentials: Boolean = true,
        onOtherWifi: Boolean = false,
        hotspotOn: Boolean = false,
        haveSavedHotspotCreds: Boolean = false,
        controlChannelPossible: Boolean = true
    ) = ReceiveViability.viableRoutes(
        lanReachable, senderIsSoftApBridge, senderHasApCredentials,
        onOtherWifi, hotspotOn, haveSavedHotspotCreds, controlChannelPossible
    )

    @Test
    fun hotspotOnAndBothPhonesOnTheSameWifiRunsOverLan() {
        // The exact case the old pre-scan check hard-blocked: hotspot up, station associated to
        // another network — and the sender sitting on that very network.
        val r = routes(lanReachable = true, hotspotOn = true, onOtherWifi = true)
        assertTrue("LAN must survive", r.contains(Route.LAN))
        assertFalse(ReceiveViability.isHardBlocked(true, false, true, true, true, false, true))
    }

    @Test
    fun hotspotOnAndSenderJoinsItIsViable() {
        val r = routes(
            senderHasApCredentials = false,   // LAN-only sender, no group creds
            hotspotOn = true,
            haveSavedHotspotCreds = true,
            controlChannelPossible = true
        )
        assertTrue(r.contains(Route.SENDER_JOINS_MY_HOTSPOT))
    }

    @Test
    fun senderJoiningOurHotspotNeedsSavedCredentialsAndAControlChannel() {
        // Android exposes no API to read a manual hotspot's password, and the creds only reach the
        // sender in a HOST_READY control frame — either missing and the route is not real.
        assertFalse(
            routes(senderHasApCredentials = false, hotspotOn = true, haveSavedHotspotCreds = false)
                .contains(Route.SENDER_JOINS_MY_HOTSPOT)
        )
        assertFalse(
            routes(
                senderHasApCredentials = false, hotspotOn = true,
                haveSavedHotspotCreds = true, controlChannelPossible = false
            ).contains(Route.SENDER_JOINS_MY_HOTSPOT)
        )
    }

    @Test
    fun blocksOnlyWhenNothingAtAllCanRun() {
        // LAN-only sender we have no route to, our station is held by another network, no hotspot.
        assertTrue(
            ReceiveViability.isHardBlocked(
                lanReachable = false,
                senderIsSoftApBridge = false,
                senderHasApCredentials = false,
                onOtherWifi = true,
                hotspotOn = false,
                haveSavedHotspotCreds = false,
                controlChannelPossible = true
            )
        )
    }

    @Test
    fun joiningTheSendersHotspotNeedsAFreeStation() {
        val free = routes(senderIsSoftApBridge = true, onOtherWifi = false)
        assertTrue(free.contains(Route.JOIN_SENDER_AP))
        val busy = routes(senderIsSoftApBridge = true, onOtherWifi = true)
        assertFalse("a station held by another network can't retune for a specifier join",
            busy.contains(Route.JOIN_SENDER_AP))
    }

    @Test
    fun softApBridgeCredentialsAreNotAWifiDirectGroup() {
        val r = routes(senderIsSoftApBridge = true)
        assertFalse("the sender's hotspot is not a P2P group to join", r.contains(Route.WIFI_DIRECT))
        assertEquals(setOf(Route.JOIN_SENDER_AP), r)
    }

    @Test
    fun payloadCredentialsAloneKeepWifiDirectAliveWithoutAControlChannel() {
        val r = routes(controlChannelPossible = false)
        assertTrue("P2P_CRED_JOIN runs off the QR creds", r.contains(Route.WIFI_DIRECT))
    }

    @Test
    fun aWifiDirectSenderIsNeverBlockedRegardlessOfOurRadios() {
        // Group creds in the payload mean there is always something to try.
        for (hotspot in listOf(false, true)) for (other in listOf(false, true)) {
            assertFalse(
                "hotspot=$hotspot otherWifi=$other must not block a Wi-Fi Direct sender",
                ReceiveViability.isHardBlocked(
                    lanReachable = false,
                    senderIsSoftApBridge = false,
                    senderHasApCredentials = true,
                    onOtherWifi = other,
                    hotspotOn = hotspot,
                    haveSavedHotspotCreds = false,
                    controlChannelPossible = true
                )
            )
        }
    }
}
