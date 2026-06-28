package com.EntropicSoftwareLab.ClipboardShare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, ClipboardService::class.java)
            // NOTE: ClipboardService is a `connectedDevice` FGS, and on Android 14+ (API 34+)
            // that type is NOT permitted to be started from BOOT_COMPLETED — the start throws
            // ForegroundServiceStartNotAllowedException. The try/catch below swallows it so boot
            // never crashes; the practical effect on modern devices is that the daemon instead
            // starts the next time the user opens the app (MainActivity/SplashActivity call
            // TransferDaemon.start). Reliable boot autostart on 14+ would need a boot-allowed FGS
            // type (e.g. specialUse, with a Play Console declaration) and on-device verification.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.w("BootReceiver", "Boot foreground-service start was not allowed", e)
            }
        }
    }
}