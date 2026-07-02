package com.EntropicSoftwareLab.ClipboardShare.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision-logic coverage for Wi-Fi Direct group-owner / host election.
 * The score() probe needs a Context and is excluded; electReceiverAsGo / shouldReceiverLeadAsHost /
 * electHost are pure and drive who hosts the group, so they get exhaustive truth-table tests.
 */
class GoFitnessTest {

    @Test
    fun receiverHostsOnlyWhenStrictlyHigher() {
        assertTrue(GoFitness.electReceiverAsGo(senderGof = 5, receiverGof = 6))
        assertFalse(GoFitness.electReceiverAsGo(senderGof = 6, receiverGof = 5))
        assertFalse("tie must yield to sender", GoFitness.electReceiverAsGo(senderGof = 5, receiverGof = 5))
        assertTrue("legacy sender (-1) never wins", GoFitness.electReceiverAsGo(senderGof = -1, receiverGof = 0))
        assertFalse(GoFitness.electReceiverAsGo(senderGof = 0, receiverGof = -1))
        assertFalse(GoFitness.electReceiverAsGo(senderGof = 15, receiverGof = 15))
    }

    @Test
    fun shouldReceiverLeadOnlyWhenItIsTheLoneFreeDevice() {
        assertTrue(GoFitness.shouldReceiverLeadAsHost(senderFree = false, receiverFree = true))
        assertFalse(GoFitness.shouldReceiverLeadAsHost(senderFree = true, receiverFree = true))
        assertFalse(GoFitness.shouldReceiverLeadAsHost(senderFree = false, receiverFree = false))
        assertFalse(GoFitness.shouldReceiverLeadAsHost(senderFree = true, receiverFree = false))
    }

    /** Reference reimplementation of the documented electHost rule; the test asserts parity over all inputs. */
    private fun expected(sh: Boolean, rh: Boolean, sf: Boolean, rf: Boolean, rc: Boolean): Boolean {
        if (!rc) return false
        return when {
            sh && rh -> rf && !sf            // both have hotspots: lone-free receiver leads
            sh && !rh -> true                // sender busy with its hotspot -> free receiver hosts
            else -> sf && !rf                // plain Wi-Fi Direct: busy receiver hosts, free sender joins
        }
    }

    @Test
    fun electHostMatchesRuleAcrossAllInputs() {
        val bools = booleanArrayOf(false, true)
        var cases = 0
        for (sh in bools) for (rh in bools) for (sf in bools) for (rf in bools) for (rc in bools) {
            val actual = GoFitness.electHost(
                senderHasHotspot = sh, receiverHasHotspot = rh,
                senderFree = sf, receiverFree = rf, receiverCanHost = rc
            )
            assertEquals("sh=$sh rh=$rh sf=$sf rf=$rf rc=$rc", expected(sh, rh, sf, rf, rc), actual)
            cases++
        }
        assertEquals("all 32 combinations covered", 32, cases)
    }

    @Test
    fun electHostNeverLeadsWithAReceiverThatCannotHost() {
        val bools = booleanArrayOf(false, true)
        for (sh in bools) for (rh in bools) for (sf in bools) for (rf in bools) {
            assertFalse(
                GoFitness.electHost(sh, rh, sf, rf, receiverCanHost = false)
            )
        }
    }
}
