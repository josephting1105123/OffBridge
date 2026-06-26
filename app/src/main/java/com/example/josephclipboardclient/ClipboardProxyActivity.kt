package com.EntropicSoftwareLab.ClipboardShare

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

class ClipboardProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Do nothing here. The activity is built, but not yet focused.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        // Only execute the read once the OS confirms we have legally stolen focus
        if (hasFocus) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // Guard itemCount: getItemAt(0) throws IndexOutOfBounds on an empty clip.
            val clip = clipboard.primaryClip
            val textToSync = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString()
            } else null

            if (!textToSync.isNullOrEmpty()) {
                val serviceIntent = Intent(this, ClipboardService::class.java).apply {
                    // Must match the action ClipboardService.onStartCommand handles, otherwise
                    // the intent is silently dropped and the clipboard never gets pushed.
                    action = ClipboardService.ACTION_EXECUTE_NETWORK_PUSH
                    putExtra("PAYLOAD", textToSync)
                }
                startService(serviceIntent)
            }

            // Instantly destroy the proxy after transmission
            finish()
        }
    }
}