package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context

/**
 * The app's single SharedPreferences file. Every reader/writer must use this constant: the name
 * was previously a raw string at ~40 call sites, where one typo silently opens a fresh empty
 * prefs bucket (lost pairing/history/settings) with no error.
 */
const val FILE_BRIDGE_PREFS = "FileBridgePrefs"

/**
 * Lite (default) vs Pro design mode — the core UI-routing predicate deciding which shell/fragment
 * renders. Centralized so the key and the "Lite" default can't drift between the shell, the four
 * fragments, and the power-menu toggle.
 */
fun isLiteMode(context: Context): Boolean =
    context.getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
        .getString("design_mode", "Lite") == "Lite"

/**
 * Android notification channel IDs. Channels are REGISTERED once in ClipboardService but posted to
 * from several files; a raw-string rename (it happened once already — hence "_V2") leaves external
 * posters on an unregistered channel, which Android 8+ silently drops.
 */
object NotificationChannels {
    const val SERVICE = "CLIPBOARD_SERVICE_CHANNEL_V2"
    const val CLIPBOARD_ALERTS = "CLIPBOARD_ALERT_CHANNEL_V2"
    const val PAIRING = "PAIRING_REQUESTS_CHANNEL_V2"
    const val TRANSFER_REQUEST = "FILE_TRANSFER_REQUEST_CHANNEL_V2"
}
