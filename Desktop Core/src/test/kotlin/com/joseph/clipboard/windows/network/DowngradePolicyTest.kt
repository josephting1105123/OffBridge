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

    /**
     * XP-DEF-01: the AUTH2 admission challenge and the v2 FileSeal key (FGCM2) now flow through this
     * same capability-agnostic decision, so this 2x2 table is the golden vector every pinned capability
     * (GCM1, FGCM1, AUTH2, FGCM2) shares — and the Android DowngradePolicyTest asserts it identically.
     */
    @Test
    fun goldenDecisionTableForEveryPinnedCapability() {
        assertEquals(DowngradePolicy.Decision.SEAL,      DowngradePolicy.decide(peerOffersAead = true,  pinned = false))
        assertEquals(DowngradePolicy.Decision.SEAL,      DowngradePolicy.decide(peerOffersAead = true,  pinned = true))
        assertEquals(DowngradePolicy.Decision.PLAINTEXT, DowngradePolicy.decide(peerOffersAead = false, pinned = false))
        assertEquals(DowngradePolicy.Decision.BLOCK,     DowngradePolicy.decide(peerOffersAead = false, pinned = true))
    }
}
