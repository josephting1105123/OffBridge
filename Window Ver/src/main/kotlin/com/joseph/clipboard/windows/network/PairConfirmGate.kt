package com.joseph.clipboard.windows.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The initiator's half of PIN pairing. The peer's PAIR_ACCEPT only says the OTHER human accepted;
 * a rogue host on the LAN can send it straight after PAIR_ACK. Trust is stored only once this
 * side's user has also compared the PINs and tapped "Codes match", in either order.
 *
 * The first decision wins: a Cancel racing a confirm can't be flipped back, and vice versa.
 */
class PairConfirmGate {
    private val decision = AtomicReference<Boolean?>(null)
    private val decided = CountDownLatch(1)

    /** The user tapped "Codes match". */
    fun confirm() = decide(true)

    /** The user cancelled, or left the pairing screen. */
    fun cancel() = decide(false)

    private fun decide(value: Boolean) {
        if (decision.compareAndSet(null, value)) decided.countDown()
    }

    /** True once the user confirmed; false if they cancelled. Null while undecided. */
    val outcome: Boolean? get() = decision.get()

    /**
     * Blocks until the user decides or [timeoutMs] passes. Returns the decision, or null on timeout.
     */
    fun await(timeoutMs: Long): Boolean? {
        decided.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return decision.get()
    }
}
