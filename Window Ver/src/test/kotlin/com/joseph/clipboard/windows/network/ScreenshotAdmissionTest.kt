package com.joseph.clipboard.windows.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-accept gate. Each test is one way a hostile or confused sender could turn "open my
 * screenshot" into "write and launch a file of my choosing", and each has to fall back to the
 * ordinary accept prompt rather than proceeding.
 */
class ScreenshotAdmissionTest {

    private val now = 1_756_600_000_000L

    private fun expected(
        name: String = "Screenshot_1.png",
        size: Long = 204_800L,
        requestedAtMs: Long = now - 1_000L,
    ) = ScreenshotAdmission.Expected("sess01", name, size, requestedAtMs)

    @Test
    fun acceptsTheExactFileThatWasAnnounced() {
        assertTrue(
            ScreenshotAdmission.mayAutoAccept(
                expected(), listOf("Screenshot_1.png" to 204_800L), now
            )
        )
    }

    @Test
    fun refusesWhenNoFetchIsOutstanding() {
        // An unsolicited offer is just an offer; it gets the normal prompt.
        assertFalse(ScreenshotAdmission.mayAutoAccept(null, listOf("Screenshot_1.png" to 204_800L), now))
    }

    @Test
    fun refusesAfterTheDeadline() {
        val stale = expected(requestedAtMs = now - ScreenshotAdmission.FETCH_DEADLINE_MS - 1)
        assertFalse(ScreenshotAdmission.mayAutoAccept(stale, listOf("Screenshot_1.png" to 204_800L), now))
    }

    @Test
    fun refusesAFetchTimestampedInTheFuture() {
        val skewed = expected(requestedAtMs = now + 5_000L)
        assertFalse(ScreenshotAdmission.mayAutoAccept(skewed, listOf("Screenshot_1.png" to 204_800L), now))
    }

    @Test
    fun refusesAManifestCarryingMoreThanTheScreenshot() {
        // The payload-alongside attack: ask for one image, receive it plus something else.
        assertFalse(
            ScreenshotAdmission.mayAutoAccept(
                expected(),
                listOf("Screenshot_1.png" to 204_800L, "payload.exe" to 10L),
                now,
            )
        )
        assertFalse(ScreenshotAdmission.mayAutoAccept(expected(), emptyList(), now))
    }

    @Test
    fun refusesADifferentFileUnderTheSameSession() {
        assertFalse(ScreenshotAdmission.mayAutoAccept(expected(), listOf("other.png" to 204_800L), now))
        assertFalse(ScreenshotAdmission.mayAutoAccept(expected(), listOf("Screenshot_1.png" to 999L), now))
    }

    @Test
    fun refusesAnExecutableEvenWhenEverythingElseMatches() {
        // The announcement itself is attacker-controlled, so matching it is not enough on its own.
        val announced = expected(name = "Screenshot_1.png.exe")
        assertFalse(
            ScreenshotAdmission.mayAutoAccept(announced, listOf("Screenshot_1.png.exe" to 204_800L), now)
        )
    }

    @Test
    fun openIsRecheckedIndependentlyOfAccept() {
        assertTrue(ScreenshotAdmission.mayOpen("Screenshot_1.png"))
        assertTrue(ScreenshotAdmission.mayOpen("shot (1).jpeg"))
        // A de-collision rename between accept and open must not smuggle anything past the gate.
        assertFalse(ScreenshotAdmission.mayOpen("Screenshot_1.bat"))
        assertFalse(ScreenshotAdmission.mayOpen("Screenshot_1"))
    }
}
