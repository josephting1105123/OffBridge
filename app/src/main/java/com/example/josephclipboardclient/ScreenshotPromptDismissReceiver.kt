package com.EntropicSoftwareLab.ClipboardShare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** Handles the "Not now" action on the screenshot prompt by clearing that notification. */
class ScreenshotPromptDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScreenshotWatcher.ACTION_DISMISS) return
        val id = intent.getIntExtra(ScreenshotWatcher.EXTRA_NOTIFICATION_ID, -1)
        if (ScreenshotWatcher.isPromptId(id)) NotificationManagerCompat.from(context).cancel(id)
    }
}
