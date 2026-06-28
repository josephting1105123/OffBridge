package com.EntropicSoftwareLab.ClipboardShare.transport

/**
 * Pure decision for anti-downgrade (TOFU) negotiation, shared by the file-stream (FileSeal/FGCM1)
 * and clipboard (AES-GCM/GCM1) paths and mirrored on the Windows client.
 *
 * Capability advertisement rides the UNAUTHENTICATED UDP discovery, so an on-path attacker can strip
 * a peer's capability token and try to force the weaker path. The rule:
 *   - peer offers the authenticated capability now  → [Decision.SEAL] (caller also pins the peer)
 *   - peer does NOT offer it but we previously pinned → [Decision.BLOCK] (a downgrade attempt — fail closed)
 *   - peer never supported it (not pinned)           → [Decision.PLAINTEXT] (legitimate legacy peer)
 *
 * Kept dependency-free (no Android types) so it is unit-testable on the host JVM.
 */
object DowngradePolicy {
    enum class Decision { SEAL, PLAINTEXT, BLOCK }

    /**
     * @param peerOffersAead whether the peer currently advertises the authenticated capability
     *   (receiver side), or — symmetrically — whether the peer requested the authenticated stream
     *   (sender side, the "fgcm"/"gcm" flag).
     * @param pinned whether we have previously negotiated the authenticated channel with this peer.
     */
    fun decide(peerOffersAead: Boolean, pinned: Boolean): Decision = when {
        peerOffersAead -> Decision.SEAL
        pinned -> Decision.BLOCK
        else -> Decision.PLAINTEXT
    }
}
