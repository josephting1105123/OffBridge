package com.EntropicSoftwareLab.ClipboardShare.transport

import com.EntropicSoftwareLab.ClipboardShare.DiscoveryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Discoverable to other devices" = OFF must hide this device from strangers WITHOUT hiding the LAN
 * from this device, and without cutting off peers we already paired with. The Windows daemon
 * implements the same decision (DiscoveryPolicyTest there), so this matrix is the shared contract —
 * a change here is a wire behaviour change on both platforms and must land on both.
 */
class DiscoveryPolicyTest {

    private val ourDid = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
    private val strangerDid = "ffffffffffffffffffffffffffffffff"

    /** One paired peer, its record filed under the address it had when it paired. */
    private val pairedIp = "192.168.0.10"
    private val trusted = setOf(pairedIp)
    private val pairings: (String) -> String? = { ip -> if (ip == pairedIp) ourDid else null }

    private fun hidden(probeDeviceId: String?, senderIp: String) =
        DiscoveryPolicy.shouldReplyToProbe(
            discoverable = false,
            probeDeviceId = probeDeviceId,
            senderIp = senderIp,
            trustedIps = trusted,
            pairedDeviceIdFor = pairings
        )

    @Test
    fun discoverableAlwaysReplies() {
        // ON keeps today's behaviour byte-for-byte: every probe is answered, paired or not.
        assertTrue(
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = true,
                probeDeviceId = strangerDid,
                senderIp = "192.168.0.99",
                trustedIps = trusted,
                pairedDeviceIdFor = pairings
            )
        )
        assertTrue(
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = true,
                probeDeviceId = null,
                senderIp = "192.168.0.99",
                trustedIps = trusted,
                pairedDeviceIdFor = pairings
            )
        )
    }

    @Test
    fun hiddenRepliesToAPairedPeerMatchedByDeviceId() {
        assertTrue(hidden(ourDid, senderIp = pairedIp))
    }

    @Test
    fun hiddenRepliesToAPairedPeerThatMovedToANewAddress() {
        // The case the `did:` token exists for: the peer's record is still filed under 192.168.0.10
        // but its DHCP lease moved it to .77. Matching only the record under the source address
        // would leave two paired devices unable to find each other after a router reboot — and the
        // Windows daemon answers here, so Android answering too is what keeps the pair symmetric.
        assertTrue(hidden(ourDid, senderIp = "192.168.0.77"))
    }

    @Test
    fun hiddenRepliesOnTheAddressFallbackWhenTheProbeCarriesNoDeviceId() {
        // Peers older than the `did:` token: the source address is all we have to go on.
        assertTrue(hidden(null, senderIp = pairedIp))
        assertFalse(hidden(null, senderIp = "192.168.0.99"))
    }

    @Test
    fun hiddenIgnoresAnUnpairedProbe() {
        assertFalse(
            "a stranger's probe must get no reply while hidden",
            hidden(strangerDid, senderIp = "192.168.0.99")
        )
    }

    @Test
    fun hiddenIgnoresAForeignDeviceIdEvenFromAPairedAddress() {
        // The address is trusted but the device behind it is not the one we paired with — a recycled
        // DHCP lease. The id wins over the address, and does NOT fall through to it.
        assertFalse(hidden(strangerDid, senderIp = pairedIp))
    }

    @Test
    fun aBlankDeviceIdFallsBackToTheAddress() {
        // A frame ending in a bare "did:" parses to an empty id. Treat it as "no id given" rather
        // than letting "" match a pairing record that has no id stored.
        assertTrue(hidden("", senderIp = pairedIp))
        assertFalse(hidden("", senderIp = "192.168.0.99"))
    }

    @Test
    fun hiddenIgnoresADeviceIdWhenEveryPairingPredatesDeviceIds() {
        // Records written before ids existed store null. A did-bearing probe from such a peer gets
        // no reply and the user re-pairs once; matching null to anything would answer strangers.
        // Windows behaves identically.
        assertFalse(
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = false,
                probeDeviceId = ourDid,
                senderIp = pairedIp,
                trustedIps = trusted,
                pairedDeviceIdFor = { null }
            )
        )
    }

    @Test
    fun hiddenWithNoPairingsAnswersNobody() {
        assertFalse(
            DiscoveryPolicy.shouldReplyToProbe(
                discoverable = false,
                probeDeviceId = ourDid,
                senderIp = pairedIp,
                trustedIps = emptySet(),
                pairedDeviceIdFor = { null }
            )
        )
    }
}
