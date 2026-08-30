package com.EntropicSoftwareLab.ClipboardShare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Brings the daemon back without the user having to open the app.
 *
 * Three triggers, and the middle one matters more than it looks:
 *
 * - `BOOT_COMPLETED` — after a reboot.
 * - `MY_PACKAGE_REPLACED` — after the app updates itself. Without this the service stays dead
 *   from the moment an update installs until the user next opens the app, which on a fast release
 *   cadence is the most common way to end up "connected" on one device and invisible on the other.
 * - `QUICKBOOT_POWERON` — some OEM ROMs send this instead of BOOT_COMPLETED when the device comes
 *   out of their fast-boot path.
 *
 * A previous version of this file asserted that a `connectedDevice` foreground service cannot be
 * started from BOOT_COMPLETED on Android 14+, swallowed the resulting exception, and treated boot
 * autostart as impossible. That was wrong. The types Android blocks from BOOT_COMPLETED are camera,
 * dataSync, mediaPlayback, mediaProjection and phoneCall (plus microphone since 14) —
 * `connectedDevice` is not among them, and it only requires one of CHANGE_NETWORK_STATE /
 * CHANGE_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE / NFC, three of which this app already declares.
 *
 * Two things this genuinely cannot do anything about, both worth naming because they look like bugs:
 * a force-stopped app receives no broadcast at all until the user launches it by hand (Android 15
 * keeps it in the stopped state on purpose), and several OEM ROMs withhold autostart until the user
 * grants it in their own settings — see [OemAutostart].
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_QUICKBOOT_POWERON
        ) return

        ServiceLifecycleLog.log(context, "BootReceiver: $action")

        val serviceIntent = Intent(context, ClipboardService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            ServiceLifecycleLog.log(context, "BootReceiver: service start requested")
        } catch (e: Exception) {
            // Still guarded — a throw here would crash during boot, which is worse than not
            // starting. But the reason is now recorded instead of vanishing into logcat that
            // nobody will be watching at the moment the phone powers on.
            ServiceLifecycleLog.log(
                context,
                "BootReceiver: start REFUSED: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private companion object {
        /** Not in the Intent constants — it's an OEM addition, so it has to be spelled out. */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
