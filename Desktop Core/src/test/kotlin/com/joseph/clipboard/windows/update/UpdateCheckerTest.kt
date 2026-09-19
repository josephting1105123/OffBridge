package com.joseph.clipboard.windows.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins which failure card a non-success GitHub status produces. The distinction is user-facing: a
 * rate-limited check tells the user to wait, everything else tells them to try again — and Try-again
 * on a throttled address only spends more of the same 60-per-hour budget.
 */
class UpdateCheckerTest {

    private fun classify(status: Int, remaining: String? = null) =
        UpdateChecker.classifyHttpFailure(status, remaining)

    @Test
    fun tooManyRequestsIsAlwaysRateLimited() {
        assertEquals(UpdateStatus.FailureKind.RATE_LIMITED, classify(429))
        assertEquals(UpdateStatus.FailureKind.RATE_LIMITED, classify(429, remaining = "37"))
    }

    @Test
    fun forbiddenWithAnExhaustedBudgetIsRateLimited() {
        assertEquals(UpdateStatus.FailureKind.RATE_LIMITED, classify(403, remaining = "0"))
        // GitHub pads the header value in some responses; a spent budget is still a spent budget.
        assertEquals(UpdateStatus.FailureKind.RATE_LIMITED, classify(403, remaining = " 0 "))
    }

    /** A permission 403 must not tell the user to wait for a quota that was never the problem. */
    @Test
    fun forbiddenWithBudgetLeftIsAPlainServerError() {
        assertEquals(UpdateStatus.FailureKind.HTTP_ERROR, classify(403, remaining = "58"))
        assertEquals(UpdateStatus.FailureKind.HTTP_ERROR, classify(403, remaining = null))
    }

    @Test
    fun otherFailuresAreServerErrors() {
        assertEquals(UpdateStatus.FailureKind.HTTP_ERROR, classify(404))
        assertEquals(UpdateStatus.FailureKind.HTTP_ERROR, classify(500))
        assertEquals(UpdateStatus.FailureKind.HTTP_ERROR, classify(503, remaining = "0"))
    }
}
