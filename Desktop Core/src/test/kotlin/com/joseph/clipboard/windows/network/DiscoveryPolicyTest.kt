package com.joseph.clipboard.windows.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins who gets answered while "Discoverable to other devices" is OFF. The wire format is unchanged
 * (`FILEBRIDGE_DISCOVERY|name|ip|os|caps|did:<id>`), so these cases double as a contract check that a
 * peer on an older build still parses and is still parsed.
 */
class DiscoveryPolicyTest {

    private val pairedId = "a".repeat(32)
    private val strangerId = "b".repeat(32)
    private val caps = "GCM1,FGCM1,FGCM2,AUTH2,AUTH2R"

    private fun probe(deviceId: String? = null, ip: String = "192.168.0.10"): String {
        val head = "FILEBRIDGE_DISCOVERY|Pixel|$ip|Android|$caps"
        return if (deviceId == null) head else "$head|${WireFrames.DEVICE_ID_TOKEN}$deviceId"
    }

    /** ip -> stored peer device id, i.e. one paired peer at 192.168.0.10. */
    private val trustedIps = setOf("192.168.0.10")
    private val idFor: (String) -> String? = { ip -> if (ip == "192.168.0.10") pairedId else null }

    private fun hidden(probe: String, senderIp: String) =
        DiscoveryPolicy.shouldReplyToProbe(false, probe, senderIp, trustedIps, idFor)

    @Test
    fun hiddenDeviceAnswersAPairedPeerMatchedByDeviceId() {
        // The peer moved to a new DHCP address; the did still identifies it as ours.
        assertTrue(hidden(probe(pairedId, ip = "192.168.0.77"), senderIp = "192.168.0.77"))
    }

    @Test
    fun hiddenDeviceAnswersALegacyPairedPeerBySourceIpWhenTheProbeCarriesNoDeviceId() {
        assertTrue(hidden(probe(deviceId = null), senderIp = "192.168.0.10"))
    }

    @Test
    fun hiddenDeviceIgnoresAnUnpairedProbe() {
        assertFalse(hidden(probe(strangerId), senderIp = "192.168.0.99"))
        assertFalse(hidden(probe(deviceId = null), senderIp = "192.168.0.99"))
    }

    /**
     * A did that names a device we are not paired with is a decision, not a gap: it must NOT fall
     * through to the source-IP check, or a stranger squatting a paired peer's freed DHCP lease would
     * be answered despite telling us it is a different device.
     */
    @Test
    fun hiddenDeviceIgnoresAForeignDeviceIdEvenFromAPairedAddress() {
        assertFalse(hidden(probe(strangerId), senderIp = "192.168.0.10"))
    }

    @Test
    fun malformedProbesAreNeverAnswered() {
        assertFalse(hidden("", senderIp = "192.168.0.10"))
        assertFalse(hidden("FILEBRIDGE_DISCOVERY", senderIp = "192.168.0.10"))
        assertFalse(hidden("FILEBRIDGE_DISCOVERY|Pixel|192.168.0.10", senderIp = "192.168.0.10"))
        assertFalse(hidden("GARBAGE|Pixel|192.168.0.10|Android", senderIp = "192.168.0.10"))
        // A REPLY is an answer to our own probe, never something to answer again.
        assertFalse(hidden("FILEBRIDGE_REPLY|Pixel|192.168.0.10|Android|$caps", senderIp = "192.168.0.10"))
    }

    /**
     * A `did:` whose value fails WireFrames validation (not 32 lowercase hex) counts as absent, so
     * the source-IP fallback still applies — the same host could have omitted the token entirely.
     */
    @Test
    fun anUnparseableDeviceIdFallsBackToTheSourceIp() {
        assertTrue(hidden(probe("not-a-valid-id"), senderIp = "192.168.0.10"))
        assertFalse(hidden(probe("not-a-valid-id"), senderIp = "192.168.0.99"))
    }

    /** Discoverable ON must stay exactly as it was: every well-formed probe is answered. */
    @Test
    fun discoverableDeviceAnswersEveryWellFormedProbe() {
        val discoverable = { p: String, ip: String ->
            DiscoveryPolicy.shouldReplyToProbe(true, p, ip, emptySet()) { null }
        }
        assertTrue(discoverable(probe(strangerId), "192.168.0.99"))
        assertTrue(discoverable(probe(deviceId = null), "192.168.0.99"))
        assertFalse(discoverable("FILEBRIDGE_REPLY|Pixel|192.168.0.10|Android|$caps", "192.168.0.10"))
        assertFalse(discoverable("FILEBRIDGE_DISCOVERY|Pixel", "192.168.0.10"))
    }
}
