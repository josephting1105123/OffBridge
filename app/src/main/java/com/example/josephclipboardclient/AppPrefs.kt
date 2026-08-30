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

/**
 * One-shot flags for the prompts that keep the daemon alive in the background.
 *
 * These exist because "ask on every cold start" is not a reminder, it's a wall. The battery
 * exemption dialog used to fire from MainActivity.onCreate on every single launch whether or not
 * the user had already answered it, which trains people to dismiss it reflexively — and a reflexive
 * dismissal of the one prompt that keeps the service running is exactly the failure it was meant to
 * prevent. Ask once; re-offer from the connection-health card, where there's room to say why.
 */
object BackgroundPrefs {

    const val BATTERY_PROMPT_SHOWN = "battery_opt_prompt_shown"
    const val OEM_AUTOSTART_PROMPT_SHOWN = "oem_autostart_prompt_shown"

    fun wasShown(context: Context, key: String): Boolean =
        context.getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(key, false)

    fun markShown(context: Context, key: String) {
        context.getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, true).apply()
    }
}
