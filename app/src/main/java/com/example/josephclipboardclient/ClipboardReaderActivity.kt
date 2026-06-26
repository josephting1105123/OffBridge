package com.EntropicSoftwareLab.ClipboardShare

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class ClipboardReaderActivity : Activity() {

    private var handledInForeground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // As an Activity launched from a notification, the OS natively closes the notification drawer.
        // We evaluate the if/else logic here immediately.
        if (MainActivity.isAppInForeground) {
            handledInForeground = true
            MainActivity.pendingClipboardPush = true
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    // This lifecycle callback fires the exact millisecond the OS grants foreground status
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus && !handledInForeground) {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                var textToPush = ""

                if (clip != null && clip.itemCount > 0) {
                    textToPush = clip.getItemAt(0).text?.toString() ?: ""
                }

                if (textToPush.isNotEmpty()) {
                    // Legal extraction successful; hand off to the background service
                    ClipboardHistoryManager.addEntry(this, textToPush)
                    sendBroadcast(Intent(ClipboardService.ACTION_HISTORY_UPDATED).apply {
                        setPackage(packageName)
                    })
                    
                    val serviceIntent = Intent(this, ClipboardService::class.java).apply {
                        action = ClipboardService.ACTION_EXECUTE_NETWORK_PUSH
                        putExtra("PAYLOAD", textToPush)
                    }
                    startService(serviceIntent)
                } else {
                    Toast.makeText(this, getString(R.string.clip_clipboard_empty), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.clip_read_failed), Toast.LENGTH_SHORT).show()
            }

            // Terminate instantly and suppress Android window animations
            finish()
            overridePendingTransition(0, 0)
        }
    }
}