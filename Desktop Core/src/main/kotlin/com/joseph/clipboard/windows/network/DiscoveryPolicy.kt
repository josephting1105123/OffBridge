package com.joseph.clipboard.windows.network

/**
 * Decides whether an inbound `FILEBRIDGE_DISCOVERY` probe earns a unicast `FILEBRIDGE_REPLY`.
 *
 * "Discoverable to other devices" used to mean two things at once: don't announce ourselves, AND
 * drop every inbound probe — which also blinded this device to its own paired peers. The two are now
 * separated: inbound DISCOVERY/REPLY frames are always processed (so the peer list still fills), and
 * this policy decides who gets answered. Discoverable ON answers everyone, exactly as before;
 * discoverable OFF answers only a peer we are already paired with, so a hidden device stays invisible
 * to strangers while two paired devices can still find each other.
 *
 * Kept free of SecurityVault/socket state so it is unit-testable on the host JVM
 * ([DiscoveryPolicyTest]); the caller supplies the trust store as plain data.
 */
object DiscoveryPolicy {

    /** Frame token this policy answers. A REPLY is never answered (that would ping-pong forever). */
    private const val DISCOVERY_PREFIX = "FILEBRIDGE_DISCOVERY"

    /** Positional fields a discovery frame must carry (`token|name|ip|os`) before the optional tail. */
    private const val MIN_POSITIONAL_FIELDS = 4

    /**
     * @param discoverable this device's "Discoverable to other devices" setting.
     * @param probe the raw UTF-8 payload of the inbound datagram.
     * @param senderIp the datagram's source address (never the payload-declared IP — a peer must not
     *   be able to claim a paired address it isn't calling from).
     * @param trustedIps the IPs this device holds a pairing key for.
     * @param pairedDeviceIdFor the stored peer device id for a trusted IP, or null for records
     *   written before device ids existed.
     *
     * @return true when [probe] should be answered with a unicast REPLY.
     */
    fun shouldReplyToProbe(
        discoverable: Boolean,
        probe: String,
        senderIp: String,
        trustedIps: Set<String>,
        pairedDeviceIdFor: (String) -> String?,
    ): Boolean {
        val parts = probe.split("|")
        // A malformed or non-DISCOVERY frame is never answered, in either discoverable state — this
        // mirrors the caller's own `parts.size >= 4 && isDiscovery` guard so the two can't drift.
        if (parts.getOrNull(0) != DISCOVERY_PREFIX) return false
        if (parts.size < MIN_POSITIONAL_FIELDS) return false

        // Discoverable: answer every probe, byte-for-byte the pre-existing behaviour.
        if (discoverable) return true

        // Hidden: only a paired peer gets an answer. Prefer the stable device id — it survives the
        // peer changing DHCP address, which an IP match cannot.
        val probeDeviceId = WireFrames.parseDeviceId(parts.drop(MIN_POSITIONAL_FIELDS))
        if (probeDeviceId != null) {
            // A did that matches no pairing does NOT fall through to the IP check: the peer told us
            // which device it is, and it isn't one of ours.
            return trustedIps.any { pairedDeviceIdFor(it) == probeDeviceId }
        }
        // No usable did (peer predates the token, or sent one that failed validation): fall back to
        // the source address. No weaker than the did path — the same host could simply omit the token.
        return senderIp in trustedIps
    }
}
