package com.EntropicSoftwareLab.ClipboardShare.transport

import com.EntropicSoftwareLab.ClipboardShare.DiscoveryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Discoverable to other devices" = OFF must hide this device from strangers WITHOUT hiding the LAN
 * from this device, and without cutting off peers we already paired with. The Windows daemon
 * implements the same decision, so this matrix is the shared contract — a change here is a wire
 * behaviour change on both platforms.
 */
class DiscoveryPolicyTest {

    private val ourDid = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
    private val strangerDid = "ffffffffffffffffffffffffffffffff"

    @Test
    fun discoverableAlwaysReplies() {
        // ON keeps today's behaviour byte-for-byte: every probe is answered, paired or not.
        assertTrue(
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = true,
                probeDeviceId = strangerDid,
                pairedDeviceIdForSenderIp = null,
                senderIsTrusted = false
            )
        )
        assertTrue(
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = true,
                probeDeviceId = null,
                pairedDeviceIdForSenderIp = null,
                senderIsTrusted = false
            )
        )
    }

    @Test
    fun hiddenRepliesToAPairedPeerMatchedByDeviceId() {
        assertTrue(
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = false,
                probeDeviceId = ourDid,
                pairedDeviceIdForSenderIp = ourDid,
                senderIsTrusted = true
            )
        )
    }

    @Test
    fun hiddenRepliesOnTheIpFallbackWhenTheProbeCarriesNoDeviceId() {
        // Peers older than the `did:` token: the source address is all we have to go on.
        assertTrue(
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = false,
                probeDeviceId = null,
                pairedDeviceIdForSenderIp = null,
                senderIsTrusted = true
            )
        )
    }

    @Test
    fun hiddenIgnoresAnUnpairedProbe() {
        assertFalse(
            "a stranger's probe must get no reply while hidden",
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = false,
                probeDeviceId = strangerDid,
                pairedDeviceIdForSenderIp = null,
                senderIsTrusted = false
            )
        )
        assertFalse(
            "no did and an untrusted address is still a stranger",
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = false,
                probeDeviceId = null,
                pairedDeviceIdForSenderIp = null,
                senderIsTrusted = false
            )
        )
    }

    @Test
    fun hiddenIgnoresADeviceIdThatDoesNotMatchThePairingForThatAddress() {
        // The address is trusted but the device behind it is not the one we paired with — a
        // recycled DHCP lease. The id wins over the address.
        assertFalse(
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = false,
                probeDeviceId = strangerDid,
                pairedDeviceIdForSenderIp = ourDid,
                senderIsTrusted = true
            )
        )
    }

    @Test
    fun malformedProbeWithBlankDeviceIdFallsBackToTheAddress() {
        // A frame ending in a bare "did:" parses to an empty id. Treat it as "no id given" rather
        // than letting "" match a missing pairing record.
        assertTrue(
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = false,
                probeDeviceId = "",
                pairedDeviceIdForSenderIp = null,
                senderIsTrusted = true
            )
        )
        assertFalse(
            "a blank id must never match a device we have no pairing record for",
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = false,
                probeDeviceId = "",
                pairedDeviceIdForSenderIp = "",
                senderIsTrusted = false
            )
        )
    }
}
