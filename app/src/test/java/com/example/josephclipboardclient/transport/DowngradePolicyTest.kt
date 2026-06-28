package com.EntropicSoftwareLab.ClipboardShare.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Anti-downgrade (TOFU) decision contract, shared by the file-stream (FGCM1) and clipboard (GCM1)
 * paths and mirrored by the Windows client's DowngradePolicy. Pure host-JVM tests.
 */
class DowngradePolicyTest {

    @Test
    fun sealsWheneverPeerOffersAuthenticatedChannel() {
        // First authenticated contact (not yet pinned) and a re-contact (already pinned) both seal.
        assertEquals(DowngradePolicy.Decision.SEAL, DowngradePolicy.decide(peerOffersAead = true, pinned = false))
        assertEquals(DowngradePolicy.Decision.SEAL, DowngradePolicy.decide(peerOffersAead = true, pinned = true))
    }

    @Test
    fun plaintextForLegacyPeerThatNeverNegotiatedAead() {
        assertEquals(DowngradePolicy.Decision.PLAINTEXT, DowngradePolicy.decide(peerOffersAead = false, pinned = false))
    }

    @Test
    fun blocksDowngradeWhenPinnedPeerDropsCapability() {
        // The attack case: we previously negotiated AEAD with this peer, now the capability is gone.
        assertEquals(DowngradePolicy.Decision.BLOCK, DowngradePolicy.decide(peerOffersAead = false, pinned = true))
    }
}
