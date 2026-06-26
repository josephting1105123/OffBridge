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
            // ClipboardService is a connectedDevice FGS, which is allowed from
            // BOOT_COMPLETED. The try/catch degrades gracefully (instead of
            // crashing boot with ForegroundServiceStartNotAllowedException) if a
            // future policy shift ever restricts this start.
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