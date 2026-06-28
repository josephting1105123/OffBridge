package com.joseph.clipboard.windows.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Anti-downgrade (TOFU) decision contract — byte-for-byte mirror of the Android client's
 * DowngradePolicyTest, so both platforms enforce the same seal/plaintext/block rule.
 */
class DowngradePolicyTest {

    @Test
    fun sealsWheneverPeerOffersAuthenticatedChannel() {
        assertEquals(DowngradePolicy.Decision.SEAL, DowngradePolicy.decide(peerOffersAead = true, pinned = false))
        assertEquals(DowngradePolicy.Decision.SEAL, DowngradePolicy.decide(peerOffersAead = true, pinned = true))
    }

    @Test
    fun plaintextForLegacyPeerThatNeverNegotiatedAead() {
        assertEquals(DowngradePolicy.Decision.PLAINTEXT, DowngradePolicy.decide(peerOffersAead = false, pinned = false))
    }

    @Test
    fun blocksDowngradeWhenPinnedPeerDropsCapability() {
        assertEquals(DowngradePolicy.Decision.BLOCK, DowngradePolicy.decide(peerOffersAead = false, pinned = true))
    }
}
