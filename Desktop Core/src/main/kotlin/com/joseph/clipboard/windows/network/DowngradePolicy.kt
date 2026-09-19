package com.joseph.clipboard.windows.network

/**
 * Pure decision for anti-downgrade (TOFU) negotiation, shared by the file-stream (FileSeal/FGCM1)
 * and clipboard (AES-GCM/GCM1) paths. Byte-for-byte mirror of the Android client's DowngradePolicy.
 *
 * Capability advertisement rides the UNAUTHENTICATED UDP discovery, so an on-path attacker can strip
 * a peer's capability token and try to force the weaker path. The rule:
 *   - peer offers the authenticated capability now  → [Decision.SEAL] (caller also pins the peer)
 *   - peer does NOT offer it but we previously pinned → [Decision.BLOCK] (a downgrade attempt — fail closed)
 *   - peer never supported it (not pinned)           → [Decision.PLAINTEXT] (legitimate legacy peer)
 */
object DowngradePolicy {
    enum class Decision { SEAL, PLAINTEXT, BLOCK }

    /**
     * @param peerOffersAead whether the peer currently advertises the authenticated capability
     *   (receiver side), or whether the peer requested the authenticated stream (sender side, "fgcm").
     * @param pinned whether we have previously negotiated the authenticated channel with this peer.
     */
    fun decide(peerOffersAead: Boolean, pinned: Boolean): Decision = when {
        peerOffersAead -> Decision.SEAL
        pinned -> Decision.BLOCK
        else -> Decision.PLAINTEXT
    }
}
