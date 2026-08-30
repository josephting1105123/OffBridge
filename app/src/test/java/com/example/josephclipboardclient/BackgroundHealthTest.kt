package com.EntropicSoftwareLab.ClipboardShare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which background-execution gate to blame, given the device state. Pure host-JVM tests over the
 * Context-free overload — the Context-reading one is a thin wrapper around these five booleans.
 */
class BackgroundHealthTest {

    private fun evaluate(
        batteryExempt: Boolean = true,
        notificationsEnabled: Boolean = true,
        oemAutostartAvailable: Boolean = false,
        oemAutostartVisited: Boolean = false,
        daemonRunning: Boolean = true,
    ) = BackgroundHealth.evaluate(
        batteryExempt, notificationsEnabled, oemAutostartAvailable, oemAutostartVisited, daemonRunning
    )

    @Test
    fun healthyDeviceReportsNothing() {
        assertEquals(emptyList<BackgroundIssue>(), evaluate())
    }

    @Test
    fun batteryOptimizationIsReported() {
        assertEquals(listOf(BackgroundIssue.BATTERY_OPTIMIZED), evaluate(batteryExempt = false))
    }

    @Test
    fun oemAutostartOnlyWhenAvailableAndNotYetVisited() {
        assertEquals(
            listOf(BackgroundIssue.OEM_AUTOSTART_UNKNOWN),
            evaluate(oemAutostartAvailable = true, oemAutostartVisited = false)
        )
        // Already sent there once: stop nagging. We can't read the grant, so asking twice adds nothing.
        assertEquals(
            emptyList<BackgroundIssue>(),
            evaluate(oemAutostartAvailable = true, oemAutostartVisited = true)
        )
        // A Pixel has no such screen — never offer a row that dead-ends.
        assertEquals(
            emptyList<BackgroundIssue>(),
            evaluate(oemAutostartAvailable = false, oemAutostartVisited = false)
        )
    }

    @Test
    fun blockedNotificationsAreReported() {
        assertEquals(listOf(BackgroundIssue.NOTIFICATIONS_BLOCKED), evaluate(notificationsEnabled = false))
    }

    @Test
    fun stoppedServiceIsReportedOnlyWhenNothingElseExplainsIt() {
        assertEquals(listOf(BackgroundIssue.SERVICE_STOPPED), evaluate(daemonRunning = false))

        // With a known cause present, SERVICE_STOPPED is just the symptom restated next to its
        // diagnosis — the card should name the fixable thing, not both.
        val withCause = evaluate(batteryExempt = false, daemonRunning = false)
        assertEquals(listOf(BackgroundIssue.BATTERY_OPTIMIZED), withCause)
    }

    @Test
    fun everyGateClosedListsEveryCauseMostImpactfulFirst() {
        val all = evaluate(
            batteryExempt = false,
            notificationsEnabled = false,
            oemAutostartAvailable = true,
            oemAutostartVisited = false,
            daemonRunning = false,
        )
        assertEquals(
            listOf(
                BackgroundIssue.BATTERY_OPTIMIZED,
                BackgroundIssue.OEM_AUTOSTART_UNKNOWN,
                BackgroundIssue.NOTIFICATIONS_BLOCKED,
            ),
            all
        )
        assertTrue(BackgroundIssue.SERVICE_STOPPED !in all)
    }
}
