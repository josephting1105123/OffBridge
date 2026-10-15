package com.EntropicSoftwareLab.ClipboardShare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.concurrent.thread

/** The initiator saves trust only on a real "Codes match"; the first decision sticks. */
class PairConfirmGateTest {

    @Test
    fun undecidedGateTimesOutAsNull() {
        val gate = PairConfirmGate()
        assertNull(gate.await(10))
        assertNull(gate.outcome)
    }

    @Test
    fun confirmBeforeAwaitReturnsAtOnce() {
        val gate = PairConfirmGate()
        gate.confirm()
        assertEquals(true, gate.await(0))
    }

    @Test
    fun cancelAfterConfirmDoesNotFlipIt() {
        val gate = PairConfirmGate()
        gate.confirm()
        gate.cancel()
        assertEquals(true, gate.await(0))
    }

    @Test
    fun confirmAfterCancelDoesNotFlipIt() {
        val gate = PairConfirmGate()
        gate.cancel()
        gate.confirm()
        assertEquals(false, gate.await(0))
    }

    /** The common order: PAIR_ACCEPT arrives first and the handshake blocks until the user taps. */
    @Test
    fun awaitWakesWhenTheUserConfirmsLater() {
        val gate = PairConfirmGate()
        val t = thread { Thread.sleep(50); gate.confirm() }
        assertEquals(true, gate.await(5_000))
        t.join()
    }

    @Test
    fun negativeRemainingWindowDoesNotThrow() {
        assertNull(PairConfirmGate().await(-1))
    }
}
