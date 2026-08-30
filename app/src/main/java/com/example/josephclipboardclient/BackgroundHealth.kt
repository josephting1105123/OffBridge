package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/**
 * Why the background daemon isn't staying up, in terms the user can act on.
 *
 * The failure this exists for is silence: the app looks connected, the other device can't see it,
 * and nothing anywhere says which of the four gates is closed. Each entry maps to exactly one
 * fixable setting.
 */
enum class BackgroundIssue {
    /** Doze/App Standby can suspend the daemon's network access. The single biggest cause. */
    BATTERY_OPTIMIZED,

    /** A vendor autostart list is withholding permission to run — see [OemAutostart]. */
    OEM_AUTOSTART_UNKNOWN,

    /** No POST_NOTIFICATIONS grant: the foreground-service notification can't be shown. */
    NOTIFICATIONS_BLOCKED,

    /** Down, and none of the above explains it. Distinguishes "unexplained" from "not diagnosed". */
    SERVICE_STOPPED,
}

object BackgroundHealth {

    /**
     * The pure decision, split out from the Context reads so it can be tested directly.
     *
     * [oemAutostartVisited] is deliberately "has the user ever opened that screen", not "is it
     * granted" — no OEM exposes a way to query the grant, so the honest state is unknown-unless-asked
     * and the worst outcome is one dismissible row on a device that was already fine.
     *
     * [SERVICE_STOPPED] is reported only when the daemon is down and nothing else accounts for it.
     * Listing it alongside a known cause would just be restating the symptom next to its diagnosis.
     */
    fun evaluate(
        batteryExempt: Boolean,
        notificationsEnabled: Boolean,
        oemAutostartAvailable: Boolean,
        oemAutostartVisited: Boolean,
        daemonRunning: Boolean,
    ): List<BackgroundIssue> {
        val causes = buildList {
            if (!batteryExempt) add(BackgroundIssue.BATTERY_OPTIMIZED)
            if (oemAutostartAvailable && !oemAutostartVisited) add(BackgroundIssue.OEM_AUTOSTART_UNKNOWN)
            if (!notificationsEnabled) add(BackgroundIssue.NOTIFICATIONS_BLOCKED)
        }
        if (causes.isEmpty() && !daemonRunning) return listOf(BackgroundIssue.SERVICE_STOPPED)
        return causes
    }

    /** Reads the live device state and hands it to [evaluate]. */
    fun evaluate(context: Context): List<BackgroundIssue> = evaluate(
        batteryExempt = isBatteryExempt(context),
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        oemAutostartAvailable = OemAutostart.isAvailable(context),
        oemAutostartVisited = BackgroundPrefs.wasShown(context, BackgroundPrefs.OEM_AUTOSTART_PROMPT_SHOWN),
        daemonRunning = TransferDaemon.isRunning,
    )

    fun isBatteryExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            // A vendor ROM that throws here is not evidence of a problem; don't invent a warning.
            true
        }
    }
}
